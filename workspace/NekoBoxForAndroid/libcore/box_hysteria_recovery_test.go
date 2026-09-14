package libcore

import (
	"context"
	"crypto/x509"
	"encoding/pem"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/hysteria2"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
)

func TestHysteriaResetReplacesLiveSession(t *testing.T) {
	fixture := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	certificate := fixture.TLS.Certificates[0]
	fixture.Close()
	key, err := x509.MarshalPKCS8PrivateKey(certificate.PrivateKey)
	if err != nil {
		t.Fatal(err)
	}
	certificatePEM := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certificate.Certificate[0]}))
	keyPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key}))
	portReservation, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := uint16(portReservation.LocalAddr().(*net.UDPAddr).Port)
	portReservation.Close()
	listen := badoption.Addr(netip.MustParseAddr("127.0.0.1"))
	newRecoveryTestCore(t, option.Options{
		Inbounds: []option.Inbound{{Type: "hysteria2", Options: &option.Hysteria2InboundOptions{
			ListenOptions: option.ListenOptions{Listen: &listen, ListenPort: port},
			Users:         []option.Hysteria2User{{Password: "local-test"}},
			InboundTLSOptionsContainer: option.InboundTLSOptionsContainer{TLS: &option.InboundTLSOptions{
				Enabled: true, Certificate: []string{certificatePEM}, Key: []string{keyPEM},
			}},
		}}},
		Outbounds: []option.Outbound{{Type: "direct", Options: &option.DirectOutboundOptions{}}},
	})
	core := newRecoveryTestCore(t, option.Options{
		Outbounds: []option.Outbound{{Type: "hysteria2", Tag: "hy2", Options: &option.Hysteria2OutboundOptions{
			ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: port},
			Password:      "local-test",
			OutboundTLSOptionsContainer: option.OutboundTLSOptionsContainer{TLS: &option.OutboundTLSOptions{
				Enabled: true, Insecure: true,
			}},
		}}},
	})
	outbound, found := core.Outbound().Outbound("hy2")
	if !found {
		t.Fatal("missing Hysteria2 outbound")
	}
	httpServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	t.Cleanup(httpServer.Close)
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			return outbound.DialContext(ctx, network, M.ParseSocksaddr(address))
		},
		DisableKeepAlives: true,
	}
	t.Cleanup(transport.CloseIdleConnections)
	httpClient := &http.Client{Transport: transport, Timeout: 5 * time.Second}
	probe := func() {
		t.Helper()
		response, probeErr := httpClient.Head(httpServer.URL)
		if probeErr != nil {
			t.Fatal(probeErr)
		}
		response.Body.Close()
		if response.StatusCode != http.StatusNoContent {
			t.Fatalf("unexpected HEAD status: %d", response.StatusCode)
		}
	}
	probe()
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	oldSession, err := outbound.ListenPacket(ctx, M.ParseSocksaddr("127.0.0.1:9"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { oldSession.Close() })
	instance := &BoxInstance{Box: core, ctx: ctx, state: boxStateStarted}
	if err = instance.ResetNetwork(); err != nil {
		t.Fatal(err)
	}
	oldSession.SetReadDeadline(time.Now().Add(time.Second))
	_, _, err = oldSession.ReadFrom(make([]byte, 1))
	if err == nil {
		t.Fatal("old UDP session survived reset")
	}
	if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
		t.Fatal("old UDP session timed out instead of being closed by reset")
	}
	probe()
}

func newRecoveryTestCore(t *testing.T, options option.Options) *box.Box {
	t.Helper()
	inbounds := nekoboxAndroidInboundRegistry()
	hysteria2.RegisterInbound(inbounds)
	ctx := box.Context(t.Context(), inbounds, nekoboxAndroidOutboundRegistry(),
		nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil),
		nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateProviderRegistry())
	options.Log = &option.LogOptions{Disabled: true}
	core, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { core.Close() })
	if err = core.Start(); err != nil {
		t.Fatal(err)
	}
	return core
}
