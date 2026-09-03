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
	"encoding/asn1"
	"math/big"
	"net"
	"net/netip"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/ntp"
	"github.com/sagernet/sing/contrab/freelru"
	"golang.org/x/sync/singleflight"
)

const (
	peerCertificateCacheSize = 4096
	peerCertificateTTL       = time.Hour
	peerCertificateErrorTTL  = 5 * time.Minute
)

type tlsCertificateAuthority struct {
	certificate *x509.Certificate
	privateKey  crypto.Signer
	logger      adblockDebugLogger
	// Leaf keys are shared across forged certificates to avoid per-domain keygen.
	// The adblock CA key remains the single trust boundary for this MITM flow.
	leafKeyECDSA     crypto.Signer
	leafKeyRSA       crypto.Signer
	certificates     *freelru.Cache[string, *tls.Certificate]
	peerCertificates *freelru.Cache[string, peerCertEntry]
	peerCertGroup    singleflight.Group
	peerCertNow      func() time.Time
}

func (c *tlsCertificateAuthority) debug(args ...any) {
	if c.isDebug() {
		c.logger.Debug(adblockDebugMessage(args...))
	}
}

func (c *tlsCertificateAuthority) isDebug() bool {
	return c != nil && c.logger != nil
}

type peerCertEntry struct {
	cert      *x509.Certificate
	fetchedAt time.Time
}

func newTLSCertificateAuthority(options *option.AdblockTLSOptions, logger adblockDebugLogger) (*tlsCertificateAuthority, error) {
	if logger != nil {
		logger.Debug(adblockDebugMessage("initializing adblock TLS certificate authority"))
	}
	certificate, err := os.ReadFile(options.Certificate)
	if err != nil {
		return nil, E.Cause(err, "read adblock TLS certificate")
	}
	key, err := os.ReadFile(options.Key)
	if err != nil {
		return nil, E.Cause(err, "read adblock TLS key")
	}
	keyPair, err := tls.X509KeyPair(certificate, key)
	if err != nil {
		return nil, E.Cause(err, "parse adblock TLS key pair")
	}
	if len(keyPair.Certificate) == 0 {
		return nil, E.New("parse adblock TLS certificate: empty certificate chain")
	}
	parsedCertificate, err := x509.ParseCertificate(keyPair.Certificate[0])
	if err != nil {
		return nil, E.Cause(err, "parse adblock TLS certificate")
	}
	privateKey, isSigner := keyPair.PrivateKey.(crypto.Signer)
	if !isSigner {
		return nil, E.New("parse adblock TLS key: unsupported private key type")
	}
	if !parsedCertificate.IsCA {
		return nil, E.New("adblock TLS certificate must be a CA certificate")
	}
	leafKeyECDSA, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, E.Cause(err, "generate adblock TLS ECDSA leaf key")
	}
	leafKeyRSA, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, E.Cause(err, "generate adblock TLS RSA leaf key")
	}
	tlsCA := &tlsCertificateAuthority{
		certificate:      parsedCertificate,
		privateKey:       privateKey,
		logger:           logger,
		leafKeyECDSA:     leafKeyECDSA,
		leafKeyRSA:       leafKeyRSA,
		certificates:     MustNewLRU[string, *tls.Certificate](defaultCheckCacheSize, newStringHasherFunc()),
		peerCertificates: MustNewLRU[string, peerCertEntry](peerCertificateCacheSize, newStringHasherFunc()),
		peerCertNow:      time.Now,
	}
	if _, err = tlsCA.generateCertificate("adblock.invalid", nil, leafKeyECDSA); err != nil {
		return nil, E.Cause(err, "verify adblock TLS leaf certificate signing via ECDSA")
	}
	if _, err = tlsCA.generateCertificate("adblock.invalid", nil, leafKeyRSA); err != nil {
		return nil, E.Cause(err, "verify adblock TLS leaf certificate signing via RSA")
	}
	tlsCA.debug("adblock TLS certificate authority ready, leaf keys: ecdsa,rsa")
	return tlsCA, nil
}

func fetchPeerCertificate(ctx context.Context, outbound adapter.Outbound, destination M.Socksaddr, serverName string, nextProtos []string) *x509.Certificate {
	if outbound == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if !destination.IsValid() && serverName != "" {
		destination = M.ParseSocksaddrHostPort(serverName, 443)
	}
	conn, err := outbound.DialContext(ctx, N.NetworkTCP, destination)
	if err != nil {
		return nil
	}
	defer conn.Close()
	tlsConn := tls.Client(conn, &tls.Config{
		ServerName:         serverName,
		InsecureSkipVerify: true,
		NextProtos:         nextProtos,
		Time:               ntp.TimeFuncFromContext(ctx),
	})
	if err = tlsConn.HandshakeContext(ctx); err != nil {
		return nil
	}
	connectionState := tlsConn.ConnectionState()
	if len(connectionState.PeerCertificates) == 0 {
		return nil
	}
	return connectionState.PeerCertificates[0]
}

