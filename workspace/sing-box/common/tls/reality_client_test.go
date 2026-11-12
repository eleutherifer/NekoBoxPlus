//go:build with_utls

package tls

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha512"
	"crypto/x509"
	"crypto/x509/pkix"
	"github.com/cloudflare/circl/sign/mldsa/mldsa65"
	"math/big"
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

func TestRealityHybridAdaptation(t *testing.T) {
	conn := utls.UClient(nil, &utls.Config{ServerName: "example.com"}, utls.HelloChrome_100)
	if err := conn.BuildHandshakeState(); err != nil {
		t.Fatal(err)
	}
	if err := ensureRealityHybrid(conn); err != nil {
		t.Fatal(err)
	}
	if err := conn.BuildHandshakeState(); err != nil {
		t.Fatal(err)
	}
	shares := conn.HandshakeState.Hello.KeyShares
	if len(shares) < 2 || shares[0].Group != utls.X25519MLKEM768 {
		t.Fatal("hybrid share must precede X25519")
	}
	keys := conn.HandshakeState.State13.KeyShareKeys
	expected := append(keys.Mlkem.EncapsulationKey().Bytes(), keys.MlkemEcdhe.PublicKey().Bytes()...)
	if !bytes.Equal(shares[0].Data, expected) {
		t.Fatal("hybrid public/private keys differ")
	}
	before := append([]byte(nil), shares[0].Data...)
	if err := ensureRealityHybrid(conn); err != nil {
		t.Fatal(err)
	}
	if err := conn.BuildHandshakeState(); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(before, conn.HandshakeState.Hello.KeyShares[0].Data) {
		t.Fatal("adaptation is not idempotent")
	}
}

func TestRealityMLDSA65Verification(t *testing.T) {
	pub, priv, err := mldsa65.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	edPub, edPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	auth := []byte("01234567890123456789012345678901")
	hello, serverHello := []byte("client hello"), []byte("server hello")
	h := hmac.New(sha512.New, auth)
	h.Write(edPub)
	mac := h.Sum(nil)
	h.Write(hello)
	h.Write(serverHello)
	sig := make([]byte, mldsa65.SignatureSize)
	if err := mldsa65.SignTo(priv, h.Sum(nil), nil, false, sig); err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		name                                string
		enabled, extension, tamper, success bool
	}{
		{"valid", true, true, false, true}, {"changed transcript", true, true, true, false},
		{"missing signature", true, false, false, false}, {"disabled", false, false, false, true},
	} {
		t.Run(test.name, func(t *testing.T) {
			template := &x509.Certificate{SerialNumber: big.NewInt(1)}
			if test.extension {
				template.ExtraExtensions = []pkix.Extension{{Id: []int{0, 0}, Value: sig}}
			}
			der, err := x509.CreateCertificate(rand.Reader, template, template, edPub, edPriv)
			if err != nil {
				t.Fatal(err)
			}
			copy(der[len(der)-64:], mac)
			conn := utls.UClient(nil, &utls.Config{ServerName: "example.com"}, utls.HelloChrome_Auto)
			if err := conn.BuildHandshakeState(); err != nil {
				t.Fatal(err)
			}
			conn.HandshakeState.Hello.Raw = hello
			conn.HandshakeState.ServerHello = &utls.PubServerHelloMsg{Raw: serverHello}
			if test.tamper {
				conn.HandshakeState.Hello.Raw = []byte("changed")
			}
			verifier := &realityVerifier{UConn: conn, authKey: auth}
			if test.enabled {
				verifier.mldsa65Verify = pub
			}
			err = verifier.VerifyPeerCertificate([][]byte{der}, nil)
			if test.success {
				if err != nil || !verifier.verified {
					t.Fatalf("verification failed: %v", err)
				}
			} else if err == nil || verifier.verified {
				t.Fatal("invalid authentication accepted")
			}
		})
	}
}
