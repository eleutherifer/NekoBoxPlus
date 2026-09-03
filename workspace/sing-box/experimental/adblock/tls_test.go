//go:build with_adblock

package adblock

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"math/big"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func TestSharedLeafKeyGeneratedOnceAtStartup(t *testing.T) {
	tlsCA := newTestTLSCA(t)
	key := tlsCA.leafKeyECDSA
	for range 100 {
		certificate, err := tlsCA.generateCertificate("example.com", nil, key)
		if err != nil {
			t.Fatal(err)
		}
		if certificate.PrivateKey != key {
			t.Fatal("expected generated certificate to reuse shared private key")
		}
	}
}

func TestClientPrefersECDSAWhenAdvertised(t *testing.T) {
	tlsCA := newTestTLSCA(t)
	certificate, err := tlsCA.certificateForServerName(t.Context(), nil, adapter.InboundContext{
		Domain:      "example.com",
		Destination: M.ParseSocksaddrHostPort("example.com", 443),
	}, &tls.ClientHelloInfo{
		ServerName:       "example.com",
		SupportedCurves:  []tls.CurveID{tls.CurveP256},
		SignatureSchemes: []tls.SignatureScheme{tls.ECDSAWithP256AndSHA256},
	})
	if err != nil {
		t.Fatal(err)
	}
	leaf := parseLeaf(t, certificate)
	if _, ok := leaf.PublicKey.(*ecdsa.PublicKey); !ok {
		t.Fatalf("expected ECDSA public key, got %T", leaf.PublicKey)
	}
}

func TestClientFallsBackToRSAWhenECDSAUnsupported(t *testing.T) {
	tlsCA := newTestTLSCA(t)
	certificate, err := tlsCA.certificateForServerName(t.Context(), nil, adapter.InboundContext{
		Domain:      "example.com",
		Destination: M.ParseSocksaddrHostPort("example.com", 443),
	}, &tls.ClientHelloInfo{
		ServerName:       "example.com",
		SupportedCurves:  []tls.CurveID{tls.CurveP384},
		SignatureSchemes: []tls.SignatureScheme{tls.PKCS1WithSHA256},
	})
	if err != nil {
		t.Fatal(err)
	}
	leaf := parseLeaf(t, certificate)
	if _, ok := leaf.PublicKey.(*rsa.PublicKey); !ok {
		t.Fatalf("expected RSA public key, got %T", leaf.PublicKey)
	}
}

func TestPeerCertCacheAvoidsRepeatedDials(t *testing.T) {
	tlsCA := newTestTLSCA(t)
	outbound := newTLSCountingOutbound(t)
	metadata := adapter.InboundContext{
		Domain:      "example.com",
		Destination: M.ParseSocksaddrHostPort("example.com", 443),
	}
	hello := &tls.ClientHelloInfo{
		ServerName:       "example.com",
		SupportedProtos:  []string{"http/1.1"},
		SupportedCurves:  []tls.CurveID{tls.CurveP256},
		SignatureSchemes: []tls.SignatureScheme{tls.ECDSAWithP256AndSHA256},
	}
	if _, err := tlsCA.certificateForServerName(t.Context(), outbound, metadata, hello); err != nil {
		t.Fatal(err)
	}
	if cert := tlsCA.peerCertificate(t.Context(), outbound, metadata.Destination, metadata.Domain, []string{"h2", "http/1.1"}); cert == nil {
		t.Fatal("expected cached peer certificate")
	}
	if got := outbound.dials.Load(); got != 1 {
		t.Fatalf("expected one upstream TLS dial, got %d", got)
	}
}

func TestPeerCertNegativeCacheShortTTL(t *testing.T) {
	tlsCA := newTestTLSCA(t)
	now := time.Now()
	tlsCA.peerCertNow = func() time.Time { return now }
	outbound := &failingOutbound{}
	destination := M.ParseSocksaddrHostPort("example.com", 443)

	if cert := tlsCA.peerCertificate(t.Context(), outbound, destination, "example.com", []string{"http/1.1"}); cert != nil {
		t.Fatal("expected nil certificate")
	}
	if cert := tlsCA.peerCertificate(t.Context(), outbound, destination, "example.com", []string{"http/1.1"}); cert != nil {
		t.Fatal("expected nil certificate")
	}
	if got := outbound.dials.Load(); got != 1 {
		t.Fatalf("expected one dial inside negative TTL, got %d", got)
	}
	now = now.Add(peerCertificateErrorTTL + time.Second)
	if cert := tlsCA.peerCertificate(t.Context(), outbound, destination, "example.com", []string{"http/1.1"}); cert != nil {
		t.Fatal("expected nil certificate")
	}
	if got := outbound.dials.Load(); got != 2 {
		t.Fatalf("expected second dial after negative TTL, got %d", got)
	}
}

