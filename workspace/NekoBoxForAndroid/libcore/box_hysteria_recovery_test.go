package libcore

import (
	"context"
	"crypto/x509"
	"encoding/pem"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/hysteria2"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
)

type recoveryUDPProxy struct {
	conn       *net.UDPConn
	serverAddr *net.UDPAddr
	clientAddr atomic.Pointer[net.UDPAddr]
	drop       atomic.Bool
}

func newRecoveryUDPProxy(t *testing.T, serverAddr *net.UDPAddr) *recoveryUDPProxy {
	t.Helper()
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	proxy := &recoveryUDPProxy{conn: conn, serverAddr: serverAddr}
	t.Cleanup(func() { conn.Close() })
	go func() {
		buffer := make([]byte, 64*1024)
		for {
			n, source, readErr := conn.ReadFromUDP(buffer)
			if readErr != nil {
				return
			}
			if proxy.drop.Load() {
				continue
			}
			var destination *net.UDPAddr
			if source.Port == serverAddr.Port && source.IP.Equal(serverAddr.IP) {
				destination = proxy.clientAddr.Load()
			} else {
				proxy.clientAddr.Store(source)
				destination = serverAddr
			}
			if destination != nil {
				_, _ = conn.WriteToUDP(buffer[:n], destination)
			}
		}
	}()
	return proxy
}

func newRecoveryUDPEcho(t *testing.T) *net.UDPConn {
	t.Helper()
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	go func() {
		buffer := make([]byte, 2048)
		for {
			n, source, readErr := conn.ReadFromUDP(buffer)
			if readErr != nil {
				return
			}
			_, _ = conn.WriteToUDP(buffer[:n], source)
		}
	}()
	return conn
}

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

func TestHysteriaExpiredSessionRecoversWithoutProcessRestart(t *testing.T) {
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
	serverAddr := portReservation.LocalAddr().(*net.UDPAddr)
	port := uint16(serverAddr.Port)
	portReservation.Close()
	listen := badoption.Addr(netip.MustParseAddr("127.0.0.1"))
	quicOptions := option.QUICOptions{HTTP2Options: option.HTTP2Options{
		IdleTimeout:     badoption.Duration(time.Second),
		KeepAlivePeriod: badoption.Duration(200 * time.Millisecond),
	}}
	newRecoveryTestCore(t, option.Options{
		Inbounds: []option.Inbound{{Type: "hysteria2", Options: &option.Hysteria2InboundOptions{
			ListenOptions: option.ListenOptions{Listen: &listen, ListenPort: port},
			Users:         []option.Hysteria2User{{Password: "local-test"}},
			InboundTLSOptionsContainer: option.InboundTLSOptionsContainer{TLS: &option.InboundTLSOptions{
				Enabled: true, Certificate: []string{certificatePEM}, Key: []string{keyPEM},
			}},
			QUICOptions: quicOptions,
		}}},
		Outbounds: []option.Outbound{{Type: "direct", Options: &option.DirectOutboundOptions{}}},
	})
	proxy := newRecoveryUDPProxy(t, serverAddr)
	proxyPort := uint16(proxy.conn.LocalAddr().(*net.UDPAddr).Port)
	core := newRecoveryTestCore(t, option.Options{
		Outbounds: []option.Outbound{{Type: "hysteria2", Tag: "hy2", Options: &option.Hysteria2OutboundOptions{
			ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: proxyPort},
			Password:      "local-test",
			OutboundTLSOptionsContainer: option.OutboundTLSOptionsContainer{TLS: &option.OutboundTLSOptions{
				Enabled: true, Insecure: true,
			}},
			QUICOptions:         quicOptions,
			DisableChromeParrot: true,
		}}},
	})
	outbound, found := core.Outbound().Outbound("hy2")
	if !found {
		t.Fatal("missing Hysteria2 outbound")
	}
	echo := newRecoveryUDPEcho(t)
	destination := M.SocksaddrFromNet(echo.LocalAddr())
	type packetReadResult struct {
		n   int
		err error
	}
	readPacket := func(packetConn net.PacketConn, buffer []byte) <-chan packetReadResult {
		result := make(chan packetReadResult, 1)
		go func() {
			n, _, readErr := packetConn.ReadFrom(buffer)
			result <- packetReadResult{n: n, err: readErr}
		}()
		return result
	}
	openAndProbe := func() net.PacketConn {
		t.Helper()
		ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
		defer cancel()
		packetConn, listenErr := outbound.ListenPacket(ctx, destination)
		if listenErr != nil {
			t.Fatalf("open packet session: %v", listenErr)
		}
		if _, writeErr := packetConn.WriteTo([]byte("ping"), echo.LocalAddr()); writeErr != nil {
			packetConn.Close()
			t.Fatalf("write packet: %v", writeErr)
		}
		buffer := make([]byte, 4)
		select {
		case result := <-readPacket(packetConn, buffer):
			if result.err != nil {
				packetConn.Close()
				t.Fatalf("read packet: %v", result.err)
			}
			if result.n != len(buffer) || string(buffer) != "ping" {
				packetConn.Close()
				t.Fatalf("unexpected echo: %q", buffer[:result.n])
			}
		case <-time.After(5 * time.Second):
			packetConn.Close()
			t.Fatal("timed out waiting for packet echo")
		}
		return packetConn
	}

	oldSession := openAndProbe()
	t.Cleanup(func() { oldSession.Close() })
	proxy.drop.Store(true)
	select {
	case result := <-readPacket(oldSession, make([]byte, 1)):
		if result.err == nil {
			t.Fatal("expired UDP session remained readable")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("expired UDP session remained open after its QUIC connection timed out")
	}
	proxy.drop.Store(false)
	newSession := openAndProbe()
	newSession.Close()
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
