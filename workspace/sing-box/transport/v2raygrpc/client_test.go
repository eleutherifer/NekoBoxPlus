package v2raygrpc

import (
	"context"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

type blockingDialer struct {
	started chan struct{}
	once    sync.Once
}

func (d *blockingDialer) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	d.once.Do(func() { close(d.started) })
	<-ctx.Done()
	return nil, context.Cause(ctx)
}

func (d *blockingDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	panic("unexpected packet dial")
}

func TestClientDialContextHonorsCallerDeadline(t *testing.T) {
	dialer := &blockingDialer{started: make(chan struct{})}
	client, err := NewClient(t.Context(), dialer, M.ParseSocksaddr("example.com:443"), option.V2RayGRPCOptions{}, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()

	started := time.Now()
	_, err = client.DialContext(ctx)
	if err == nil {
		t.Fatal("expected caller deadline error")
	}
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("blocked dial returned too late: %v", elapsed)
	}
}

func TestClientDialContextHonorsClientCancellation(t *testing.T) {
	dialer := &blockingDialer{started: make(chan struct{})}
	clientCtx, cancelClient := context.WithCancel(t.Context())
	client, err := NewClient(clientCtx, dialer, M.ParseSocksaddr("example.com:443"), option.V2RayGRPCOptions{}, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	result := make(chan error, 1)
	go func() {
		_, dialErr := client.DialContext(t.Context())
		result <- dialErr
	}()

	select {
	case <-dialer.started:
	case <-time.After(time.Second):
		t.Fatal("dial did not start")
	}
	cancelClient()
	select {
	case err = <-result:
		if err == nil {
			t.Fatal("expected client cancellation error")
		}
	case <-time.After(time.Second):
		t.Fatal("dial ignored client cancellation")
	}
}
