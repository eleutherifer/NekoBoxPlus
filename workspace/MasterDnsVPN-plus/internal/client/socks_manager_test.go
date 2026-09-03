package client

import (
	"bytes"
	"io"
	"net"
	"testing"
	"time"

	"masterdnsvpn-go/internal/config"
	VpnProto "masterdnsvpn-go/internal/vpnproto"
)

func TestSupportsSOCKS4Policy(t *testing.T) {
	tests := []struct {
		name string
		cfg  config.ClientConfig
		want bool
	}{
		{
			name: "auth disabled supports socks4",
			cfg:  config.ClientConfig{SOCKS5Auth: false},
			want: true,
		},
		{
			name: "auth enabled with username and password disables socks4",
			cfg:  config.ClientConfig{SOCKS5Auth: true, SOCKS5User: "user", SOCKS5Pass: "pass"},
			want: false,
		},
		{
			name: "auth enabled with username only supports socks4",
			cfg:  config.ClientConfig{SOCKS5Auth: true, SOCKS5User: "user"},
			want: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c := &Client{cfg: tt.cfg}
			if got := c.supportsSOCKS4(); got != tt.want {
				t.Fatalf("supportsSOCKS4() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestSendSocks4ReplyFormatsResponse(t *testing.T) {
	c := &Client{}
	server, clientConn := net.Pipe()
	defer server.Close()
	defer clientConn.Close()

	done := make(chan error, 1)
	go func() {
		done <- c.sendSocks4Reply(server, true)
	}()

	reply := make([]byte, 8)
	if _, err := io.ReadFull(clientConn, reply); err != nil {
		t.Fatalf("failed to read SOCKS4 reply: %v", err)
	}
	if err := <-done; err != nil {
		t.Fatalf("sendSocks4Reply returned error: %v", err)
	}

	want := []byte{0x00, SOCKS4_REPLY_GRANTED, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	for i := range want {
		if reply[i] != want[i] {
			t.Fatalf("reply[%d] = 0x%02x, want 0x%02x", i, reply[i], want[i])
		}
	}
}

func TestLateSocksResultDoesNotReactivateCancelledStream(t *testing.T) {
	c := &Client{
		active_streams: make(map[uint16]*Stream_client),
	}

	server, clientConn := net.Pipe()
	defer server.Close()
	defer clientConn.Close()

	s := &Stream_client{
		client:            c,
		StreamID:          7,
		LocalSocksVersion: SOCKS5_VERSION,
		NetConn:           server,
		Status:            streamStatusSocksConnecting,
		CreateTime:        time.Now(),
		LastActivityTime:  time.Now(),
	}
	c.active_streams[s.StreamID] = s

	c.handlePendingSOCKSLocalClose(s.StreamID, "test cancel")
	if got := s.StatusValue(); got != streamStatusCancelled {
		t.Fatalf("expected stream status %q after local close, got %q", streamStatusCancelled, got)
	}

	if err := c.HandleSocksConnected(VpnProto.Packet{StreamID: s.StreamID}); err != nil {
		t.Fatalf("HandleSocksConnected returned error: %v", err)
	}

	if got := s.StatusValue(); got != streamStatusCancelled {
		t.Fatalf("expected cancelled stream not to reactivate, got %q", got)
	}
	if s.TerminalSince().IsZero() {
		t.Fatal("expected cancelled stream to remain terminal after late SOCKS result")
	}
}

func TestBuildSocksUDPResponseHeaderIPv4EchoesTarget(t *testing.T) {
	got := buildSocksUDPResponseHeader(SOCKS5_ATYP_IPV4, "77.88.8.88", 53)
	want := []byte{
		0x00, 0x00, // RSV
		0x00,             // FRAG
		SOCKS5_ATYP_IPV4, // ATYP
		77, 88, 8, 88,    // DST.ADDR
		0x00, 0x35, // DST.PORT = 53
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("header = %x, want %x", got, want)
	}
}

func TestBuildSocksUDPResponseHeaderIPv6EchoesTarget(t *testing.T) {
	got := buildSocksUDPResponseHeader(SOCKS5_ATYP_IPV6, "2001:db8::1", 5353)
	if len(got) != 4+16+2 {
		t.Fatalf("unexpected len %d", len(got))
	}
	if got[3] != SOCKS5_ATYP_IPV6 {
		t.Errorf("ATYP = %x, want IPv6", got[3])
	}
	if got[len(got)-2] != 0x14 || got[len(got)-1] != 0xE9 {
		t.Errorf("port bytes = %x %x, want 14 E9 (5353)", got[len(got)-2], got[len(got)-1])
	}
}

func TestBuildSocksUDPResponseHeaderDomainPreservesName(t *testing.T) {
	got := buildSocksUDPResponseHeader(SOCKS5_ATYP_DOMAIN, "dns.example.com", 53)
	if got[3] != SOCKS5_ATYP_DOMAIN {
		t.Fatalf("ATYP = %x, want DOMAIN", got[3])
	}
	nameLen := int(got[4])
	if nameLen != len("dns.example.com") {
		t.Fatalf("name len = %d", nameLen)
	}
	if string(got[5:5+nameLen]) != "dns.example.com" {
		t.Errorf("name = %q", string(got[5:5+nameLen]))
	}
}

func TestBuildSocksUDPResponseHeaderNeverProducesZeroAddr(t *testing.T) {
	// The pre-v0.1.3 bug: header was hardcoded to 0.0.0.0:53. Make sure
	// the new builder echoes real targets — the regression bait is any
	// header whose ATYP=IPv4 has 0.0.0.0 when a real IP was provided.
	got := buildSocksUDPResponseHeader(SOCKS5_ATYP_IPV4, "1.2.3.4", 53)
	if got[4] == 0 && got[5] == 0 && got[6] == 0 && got[7] == 0 {
		t.Fatal("regression: header DST.ADDR is 0.0.0.0 for non-zero target")
	}
}
