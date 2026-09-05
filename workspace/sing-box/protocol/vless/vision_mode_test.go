package vless

import (
	"context"
	"net"
	"testing"

	"github.com/sagernet/sing-box/protocol/vless/encryption"
)

func TestNeedsEnhancedVision(t *testing.T) {
	tests := []struct {
		name        string
		vision      bool
		encrypted   bool
		transported bool
		expected    bool
	}{
		{"plain VLESS", false, false, false, false},
		{"encrypted VLESS", false, true, false, false},
		{"upstream Vision", true, false, false, false},
		{"encrypted Vision", true, true, false, true},
		{"transported Vision", true, false, true, true},
		{"encrypted transported Vision", true, true, true, true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			actual := needsEnhancedVision(test.vision, test.encrypted, test.transported)
			if actual != test.expected {
				t.Fatalf("needsEnhancedVision() = %v, expected %v", actual, test.expected)
			}
		})
	}
}

func TestCanDirectEnhancedVision(t *testing.T) {
	tests := []struct {
		name                 string
		encrypted            bool
		fullRandomEncryption bool
		transported          bool
		expected             bool
	}{
		{"direct", false, false, false, true},
		{"transport", false, false, true, false},
		{"native encryption", true, false, false, true},
		{"native encryption over transport", true, false, true, true},
		{"full random encryption", true, true, false, false},
		{"full random encryption over transport", true, true, true, false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			actual := canDirectEnhancedVision(test.encrypted, test.fullRandomEncryption, test.transported)
			if actual != test.expected {
				t.Fatalf("canDirectEnhancedVision() = %v, expected %v", actual, test.expected)
			}
		})
	}
}

func TestPrepareEnhancedVisionConnPrefersEncryptionOverOuterTLS(t *testing.T) {
	transportConn, transportPeer := net.Pipe()
	defer transportConn.Close()
	defer transportPeer.Close()
	outerTLSConn, outerTLSPeer := net.Pipe()
	defer outerTLSConn.Close()
	defer outerTLSPeer.Close()

	encryptionConn := encryption.NewCommonConn(transportConn, true)
	dialer := (*vlessDialer)(&Outbound{
		encryption: &encryption.ClientInstance{},
		transport:  visionTestTransport{},
	})
	conn, baseConn, canDirect, err := dialer.prepareEnhancedVisionConn(encryptionConn, outerTLSConn)
	if err != nil {
		t.Fatal(err)
	}
	if conn != encryptionConn {
		t.Fatalf("prepared connection = %T, expected encryption connection", conn)
	}
	if baseConn != encryptionConn {
		t.Fatalf("base connection = %T, expected encryption connection", baseConn)
	}
	if !canDirect {
		t.Fatal("native encryption over a transport must allow Vision direct mode")
	}
}

type visionTestTransport struct{}

func (visionTestTransport) DialContext(context.Context) (net.Conn, error) {
	panic("unexpected call")
}

func (visionTestTransport) Close() error {
	return nil
}
