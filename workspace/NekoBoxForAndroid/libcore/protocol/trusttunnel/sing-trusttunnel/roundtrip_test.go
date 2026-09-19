package trusttunnel

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	stdtls "crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"io"
	"math/big"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/tls"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"golang.org/x/net/http2"
)

// testClientTLSConfig wraps *stdtls.Config to satisfy singtls.Config.
type testClientTLSConfig struct{ config *stdtls.Config }

func (c *testClientTLSConfig) ServerName() string                 { return c.config.ServerName }
func (c *testClientTLSConfig) SetServerName(serverName string)    { c.config.ServerName = serverName }
func (c *testClientTLSConfig) NextProtos() []string               { return c.config.NextProtos }
func (c *testClientTLSConfig) SetNextProtos(p []string)           { c.config.NextProtos = p }
func (c *testClientTLSConfig) HandshakeTimeout() time.Duration    { return 0 }
func (c *testClientTLSConfig) SetHandshakeTimeout(time.Duration)  {}
func (c *testClientTLSConfig) STDConfig() (*stdtls.Config, error) { return c.config, nil }
func (c *testClientTLSConfig) Client(conn net.Conn) (tls.Conn, error) {
	return stdtls.Client(conn, c.config), nil
}

func (c *testClientTLSConfig) Clone() tls.Config {
	return &testClientTLSConfig{config: c.config.Clone()}
}

// testServerTLSConfig wraps *stdtls.Config to satisfy singtls.ServerConfig.
type testServerTLSConfig struct{ config *stdtls.Config }

func (s *testServerTLSConfig) ServerName() string                 { return s.config.ServerName }
func (s *testServerTLSConfig) SetServerName(serverName string)    { s.config.ServerName = serverName }
func (s *testServerTLSConfig) NextProtos() []string               { return s.config.NextProtos }
func (s *testServerTLSConfig) SetNextProtos(p []string)           { s.config.NextProtos = p }
func (s *testServerTLSConfig) HandshakeTimeout() time.Duration    { return 0 }
func (s *testServerTLSConfig) SetHandshakeTimeout(time.Duration)  {}
func (s *testServerTLSConfig) STDConfig() (*stdtls.Config, error) { return s.config, nil }
func (s *testServerTLSConfig) Client(conn net.Conn) (tls.Conn, error) {
	return stdtls.Client(conn, s.config), nil
}

func (s *testServerTLSConfig) Clone() tls.Config {
	return &testServerTLSConfig{config: s.config.Clone()}
}
func (s *testServerTLSConfig) Start() error { return nil }
func (s *testServerTLSConfig) Close() error { return nil }
func (s *testServerTLSConfig) Server(conn net.Conn) (tls.Conn, error) {
	return stdtls.Server(conn, s.config), nil
}

// echoHandler echoes all TCP streams and UDP packets back to the sender.
type echoHandler struct{}

func (h *echoHandler) NewConnectionEx(ctx context.Context, conn net.Conn, _, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	go func() {
		defer onClose(nil)
		defer conn.Close()
		_ = bufio.CopyConn(ctx, conn, conn)
	}()
}

func (h *echoHandler) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, _, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	go func() {
		defer onClose(nil)
		defer conn.Close()
		_ = bufio.CopyPacketConn(ctx, conn, conn)
	}()
}

func generateTestTLSPair(t *testing.T) (serverStd, clientStd *stdtls.Config) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	require.NoError(t, err)

	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "test"},
		DNSNames:     []string{"localhost"},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	certDER, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	require.NoError(t, err)
	cert, err := x509.ParseCertificate(certDER)
	require.NoError(t, err)

	pool := x509.NewCertPool()
	pool.AddCert(cert)

	tlsCert := stdtls.Certificate{Certificate: [][]byte{certDER}, PrivateKey: key}
	serverStd = &stdtls.Config{
		Certificates: []stdtls.Certificate{tlsCert},
		NextProtos:   []string{http2.NextProtoTLS},
	}
	clientStd = &stdtls.Config{
		RootCAs:    pool,
		ServerName: "localhost",
	}
	return
}

type testSetup struct {
	service *Service
	client  *Client
}

