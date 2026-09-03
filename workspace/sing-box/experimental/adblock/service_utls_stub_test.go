//go:build with_adblock && !with_utls

package adblock

import (
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/pem"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/option"
)

func TestNewRejectsUTLSWithoutBuildTag(t *testing.T) {
	tlsCA := newTestTLSCA(t)
	certificatePath := filepath.Join(t.TempDir(), "ca.pem")
	keyPath := filepath.Join(t.TempDir(), "ca.key")
	if err := os.WriteFile(certificatePath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: tlsCA.certificate.Raw}), 0o600); err != nil {
		t.Fatal(err)
	}
	key, ok := tlsCA.privateKey.(*ecdsa.PrivateKey)
	if !ok {
		t.Fatalf("unexpected test CA key type: %T", tlsCA.privateKey)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600); err != nil {
		t.Fatal(err)
	}

	fingerprint := "chrome"
	_, err = New(t.Context(), nil, option.AdblockOptions{
		Enabled:      true,
		DatabasePath: filepath.Join(t.TempDir(), "adblock.db"),
		TLS: &option.AdblockTLSOptions{
			Enabled:     true,
			Certificate: certificatePath,
			Key:         keyPath,
			UTLS:        &fingerprint,
		},
		Filters: &option.AdblockFilters{
			Rules: []string{"||example.com^"},
		},
	})
	if err == nil {
		t.Fatal("expected uTLS build-tag error")
	}
	if !strings.Contains(err.Error(), "with_utls") {
		t.Fatalf("unexpected error: %v", err)
	}
}
