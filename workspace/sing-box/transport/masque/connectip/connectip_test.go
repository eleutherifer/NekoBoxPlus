package connectip

import (
	"context"
	"io"
	"net/netip"
	"testing"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/quicvarint"
)

type packetStream struct {
	received [][]byte
	sent     [][]byte
}

func (s *packetStream) Read([]byte) (int, error)        { return 0, io.EOF }
func (s *packetStream) Write(p []byte) (int, error)     { return len(p), nil }
func (s *packetStream) Close() error                    { return nil }
func (s *packetStream) CancelRead(quic.StreamErrorCode) {}
func (s *packetStream) ReceiveDatagram(context.Context) ([]byte, error) {
	if len(s.received) == 0 {
		return nil, io.EOF
	}
	packet := s.received[0]
	s.received = s.received[1:]
	return packet, nil
}
func (s *packetStream) SendDatagram(packet []byte) error {
	s.sent = append(s.sent, append([]byte(nil), packet...))
	return nil
}

func TestReadPacketDropsMalformedDatagram(t *testing.T) {
	ipPacket := makeIPv4Packet(64)
	stream := &packetStream{received: [][]byte{
		{},
		quicvarint.Append(append([]byte(nil), contextIDZero...), 1),
		append(append([]byte(nil), contextIDZero...), ipPacket...),
	}}
	conn := &Conn{
		str:         stream,
		closeChan:   make(chan struct{}),
		localRoutes: []IPRoute{{StartIP: netip.IPv4Unspecified(), EndIP: netip.MustParseAddr("255.255.255.255")}},
	}
	packet, err := conn.ReadPacket()
	if err != nil {
		t.Fatal(err)
	}
	if len(packet) != len(ipPacket) {
		t.Fatalf("packet length = %d, want %d", len(packet), len(ipPacket))
	}
}

func TestWritePacketDecrementsTTLAndReusesBuffer(t *testing.T) {
	stream := &packetStream{}
	conn := &Conn{str: stream, closeChan: make(chan struct{})}
	first := makeIPv4Packet(64)
	if _, err := conn.WritePacket(first); err != nil {
		t.Fatal(err)
	}
	if first[8] != 63 {
		t.Fatalf("TTL = %d, want 63", first[8])
	}
	firstCapacity := cap(conn.sendBuf)
	second := makeIPv4Packet(32)
	if _, err := conn.WritePacket(second); err != nil {
		t.Fatal(err)
	}
	if cap(conn.sendBuf) != firstCapacity {
		t.Fatal("send buffer was reallocated for a smaller packet")
	}
	if len(stream.sent) != 2 {
		t.Fatalf("sent datagrams = %d, want 2", len(stream.sent))
	}
}

func makeIPv4Packet(ttl byte) []byte {
	packet := make([]byte, 20)
	packet[0] = 0x45
	packet[8] = ttl
	packet[9] = 6
	copy(packet[12:16], netip.MustParseAddr("192.0.2.1").AsSlice())
	copy(packet[16:20], netip.MustParseAddr("198.51.100.1").AsSlice())
	return packet
}
