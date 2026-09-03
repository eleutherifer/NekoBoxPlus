//go:build with_quic

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
	"testing"
	"time"

	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/stretchr/testify/require"
)

func generateQuicTestTLSPair(t *testing.T) (serverStd, clientStd *stdtls.Config) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	require.NoError(t, err)

	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "localhost"},
		DNSNames:     []string{"localhost"},
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
	}
	clientStd = &stdtls.Config{
		RootCAs: pool,
	}
	return
}

func newQuicTestSetup(t *testing.T) *testSetup {
	t.Helper()

	serverStd, clientStd := generateQuicTestTLSPair(t)

	udpConn, err := net.ListenPacket(N.NetworkUDP, "127.0.0.1:0")
	require.NoError(t, err)

	service := NewService(ServiceOptions{
		Ctx:     t.Context(),
		Logger:  logger.NOP(),
		Handler: &echoHandler{},
	})
	service.UpdateUsers([]auth.User{{Username: "test", Password: "test"}})
	require.NoError(t, service.Start(nil, udpConn, &testServerTLSConfig{config: serverStd}))

	client, err := NewClient(ClientOptions{
		Ctx:           t.Context(),
		Detour:        new(N.DefaultDialer),
		Server:        M.ParseSocksaddr(udpConn.LocalAddr().String()),
		Auth:          auth.User{Username: "test", Password: "test"},
		TLSConfig:     &testClientTLSConfig{config: clientStd},
		QUIC:          true,
		ForceQUIC:     true,
		TLSServerName: "localhost",
	})
	require.NoError(t, err)
	require.NoError(t, client.Start())

	t.Cleanup(func() {
		client.Close()
		service.Close()
	})

	return &testSetup{service: service, client: client}
}

func TestRoundtripQUICUsesTLSServerNameOrigin(t *testing.T) {
	t.Parallel()
	s := newQuicTestSetup(t)

	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	require.NoError(t, s.client.HealthCheck(ctx))
}

func TestRoundtripQUICTCP(t *testing.T) {
	t.Parallel()
	s := newQuicTestSetup(t)

	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	conn, err := s.client.Dial(ctx, M.ParseSocksaddr("example.com:80"))
	require.NoError(t, err)
	defer conn.Close()

	msg := []byte("hello trusttunnel quic tcp")
	_, err = conn.Write(msg)
	require.NoError(t, err)

	got := make([]byte, len(msg))
	_, err = io.ReadFull(conn, got)
	require.NoError(t, err)
	require.Equal(t, msg, got)
}

func TestRoundtripQUICUDP(t *testing.T) {
	t.Parallel()
	s := newQuicTestSetup(t)

	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	conn, err := s.client.ListenPacket(ctx)
	require.NoError(t, err)
	defer conn.Close()

	dest := &net.UDPAddr{IP: net.ParseIP("1.2.3.4"), Port: 53}
	payload := []byte("hello trusttunnel quic udp")

	_, err = conn.WriteTo(payload, dest)
	require.NoError(t, err)

	got := buf.Get(1500)
	defer buf.Put(got)
	n, src, err := conn.ReadFrom(got)
	require.NoError(t, err)
	require.Equal(t, payload, got[:n])
	require.Equal(t, "1.2.3.4:53", src.String())
}