func (c *tlsCertificateAuthority) peerCertificate(ctx context.Context, outbound adapter.Outbound, destination M.Socksaddr, serverName string, nextProtos []string) *x509.Certificate {
	if outbound == nil {
		c.debug("peer certificate skipped: outbound unavailable, server_name=", serverName)
		return nil
	}
	cacheKey := peerCertificateCacheKey(destination, serverName)
	if entry, ok := c.peerCertificates.Get(cacheKey); ok {
		ttl := peerCertificateTTL
		if entry.cert == nil {
			ttl = peerCertificateErrorTTL
		}
		if c.peerCertNow().Sub(entry.fetchedAt) < ttl {
			c.debug("peer certificate cache hit: server_name=", serverName, ", cached=", entry.cert != nil)
			return entry.cert
		}
		c.debug("peer certificate cache expired: server_name=", serverName)
		c.peerCertificates.Remove(cacheKey)
	}
	var start time.Time
	if c.isDebug() {
		start = time.Now()
	}
	c.debug("peer certificate fetch started: server_name=", serverName, ", destination=", destination)
	result, _, _ := c.peerCertGroup.Do(cacheKey, func() (any, error) {
		cert := fetchPeerCertificate(ctx, outbound, destination, serverName, nextProtos)
		c.peerCertificates.Add(cacheKey, peerCertEntry{
			cert:      cert,
			fetchedAt: c.peerCertNow(),
		})
		return cert, nil
	})
	var duration time.Duration
	if c.isDebug() {
		duration = time.Now().Sub(start)
		c.debug("peer certificate fetch for server_name=", serverName, "finished in ", duration)
	}
	if result == nil {
		c.debug("peer certificate unavailable: server_name=", serverName)
		return nil
	}
	c.debug("peer certificate fetched: server_name=", serverName)
	return result.(*x509.Certificate)
}

func peerCertificateCacheKey(destination M.Socksaddr, serverName string) string {
	return strings.ToLower(destination.String()) + "|" + strings.ToLower(serverName)
}

var evPolicyOID = asn1.ObjectIdentifier{2, 23, 140, 1, 1}

func certificateHasEVPolicy(certificate *x509.Certificate) bool {
	if certificate == nil {
		return false
	}

	for _, policy := range certificate.PolicyIdentifiers {
		if policy.Equal(evPolicyOID) {
			return true
		}
	}

	for _, policy := range certificate.Policies {
		if policy.EqualASN1OID(evPolicyOID) {
			return true
		}
	}

	return false
}

func copyPeerCertificateFields(template *x509.Certificate, peerCertificate *x509.Certificate, now time.Time) {
	template.Subject = peerCertificate.Subject
	template.DNSNames = append([]string(nil), peerCertificate.DNSNames...)
	template.EmailAddresses = append([]string(nil), peerCertificate.EmailAddresses...)
	template.URIs = append([]*url.URL(nil), peerCertificate.URIs...)
	for _, ipAddress := range peerCertificate.IPAddresses {
		template.IPAddresses = append(template.IPAddresses, append(net.IP(nil), ipAddress...))
	}
	if !peerCertificate.NotBefore.IsZero() && peerCertificate.NotBefore.Before(now) {
		template.NotBefore = peerCertificate.NotBefore
	}
	if !peerCertificate.NotAfter.IsZero() && peerCertificate.NotAfter.After(now) {
		template.NotAfter = peerCertificate.NotAfter
	}
	if peerCertificate.KeyUsage != 0 {
		template.KeyUsage = peerCertificate.KeyUsage & (x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment | x509.KeyUsageKeyAgreement)
		if template.KeyUsage == 0 {
			template.KeyUsage = x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment
		}
	}
	if len(peerCertificate.ExtKeyUsage) > 0 {
		template.ExtKeyUsage = append([]x509.ExtKeyUsage(nil), peerCertificate.ExtKeyUsage...)
	}
}

func ensureCertificateName(template *x509.Certificate, serverName string) {
	if len(template.DNSNames) > 0 || len(template.IPAddresses) > 0 || serverName == "" {
		return
	}
	if ipAddress, err := netip.ParseAddr(serverName); err == nil {
		template.IPAddresses = []net.IP{ipAddress.AsSlice()}
	} else {
		template.DNSNames = []string{serverName}
	}
}

