package xhttp

import (
	"testing"
	"time"

	common "github.com/sagernet/sing-box/common/xray"
)

func TestUploadQueueReadZeroRegression(t *testing.T) {
	q := NewUploadQueue(10)
	common.Must(q.Push(Packet{
		Payload: []byte("x"),
		Seq:     0,
	}))

	buf := make([]byte, 20)
	n, err := q.Read(buf)
	common.Must(err)
	if n != 1 {
		t.Fatal("n=", n)
	}
}

func TestUploadQueueSkipsStalePacket(t *testing.T) {
	q := NewUploadQueue(10)
	common.Must(q.Push(Packet{
		Payload: []byte("x"),
		Seq:     0,
	}))

	buf := make([]byte, 20)
	n, err := q.Read(buf)
	common.Must(err)
	if n != 1 {
		t.Fatal("n=", n)
	}

	common.Must(q.Push(Packet{
		Payload: []byte("stale"),
		Seq:     0,
	}))
	common.Must(q.Push(Packet{
		Payload: []byte("y"),
		Seq:     1,
	}))

	n, err = q.Read(buf)
	common.Must(err)
	if n != 1 || string(buf[:n]) != "y" {
		t.Fatalf("unexpected read: n=%d payload=%q", n, string(buf[:n]))
	}
}

func TestUploadQueueSkipsEmptyPacket(t *testing.T) {
	q := NewUploadQueue(10)
	common.Must(q.Push(Packet{
		Payload: nil,
		Seq:     0,
	}))
	common.Must(q.Push(Packet{
		Payload: []byte("x"),
		Seq:     1,
	}))

	buf := make([]byte, 20)
	n, err := q.Read(buf)
	common.Must(err)
	if n != 1 || string(buf[:n]) != "x" {
		t.Fatalf("unexpected read: n=%d payload=%q", n, string(buf[:n]))
	}
}

func TestUploadQueuePushReturnsWhenFull(t *testing.T) {
	q := NewUploadQueue(1)
	common.Must(q.Push(Packet{
		Payload: []byte("x"),
		Seq:     1,
	}))

	errCh := make(chan error, 1)
	go func() {
		errCh <- q.Push(Packet{
			Payload: []byte("y"),
			Seq:     2,
		})
	}()

	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("expected full queue error")
		}
	case <-time.After(time.Second):
		t.Fatal("Push blocked on a full queue")
	}
}
