package byedpi

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/varbin"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

func TestPrivateHandshakeConnect(t *testing.T) {
	client, server := net.Pipe()
	t.Cleanup(func() {
		_ = client.Close()
		_ = server.Close()
	})

	serverErr := make(chan error, 1)
	go func() {
		reader := varbin.StubReader(server)
		request, err := socks5.ReadAuthRequest(reader)
		if err != nil {
			serverErr <- err
			return
		}
		if len(request.Methods) != 1 ||
			request.Methods[0] != socks5.AuthTypeNotRequired {
			serverErr <- errors.New("unexpected authentication methods")
			return
		}
		if err = socks5.WriteAuthResponse(server, socks5.AuthResponse{
			Method: socks5.AuthTypeNotRequired,
		}); err != nil {
			serverErr <- err
			return
		}
		connectRequest, err := socks5.ReadRequest(reader)
		if err != nil {
			serverErr <- err
			return
		}
		if connectRequest.Command != socks5.CommandConnect ||
			connectRequest.Destination.String() != "1.1.1.1:443" {
			serverErr <- errors.New("unexpected connect request")
			return
		}
		serverErr <- socks5.WriteResponse(server, socks5.Response{
			ReplyCode: socks5.ReplyCodeSuccess,
		})
	}()

	if err := privateHandshake(
		t.Context(),
		client,
		socks5.CommandConnect,
		M.ParseSocksaddr("1.1.1.1:443"),
	); err != nil {
		t.Fatal(err)
	}
	if err := <-serverErr; err != nil {
		t.Fatal(err)
	}
}

func TestPrivateHandshakeReturnsContextDeadline(t *testing.T) {
	client, server := net.Pipe()
	t.Cleanup(func() {
		_ = client.Close()
		_ = server.Close()
	})

	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()

	err := privateHandshake(
		ctx,
		client,
		socks5.CommandConnect,
		M.ParseSocksaddr("1.1.1.1:443"),
	)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected context deadline exceeded, got %v", err)
	}
}