func newTestSetup(t *testing.T) *testSetup {
	t.Helper()

	serverStd, clientStd := generateTestTLSPair(t)

	listener, err := net.Listen(N.NetworkTCP, "127.0.0.1:0")
	require.NoError(t, err)

	service := NewService(ServiceOptions{
		Ctx:     context.Background(),
		Logger:  logger.NOP(),
		Handler: &echoHandler{},
	})
	service.UpdateUsers([]auth.User{{Username: "test", Password: "test"}})
	require.NoError(t, service.Start(listener, nil, &testServerTLSConfig{config: serverStd}))

	addr := listener.Addr().String()
	client, err := NewClient(ClientOptions{
		Ctx:       context.Background(),
		Detour:    new(N.DefaultDialer),
		Server:    M.ParseSocksaddr(addr),
		Auth:      auth.User{Username: "test", Password: "test"},
		TLSConfig: &testClientTLSConfig{config: clientStd},
	})
	require.NoError(t, err)
	require.NoError(t, client.Start())

	t.Cleanup(func() {
		client.Close()
		service.Close()
	})

	return &testSetup{service: service, client: client}
}

func TestRoundtripHealthCheck(t *testing.T) {
	t.Parallel()
	s := newTestSetup(t)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	require.NoError(t, s.client.HealthCheck(ctx))
}

func TestRoundtripTCP(t *testing.T) {
	t.Parallel()
	s := newTestSetup(t)

	conn, err := s.client.Dial(context.Background(), M.ParseSocksaddr("example.com:80"))
	require.NoError(t, err)
	defer conn.Close()

	msg := []byte("hello trusttunnel tcp")
	_, err = conn.Write(msg)
	require.NoError(t, err)

	got := make([]byte, len(msg))
	_, err = io.ReadFull(conn, got)
	require.NoError(t, err)
	require.Equal(t, msg, got)
}

func TestRoundtripUDP(t *testing.T) {
	t.Parallel()
	s := newTestSetup(t)

	conn, err := s.client.ListenPacket(context.Background())
	require.NoError(t, err)
	defer conn.Close()

	dest := &net.UDPAddr{IP: net.ParseIP("1.2.3.4"), Port: 53}
	payload := []byte("hello trusttunnel udp")

	_, err = conn.WriteTo(payload, dest)
	require.NoError(t, err)

	got := buf.Get(1500)
	defer buf.Put(got)
	n, src, err := conn.ReadFrom(got)
	require.NoError(t, err)
	require.Equal(t, payload, got[:n])
	require.Equal(t, "1.2.3.4:53", src.String())
}

// TestRoundtripTCPConcurrent opens many TCP streams simultaneously to catch data races.
// Run with -race to enable the race detector.
func TestRoundtripTCPConcurrent(t *testing.T) {
	t.Parallel()
	s := newTestSetup(t)

	const numStreams = 20
	var waitGroup sync.WaitGroup
	waitGroup.Add(numStreams)
	for range numStreams {
		go func() {
			defer waitGroup.Done()
			conn, err := s.client.Dial(context.Background(), M.ParseSocksaddr("example.com:80"))
			if !assert.NoError(t, err) {
				return
			}
			defer conn.Close()

			msg := []byte("concurrent tcp echo")
			if _, err = conn.Write(msg); !assert.NoError(t, err) {
				return
			}
			got := buf.Get(len(msg))
			defer buf.Put(got)
			if _, err = io.ReadFull(conn, got); !assert.NoError(t, err) {
				return
			}
			assert.Equal(t, msg, got)
		}()
	}
	waitGroup.Wait()
}

// TestRoundtripUDPConcurrent opens many UDP packet conns simultaneously to catch data races.
// Run with -race to enable the race detector.
func TestRoundtripUDPConcurrent(t *testing.T) {
	t.Parallel()
	s := newTestSetup(t)

	const numConns = 20
	dest := &net.UDPAddr{IP: net.ParseIP("1.2.3.4"), Port: 53}
	payload := []byte("concurrent udp echo")

	var waitGroup sync.WaitGroup
	waitGroup.Add(numConns)
	for range numConns {
		go func() {
			defer waitGroup.Done()
			pktConn, err := s.client.ListenPacket(context.Background())
			if !assert.NoError(t, err) {
				return
			}
			defer pktConn.Close()

			if _, err = pktConn.WriteTo(payload, dest); !assert.NoError(t, err) {
				return
			}
			got := buf.Get(1500)
			defer buf.Put(got)
			n, _, err := pktConn.ReadFrom(got)
			if !assert.NoError(t, err) {
				return
			}
			assert.Equal(t, payload, got[:n])
		}()
	}
	waitGroup.Wait()
}