func TestPeerCertSingleFlightConcurrent(t *testing.T) {
	tlsCA := newTestTLSCA(t)
	outbound := newTLSCountingOutbound(t)
	destination := M.ParseSocksaddrHostPort("example.com", 443)

	var wg sync.WaitGroup
	for range 50 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if cert := tlsCA.peerCertificate(t.Context(), outbound, destination, "example.com", []string{"http/1.1"}); cert == nil {
				t.Error("expected peer certificate")
			}
		}()
	}
	wg.Wait()
	if got := outbound.dials.Load(); got != 1 {
		t.Fatalf("expected one upstream TLS dial, got %d", got)
	}
}

func TestIssuedCertificateValidatesAgainstCA(t *testing.T) {
	tlsCA := newTestTLSCA(t)
	certificate, err := tlsCA.generateCertificate("example.com", nil, tlsCA.leafKeyECDSA)
	if err != nil {
		t.Fatal(err)
	}
	leaf := parseLeaf(t, certificate)
	pool := x509.NewCertPool()
	pool.AddCert(tlsCA.certificate)
	if _, err = leaf.Verify(x509.VerifyOptions{
		DNSName: "example.com",
		Roots:   pool,
	}); err != nil {
		t.Fatal(err)
	}
}

func BenchmarkGenerateCertificateSharedKey(b *testing.B) {
	tlsCA := newTestTLSCA(b)
	b.ReportAllocs()
	for b.Loop() {
		if _, err := tlsCA.generateCertificate("example.com", nil, tlsCA.leafKeyECDSA); err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkGenerateCertificateLegacy(b *testing.B) {
	tlsCA := newTestTLSCA(b)
	b.ReportAllocs()
	for b.Loop() {
		privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
		if err != nil {
			b.Fatal(err)
		}
		if _, err = tlsCA.generateCertificate("example.com", nil, privateKey); err != nil {
			b.Fatal(err)
		}
	}
}

type testOutboundBase struct{}

func (testOutboundBase) Type() string           { return "test" }
func (testOutboundBase) Tag() string            { return "test" }
func (testOutboundBase) Network() []string      { return []string{N.NetworkTCP} }
func (testOutboundBase) Dependencies() []string { return nil }
func (testOutboundBase) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not implemented")
}

type tlsCountingOutbound struct {
	testOutboundBase
	tlsConfig *tls.Config
	dials     atomic.Int64
}

func newTLSCountingOutbound(t testing.TB) *tlsCountingOutbound {
	t.Helper()
	certificate := newServerCertificate(t)
	return &tlsCountingOutbound{
		tlsConfig: &tls.Config{
			Certificates: []tls.Certificate{certificate},
		},
	}
}

func (o *tlsCountingOutbound) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	o.dials.Add(1)
	clientConn, serverConn := net.Pipe()
	go func() {
		tlsConn := tls.Server(serverConn, o.tlsConfig)
		_ = tlsConn.Handshake()
		_ = tlsConn.Close()
	}()
	return clientConn, nil
}

type failingOutbound struct {
	testOutboundBase
	dials atomic.Int64
}

func (o *failingOutbound) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	o.dials.Add(1)
	return nil, errors.New("dial failed")
}

func newTestTLSCA(t testing.TB) *tlsCertificateAuthority {
	t.Helper()
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "test ca"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, template, template, caKey.Public(), caKey)
	if err != nil {
		t.Fatal(err)
	}
	caCertificate, err := x509.ParseCertificate(caDER)
	if err != nil {
		t.Fatal(err)
	}
	leafKeyECDSA, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	leafKeyRSA, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	return &tlsCertificateAuthority{
		certificate:      caCertificate,
		privateKey:       caKey,
		leafKeyECDSA:     leafKeyECDSA,
		leafKeyRSA:       leafKeyRSA,
		certificates:     MustNewLRU[string, *tls.Certificate](defaultCheckCacheSize, newStringHasherFunc()),
		peerCertificates: MustNewLRU[string, peerCertEntry](peerCertificateCacheSize, newStringHasherFunc()),
		peerCertNow:      time.Now,
	}
}

func newServerCertificate(t testing.TB) tls.Certificate {
	t.Helper()
	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(2),
		Subject:               pkix.Name{CommonName: "example.com"},
		DNSNames:              []string{"example.com"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}
	certificateDER, err := x509.CreateCertificate(rand.Reader, template, template, privateKey.Public(), privateKey)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{
		Certificate: [][]byte{certificateDER},
		PrivateKey:  privateKey,
	}
}

func parseLeaf(t testing.TB, certificate *tls.Certificate) *x509.Certificate {
	t.Helper()
	leaf, err := x509.ParseCertificate(certificate.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	return leaf
}

var _ adapter.Outbound = (*tlsCountingOutbound)(nil)
var _ adapter.Outbound = (*failingOutbound)(nil)
var _ crypto.Signer = (*ecdsa.PrivateKey)(nil)
