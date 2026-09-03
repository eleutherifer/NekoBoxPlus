package tls

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestVerifyXrayCertificateSHA256(t *testing.T) {
	now := time.Now()
	rootKey, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	rootTemplate := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "Test Root"},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign,
	}
	rootDER, err := x509.CreateCertificate(rand.Reader, rootTemplate, rootTemplate, &rootKey.PublicKey, rootKey)
	require.NoError(t, err)
	rootCertificate, err := x509.ParseCertificate(rootDER)
	require.NoError(t, err)

	leafKey, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	leafTemplate := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: "example.com"},
		DNSNames:     []string{"example.com"},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	leafDER, err := x509.CreateCertificate(rand.Reader, leafTemplate, rootCertificate, &leafKey.PublicKey, rootKey)
	require.NoError(t, err)

	leafHash := sha256.Sum256(leafDER)
	rootHash := sha256.Sum256(rootDER)
	wrongHash := sha256.Sum256([]byte("wrong"))
	rawCertificates := [][]byte{leafDER, rootDER}
	timeFunc := func() time.Time { return now }

	require.NoError(t, verifyXrayCertificateSHA256([][]byte{leafHash[:]}, rawCertificates, "wrong.example", timeFunc))
	require.NoError(t, verifyXrayCertificateSHA256([][]byte{rootHash[:]}, rawCertificates, "example.com", timeFunc))
	require.Error(t, verifyXrayCertificateSHA256([][]byte{rootHash[:]}, rawCertificates, "wrong.example", timeFunc))
	require.Error(t, verifyXrayCertificateSHA256([][]byte{wrongHash[:]}, rawCertificates, "example.com", timeFunc))
	require.Error(t, verifyXrayCertificateSHA256([][]byte{leafHash[:]}, nil, "example.com", timeFunc))
}
