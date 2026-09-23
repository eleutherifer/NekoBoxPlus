package encryption

import (
	"bytes"
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

func TestCommonConnConcurrentWritesPreserveAEADRecordOrder(t *testing.T) {
	const useAES = false
	contextBytes := []byte("test context")
	key := []byte("0123456789abcdef0123456789abcdef")
	underlying := newReorderingConn()
	conn := NewCommonConn(underlying, useAES)
	conn.AEAD = NewAEAD(contextBytes, key, useAES)

	firstPayload := []byte("first DNS query")
	secondPayload := []byte("second DNS query")
	errCh := make(chan error, 2)
	var writes sync.WaitGroup
	writes.Go(func() {
		_, err := conn.Write(firstPayload)
		errCh <- err
	})

	<-underlying.firstWriteEntered
	writes.Go(func() {
		_, err := conn.Write(secondPayload)
		errCh <- err
	})

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

	peerAEAD := NewAEAD(contextBytes, key, useAES)
	stream := underlying.Bytes()
	for index, expected := range [][]byte{firstPayload, secondPayload} {
		if len(stream) < 5 {
			t.Fatalf("record %d: missing header", index)
		}
		header := stream[:5]
		length, err := DecodeHeader(header)
		if err != nil {
			t.Fatalf("record %d: %v", index, err)
		}
		if len(stream) < 5+length {
			t.Fatalf("record %d: short ciphertext", index)
		}
		ciphertext := stream[5 : 5+length]
		plaintext, err := peerAEAD.Open(nil, nil, ciphertext, header)
		if err != nil {
			t.Fatalf("record %d: %v", index, err)
		}
		if !bytes.Equal(plaintext, expected) {
			t.Fatalf("record %d: payload = %q, want %q", index, plaintext, expected)
		}
		stream = stream[5+length:]
	}
	if len(stream) != 0 {
		t.Fatalf("unexpected trailing data: %d bytes", len(stream))
	}
}

type reorderingConn struct {
	access                 sync.Mutex
	writeCount             int
	records                bytes.Buffer
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
	return c.records.Write(data)
}

func (c *reorderingConn) Bytes() []byte {
	c.access.Lock()
	defer c.access.Unlock()
	return bytes.Clone(c.records.Bytes())
}

func (c *reorderingConn) Close() error                     { return nil }
func (c *reorderingConn) LocalAddr() net.Addr              { return nil }
func (c *reorderingConn) RemoteAddr() net.Addr             { return nil }
func (c *reorderingConn) SetDeadline(time.Time) error      { return nil }
func (c *reorderingConn) SetReadDeadline(time.Time) error  { return nil }
func (c *reorderingConn) SetWriteDeadline(time.Time) error { return nil }
