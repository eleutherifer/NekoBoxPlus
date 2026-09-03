//go:build with_adblock && with_quic

package adblock

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"io"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing-box/adapter"
	adblockctx "github.com/sagernet/sing-box/experimental/adblock/ctx"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

func TestAdblockHTTP3PacketConnUsesClientAddrForQUICAndServerAddrForSing(t *testing.T) {
	client := M.ParseSocksaddrHostPort("192.0.2.10", 55000)
	server := M.ParseSocksaddrHostPort("203.0.113.20", 443)
	conn := &recordingH3PacketConn{
		readData: []byte("client initial"),
		readAddr: server,
	}
	packetConn := &adblockHTTP3PacketConn{
		PacketConn:  conn,
		source:      client,
		destination: server,
		localAddr:   server.UDPAddr(),
	}

	readBuffer := make([]byte, 64)
	n, addr, err := packetConn.ReadFrom(readBuffer)
	if err != nil {
		t.Fatal(err)
	}
	if string(readBuffer[:n]) != "client initial" {
		t.Fatalf("unexpected read payload: %q", readBuffer[:n])
	}
	if M.SocksaddrFromNet(addr) != client {
		t.Fatalf("ReadFrom addr = %v, want client %v", addr, client)
	}

	if _, err = packetConn.WriteTo([]byte("server initial"), client.UDPAddr()); err != nil {
		t.Fatal(err)
	}
	if string(conn.writtenData) != "server initial" {
		t.Fatalf("unexpected written payload: %q", conn.writtenData)
	}
	if conn.writtenAddr != server {
		t.Fatalf("WriteTo destination = %v, want server %v", conn.writtenAddr, server)
	}
}

func TestAdblockHTTP3FakeIPUsesOriginDestinationForClientSide(t *testing.T) {
	fakeDestination := M.ParseSocksaddrHostPort("198.18.0.94", 443)
	metadata := adapter.InboundContext{
		FakeIP:            true,
		Destination:       M.ParseSocksaddrHostPort("google.com", 443),
		OriginDestination: fakeDestination,
	}
	if got := adblockHTTP3ClientDestination(metadata); got != fakeDestination {
		t.Fatalf("client destination = %v, want fake destination %v", got, fakeDestination)
	}
	if got := M.SocksaddrFromNet(adblockHTTP3LocalAddr(metadata)); got != fakeDestination {
		t.Fatalf("local addr = %v, want fake destination %v", got, fakeDestination)
	}

	metadata.FakeIP = false
	metadata.Destination = M.ParseSocksaddrHostPort("203.0.113.20", 443)
	if got := adblockHTTP3ClientDestination(metadata); got != metadata.Destination {
		t.Fatalf("non-FakeIP client destination = %v, want %v", got, metadata.Destination)
	}
}

func TestHandleQUICHTTPCompletesHandshakeAndForwardsRequest(t *testing.T) {
	upstreamCert := newServerCertificate(t)
	upstreamPacketConn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	upstreamDestination := M.SocksaddrFromNet(upstreamPacketConn.LocalAddr())
	requestHost := make(chan string, 1)
	upstreamServer := &http3.Server{
		TLSConfig: http3.ConfigureTLSConfig(&tls.Config{
			Certificates: []tls.Certificate{upstreamCert},
		}),
		Handler: http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
			requestHost <- request.Host
			_, _ = writer.Write([]byte("h3 ok"))
		}),
	}
	go func() {
		_ = upstreamServer.Serve(upstreamPacketConn)
	}()
	t.Cleanup(func() {
		_ = upstreamServer.Close()
		_ = upstreamPacketConn.Close()
	})

	upstreamRoots := x509.NewCertPool()
	upstreamLeaf, err := x509.ParseCertificate(upstreamCert.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	upstreamRoots.AddCert(upstreamLeaf)
	serviceCtx := service.ContextWith[adapter.CertificateStore](t.Context(), h3TestCertificateStore{pool: upstreamRoots})

	tlsCA := newTestTLSCA(t)
	adblockPacketConn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	inboundPacketConn := &recordingH3UDPPacketConn{
		PacketConn:          adblockPacketConn,
		originalDestination: upstreamDestination,
	}
	adblockService := newTestService(serviceCtx, option.AdblockOptions{}, &fakeAdblockEngine{})
	adblockService.tlsCA = tlsCA

	handlerCtx, cancelHandler := context.WithCancel(t.Context())
	defer cancelHandler()
	errCh := make(chan error, 1)
	go func() {
		errCh <- adblockService.handleQUICHTTP(&adblockctx.Conn{
			Ctx:        handlerCtx,
			Engine:     &fakeAdblockEngine{},
			PacketConn: inboundPacketConn,
			Metadata: adapter.InboundContext{
				Source:      M.ParseSocksaddrHostPort("127.0.0.1", 55000),
				Destination: upstreamDestination,
				Domain:      "example.com",
			},
			Outbound: h3TestOutbound{destination: upstreamDestination},
		})
	}()
	t.Cleanup(func() {
		cancelHandler()
		_ = adblockPacketConn.Close()
		select {
		case err := <-errCh:
			if err != nil {
				t.Errorf("HTTP/3 handler failed: %v", err)
			}
		case <-time.After(time.Second):
			t.Error("HTTP/3 handler did not stop")
		}
	})

	mitmRoots := x509.NewCertPool()
	mitmRoots.AddCert(tlsCA.certificate)
	client := &http.Client{
		Transport: &http3.Transport{
			TLSClientConfig: &tls.Config{
				RootCAs: mitmRoots,
			},
			Dial: func(ctx context.Context, _ string, tlsConfig *tls.Config, quicConfig *quic.Config) (*quic.Conn, error) {
				conn, err := net.ListenPacket("udp", "127.0.0.1:0")
				if err != nil {
					return nil, err
				}
				quicConn, err := quic.DialEarly(ctx, conn, adblockPacketConn.LocalAddr(), tlsConfig, quicConfig)
				if err != nil {
					_ = conn.Close()
					return nil, err
				}
				return quicConn, nil
			},
		},
	}
	defer client.Transport.(*http3.Transport).Close()

	requestCtx, cancelRequest := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancelRequest()
	request, err := http.NewRequestWithContext(requestCtx, http.MethodGet, "https://example.com/", nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", response.StatusCode)
	}
	if string(body) != "h3 ok" {
		t.Fatalf("body = %q, want h3 ok", body)
	}
	if host := <-requestHost; host != "example.com" {
		t.Fatalf("request host = %q, want example.com", host)
	}
	if inboundPacketConn.lastWriteDestination() != upstreamDestination {
		t.Fatalf("QUIC response destination = %v, want %v", inboundPacketConn.lastWriteDestination(), upstreamDestination)
	}
}

