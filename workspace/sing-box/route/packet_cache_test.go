package route

import (
	"io"
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func TestCachePacketBuffersPreservesFIFOOrder(t *testing.T) {
	first := N.NewPacketBuffer()
	first.Buffer = buf.As([]byte("first"))
	first.Destination = M.Socksaddr{Fqdn: "first.example", Port: 443}
	second := N.NewPacketBuffer()
	second.Buffer = buf.As([]byte("second"))
	second.Destination = M.Socksaddr{Fqdn: "second.example", Port: 443}

	conn := cachePacketBuffers(noopPacketConn{}, []*N.PacketBuffer{first, second})

	assertCachedPacket(t, conn, "first", "first.example")
	assertCachedPacket(t, conn, "second", "second.example")
}

func assertCachedPacket(t *testing.T, conn N.PacketConn, expectedData string, expectedHost string) {
	t.Helper()
	buffer := buf.NewPacket()
	defer buffer.Release()
	destination, err := conn.ReadPacket(buffer)
	if err != nil {
		t.Fatal(err)
	}
	if string(buffer.Bytes()) != expectedData {
		t.Fatalf("packet data = %q, want %q", buffer.Bytes(), expectedData)
	}
	if destination.Fqdn != expectedHost {
		t.Fatalf("packet destination = %s, want host %s", destination, expectedHost)
	}
}

type noopPacketConn struct{}

func (noopPacketConn) ReadPacket(*buf.Buffer) (M.Socksaddr, error) {
	return M.Socksaddr{}, io.EOF
}

func (noopPacketConn) WritePacket(*buf.Buffer, M.Socksaddr) error {
	return nil
}

func (noopPacketConn) Close() error {
	return nil
}

func (noopPacketConn) LocalAddr() net.Addr {
	return nil
}

func (noopPacketConn) SetDeadline(time.Time) error {
	return nil
}

func (noopPacketConn) SetReadDeadline(time.Time) error {
	return nil
}

func (noopPacketConn) SetWriteDeadline(time.Time) error {
	return nil
}
