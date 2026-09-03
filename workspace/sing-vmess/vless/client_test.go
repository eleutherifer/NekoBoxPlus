package vless

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

func TestPacketConnConcurrentWritesPreservePacketBoundaries(t *testing.T) {
	underlying := newReorderingConn()
	conn := &PacketConn{
		Conn:           underlying,
		requestWritten: true,
	}
	firstPayload := []byte("first DNS query")
	secondPayload := []byte("second DNS query")

	errCh := make(chan error, 2)
	var writes sync.WaitGroup
	writes.Add(1)
	go func() {
		defer writes.Done()
		_, err := conn.Write(firstPayload)
		errCh <- err
	}()

	<-underlying.firstWriteEntered
	writes.Add(1)
	go func() {
		defer writes.Done()
		_, err := conn.Write(secondPayload)
		errCh <- err
	}()

	select {
	case <-underlying.secondWriteEntered:
	case <-time.After(100 * time.Millisecond):
	}
	close(underlying.releaseFirstWrite)
	writes.Wait()
	close(errCh)
	for err := range errCh {
		if err != nil {
			t.Fatal(err)
		}
	}

	stream := bytes.NewReader(underlying.Bytes())
	for index, expected := range [][]byte{firstPayload, secondPayload} {
		var length uint16
		if err := binary.Read(stream, binary.BigEndian, &length); err != nil {
			t.Fatalf("packet %d: read length: %v", index, err)
		}
		payload := make([]byte, length)
		if _, err := io.ReadFull(stream, payload); err != nil {
			t.Fatalf("packet %d: read payload: %v", index, err)
		}
		if !bytes.Equal(payload, expected) {
			t.Fatalf("packet %d: payload = %q, want %q", index, payload, expected)
		}
	}
	if stream.Len() != 0 {
		t.Fatalf("unexpected trailing data: %d bytes", stream.Len())
	}
}

type reorderingConn struct {
	access                 sync.Mutex
	writeCount             int
	data                   bytes.Buffer
	firstWriteEntered      chan struct{}
	secondWriteEntered     chan struct{}
	releaseFirstWrite      chan struct{}
	secondWriteEnteredOnce sync.Once
}

func newReorderingConn() *reorderingConn {
	return &reorderingConn{
		firstWriteEntered:  make(chan struct{}),
		secondWriteEntered: make(chan struct{}),
		releaseFirstWrite:  make(chan struct{}),
	}
}

func (c *reorderingConn) Read([]byte) (int, error) {
	return 0, io.EOF
}

func (c *reorderingConn) Write(data []byte) (int, error) {
	c.access.Lock()
	c.writeCount++
	writeCount := c.writeCount
	c.access.Unlock()

	if writeCount == 1 {
		close(c.firstWriteEntered)
		<-c.releaseFirstWrite
	} else {
		c.secondWriteEnteredOnce.Do(func() {
			close(c.secondWriteEntered)
		})
	}

	c.access.Lock()
	defer c.access.Unlock()
	return c.data.Write(data)
}

func (c *reorderingConn) Bytes() []byte {
	c.access.Lock()
	defer c.access.Unlock()
	return bytes.Clone(c.data.Bytes())
}

func (c *reorderingConn) Close() error                     { return nil }
func (c *reorderingConn) LocalAddr() net.Addr              { return nil }
func (c *reorderingConn) RemoteAddr() net.Addr             { return nil }
func (c *reorderingConn) SetDeadline(time.Time) error      { return nil }
func (c *reorderingConn) SetReadDeadline(time.Time) error  { return nil }
func (c *reorderingConn) SetWriteDeadline(time.Time) error { return nil }