func randomSerialNumber() (*big.Int, error) {
	serialNumberLimit := new(big.Int).Lsh(big.NewInt(1), 128)
	return rand.Int(rand.Reader, serialNumberLimit)
}

func (c *tlsCertificateAuthority) certificateForServerName(ctx context.Context, outbound adapter.Outbound, metadata adapter.InboundContext, hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
	serverName := strings.TrimSuffix(hello.ServerName, ".")
	if serverName == "" {
		serverName = metadata.Domain
	}
	if serverName == "" {
		serverName = metadata.Destination.AddrString()
	}
	leafKeyType := "ecdsa"
	leafKey := c.leafKeyECDSA
	if !clientSupportsECDSA(hello) {
		leafKeyType = "rsa"
		leafKey = c.leafKeyRSA
		c.debug("TLS using RSA leaf key: client does not support ECDSA, server_name=", serverName)
	} else {
		c.debug("TLS using ECDSA leaf key: server_name=", serverName)
	}
	if leafKey == nil {
		return nil, E.New("adblock TLS leaf key is unavailable")
	}
	cacheKey := strings.ToLower(serverName) + "|" + leafKeyType
	if cachedCertificate := c.loadCertificate(cacheKey); cachedCertificate != nil {
		c.debug("TLS certificate cache hit: server_name=", serverName, ", key=", leafKeyType)
		return cachedCertificate, nil
	}

	c.debug("TLS certificate cache miss: server_name=", serverName, ", key=", leafKeyType)
	c.debug("TLS generating certificate without peer fields: server_name=", serverName, ", key=", leafKeyType)
	certificate, err := c.generateCertificate(serverName, nil, leafKey)
	if err != nil {
		return nil, err
	}
	c.storeCertificate(cacheKey, certificate)
	c.debug("TLS certificate stored: server_name=", serverName, ", key=", leafKeyType)
	return certificate, nil
}

func (c *tlsCertificateAuthority) loadCertificate(cacheKey string) *tls.Certificate {
	certificate, ok := c.certificates.Get(cacheKey)
	if !ok || certificate == nil || len(certificate.Certificate) == 0 {
		return nil
	}
	leaf, err := x509.ParseCertificate(certificate.Certificate[0])
	if err != nil || time.Until(leaf.NotAfter) < time.Hour {
		c.debug("TLS certificate cache entry expired or invalid: ", cacheKey)
		c.certificates.Remove(cacheKey)
		return nil
	}
	return certificate
}

func (c *tlsCertificateAuthority) storeCertificate(cacheKey string, certificate *tls.Certificate) {
	c.certificates.Add(cacheKey, certificate)
}

func (c *tlsCertificateAuthority) generateCertificate(serverName string, peerCertificate *x509.Certificate, privateKey crypto.Signer) (*tls.Certificate, error) {
	serialNumber, err := randomSerialNumber()
	if err != nil {
		return nil, err
	}
	now := time.Now()
	template := &x509.Certificate{
		SerialNumber:          serialNumber,
		Subject:               c.certificate.Subject,
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}
	if peerCertificate != nil {
		copyPeerCertificateFields(template, peerCertificate, now)
	}
	ensureCertificateName(template, serverName)
	if template.NotAfter.After(c.certificate.NotAfter) {
		template.NotAfter = c.certificate.NotAfter
	}
	certificateDER, err := x509.CreateCertificate(rand.Reader, template, c.certificate, privateKey.Public(), c.privateKey)
	if err != nil {
		return nil, err
	}
	return &tls.Certificate{
		Certificate: [][]byte{certificateDER, c.certificate.Raw},
		PrivateKey:  privateKey,
		Leaf:        template,
	}, nil
}

func clientSupportsECDSA(hello *tls.ClientHelloInfo) bool {
	if hello == nil {
		return false
	}
	hasP256 := len(hello.SupportedCurves) == 0
	for _, curve := range hello.SupportedCurves {
		if curve == tls.CurveP256 {
			hasP256 = true
			break
		}
	}
	if !hasP256 {
		return false
	}
	if len(hello.SignatureSchemes) == 0 {
		return true
	}
	for _, scheme := range hello.SignatureSchemes {
		switch scheme {
		case tls.ECDSAWithP256AndSHA256, tls.ECDSAWithP384AndSHA384, tls.ECDSAWithP521AndSHA512:
			return true
		}
	}
	return false
}
