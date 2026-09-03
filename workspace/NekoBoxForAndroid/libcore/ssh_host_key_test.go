package libcore

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"net"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

func TestSSHHostKeyAddress(t *testing.T) {
	address, err := sshHostKeyAddress(" 2001:db8::1 ", " 2222 ")
	if err != nil {
		t.Fatal(err)
	}
	if address != "[2001:db8::1]:2222" {
		t.Fatalf("unexpected address %q", address)
	}

	for _, testCase := range []struct {
		host string
		port string
	}{
		{"", "22"},
		{"example.com", ""},
		{"example.com", "0"},
		{"example.com", "65536"},
		{"example.com", "invalid"},
	} {
		if _, err := sshHostKeyAddress(testCase.host, testCase.port); err == nil {
			t.Fatalf("expected %q:%q to fail", testCase.host, testCase.port)
		}
	}
}

func TestFetchSSHHostKeyReturnsAuthorizedKey(t *testing.T) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.NewSignerFromKey(privateKey)
	if err != nil {
		t.Fatal(err)
	}
	serverConfig := &ssh.ServerConfig{NoClientAuth: true}
	serverConfig.AddHostKey(signer)

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	serverDone := make(chan struct{})
	go func() {
		defer close(serverDone)
		serverConn, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer serverConn.Close()
		_, _, _, _ = ssh.NewServerConn(serverConn, serverConfig)
	}()

	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	hostKey, err := fetchSSHHostKey(
		ctx,
		listener.Addr().String(),
		new(net.Dialer).DialContext,
	)
	if err != nil {
		t.Fatal(err)
	}
	expectedKey, err := ssh.NewPublicKey(publicKey)
	if err != nil {
		t.Fatal(err)
	}
	expected := string(ssh.MarshalAuthorizedKey(expectedKey))
	if hostKey != expected[:len(expected)-1] {
		t.Fatalf("unexpected host key %q", hostKey)
	}
	if _, _, _, _, err := ssh.ParseAuthorizedKey([]byte(hostKey)); err != nil {
		t.Fatalf("returned key is not parseable: %v", err)
	}

	select {
	case <-serverDone:
	case <-time.After(time.Second):
		t.Fatal("SSH test server did not stop")
	}
}

func TestFetchSSHHostKeyTimesOutWithoutChangingResult(t *testing.T) {
	dialContext := func(context.Context, string, string) (net.Conn, error) {
		clientConn, serverConn := net.Pipe()
		t.Cleanup(func() { serverConn.Close() })
		return clientConn, nil
	}
	ctx, cancel := context.WithTimeoutCause(
		t.Context(),
		25*time.Millisecond,
		errSSHHostKeyTimeout,
	)
	defer cancel()

	hostKey, err := fetchSSHHostKey(ctx, "example.com:22", dialContext)
	if hostKey != "" {
		t.Fatalf("unexpected host key %q", hostKey)
	}
	if !errors.Is(err, errSSHHostKeyTimeout) {
		t.Fatalf("expected timeout error, got %v", err)
	}
}

func TestFetchSSHHostKeyReportsHandshakeFailure(t *testing.T) {
	dialContext := func(context.Context, string, string) (net.Conn, error) {
		clientConn, serverConn := net.Pipe()
		go func() {
			defer serverConn.Close()
			_, _ = serverConn.Write([]byte("not ssh\n"))
		}()
		return clientConn, nil
	}
	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()

	hostKey, err := fetchSSHHostKey(ctx, "example.com:22", dialContext)
	if hostKey != "" {
		t.Fatalf("unexpected host key %q", hostKey)
	}
	if err == nil {
		t.Fatal("expected handshake failure")
	}
}
