//go:build with_utls

package tls

import (
	"crypto/ecdh"
	"crypto/rand"
	"testing"

	utls "github.com/metacubex/utls"
)

func TestRealityClientHelloPreservesX25519MLKEM768(t *testing.T) {
	config := &utls.Config{
		ServerName:         "example.com",
		InsecureSkipVerify: true,
	}
	uConn := utls.UClient(nil, config, utls.HelloChrome_Auto)
	if err := uConn.BuildHandshakeState(); err != nil {
		t.Fatal(err)
	}

	var hasHybrid bool
	var hasClassical bool
	for _, keyShare := range uConn.HandshakeState.Hello.KeyShares {
		switch keyShare.Group {
		case utls.X25519MLKEM768:
			hasHybrid = true
		case utls.X25519:
			hasClassical = true
		}
	}
	if !hasHybrid {
		t.Fatal("Chrome fingerprint does not contain an X25519MLKEM768 key share")
	}
	if !hasClassical {
		t.Fatal("Chrome fingerprint does not contain an X25519 key share")
	}

	keyShareKeys := uConn.HandshakeState.State13.KeyShareKeys
	if keyShareKeys == nil || keyShareKeys.MlkemEcdhe == nil || keyShareKeys.Ecdhe == nil {
		t.Fatal("Chrome fingerprint does not provide both hybrid and classical X25519 private keys")
	}
}

func TestRealityAuthKeySelection(t *testing.T) {
	classicalKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	p256Key, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	hybridKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}

	testCases := []struct {
		name     string
		keys     *utls.KeySharePrivateKeys
		expected *ecdh.PrivateKey
	}{
		{
			name: "prefer standalone X25519",
			keys: &utls.KeySharePrivateKeys{
				Ecdhe:      classicalKey,
				MlkemEcdhe: hybridKey,
			},
			expected: classicalKey,
		},
		{
			name: "fall back to hybrid X25519",
			keys: &utls.KeySharePrivateKeys{
				MlkemEcdhe: hybridKey,
			},
			expected: hybridKey,
		},
		{
			name: "ignore incompatible standalone curve",
			keys: &utls.KeySharePrivateKeys{
				Ecdhe:      p256Key,
				MlkemEcdhe: hybridKey,
			},
			expected: hybridKey,
		},
		{
			name: "reject incompatible standalone curve",
			keys: &utls.KeySharePrivateKeys{
				Ecdhe: p256Key,
			},
		},
		{
			name: "no compatible key",
			keys: &utls.KeySharePrivateKeys{},
		},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			actual := realityAuthPrivateKey(testCase.keys)
			if actual != testCase.expected {
				t.Fatalf("unexpected key selected: got %p, expected %p", actual, testCase.expected)
			}
		})
	}
}