type recordingH3PacketConn struct {
	readData []byte
	readAddr M.Socksaddr

	writtenData []byte
	writtenAddr M.Socksaddr
	closed      bool
}

func (c *recordingH3PacketConn) ReadPacket(buffer *buf.Buffer) (M.Socksaddr, error) {
	if c.readData == nil {
		return M.Socksaddr{}, io.ErrClosedPipe
	}
	_, _ = buffer.Write(c.readData)
	c.readData = nil
	return c.readAddr, nil
}

func (c *recordingH3PacketConn) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	defer buffer.Release()
	c.writtenData = append(c.writtenData[:0], buffer.Bytes()...)
	c.writtenAddr = destination
	return nil
}

func (c *recordingH3PacketConn) Close() error {
	c.closed = true
	return nil
}

func (c *recordingH3PacketConn) LocalAddr() net.Addr {
	return M.ParseSocksaddrHostPort("198.51.100.1", 443).UDPAddr()
}

func (c *recordingH3PacketConn) SetDeadline(time.Time) error {
	return nil
}

func (c *recordingH3PacketConn) SetReadDeadline(time.Time) error {
	return nil
}

func (c *recordingH3PacketConn) SetWriteDeadline(time.Time) error {
	return nil
}

type recordingH3UDPPacketConn struct {
	net.PacketConn
	access              sync.Mutex
	originalDestination M.Socksaddr
	clientAddr          net.Addr
	writtenDestination  M.Socksaddr
}

func (c *recordingH3UDPPacketConn) ReadPacket(buffer *buf.Buffer) (M.Socksaddr, error) {
	n, addr, err := c.PacketConn.ReadFrom(buffer.FreeBytes())
	if err != nil {
		return M.Socksaddr{}, err
	}
	buffer.Truncate(n)
	c.access.Lock()
	c.clientAddr = addr
	c.access.Unlock()
	return c.originalDestination, nil
}

func (c *recordingH3UDPPacketConn) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	defer buffer.Release()
	c.access.Lock()
	c.writtenDestination = destination
	clientAddr := c.clientAddr
	c.access.Unlock()
	_, err := c.PacketConn.WriteTo(buffer.Bytes(), clientAddr)
	return err
}

func (c *recordingH3UDPPacketConn) lastWriteDestination() M.Socksaddr {
	c.access.Lock()
	defer c.access.Unlock()
	return c.writtenDestination
}

type h3TestOutbound struct {
	destination M.Socksaddr
}

func (h3TestOutbound) Type() string {
	return "test"
}

func (h3TestOutbound) Tag() string {
	return "test"
}

func (h3TestOutbound) Network() []string {
	return []string{N.NetworkUDP}
}

func (h3TestOutbound) Dependencies() []string {
	return nil
}

func (o h3TestOutbound) DialContext(ctx context.Context, network string, _ M.Socksaddr) (net.Conn, error) {
	var dialer net.Dialer
	return dialer.DialContext(ctx, network, o.destination.String())
}

func (h3TestOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, net.ErrClosed
}

type h3TestCertificateStore struct {
	pool *x509.CertPool
}

func (h3TestCertificateStore) Name() string {
	return "test-certificate-store"
}

func (h3TestCertificateStore) Start(adapter.StartStage) error {
	return nil
}

func (h3TestCertificateStore) Close() error {
	return nil
}

func (s h3TestCertificateStore) Pool() *x509.CertPool {
	return s.pool
}

func (h3TestCertificateStore) ExclusiveAnchors() bool {
	return false
}
