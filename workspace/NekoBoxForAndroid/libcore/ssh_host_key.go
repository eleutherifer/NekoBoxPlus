package libcore

import (
	"context"
	"errors"
	"fmt"
	"libcore/device"
	"net"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/ssh"
)

const sshHostKeyFetchTimeout = 10 * time.Second

var (
	errSSHHostKeyCaptured = errors.New("SSH host key captured")
	errSSHHostKeyTimeout  = errors.New("SSH host key fetch timed out")
)

// FetchSSHHostKey connects to an SSH server and returns the host key selected
// during key exchange in OpenSSH authorized_keys format.
func FetchSSHHostKey(host, port string) (hostKey string, err error) {
	defer device.DeferPanicToError("FetchSSHHostKey", func(panicErr error) { err = panicErr })

	address, err := sshHostKeyAddress(host, port)
	if err != nil {
		return "", err
	}

	ctx, cancel := context.WithTimeoutCause(
		context.Background(),
		sshHostKeyFetchTimeout,
		errSSHHostKeyTimeout,
	)
	defer cancel()

	dialer := &net.Dialer{Control: protectSocketControl}
	acquireProtect()
	defer releaseProtect()
	return fetchSSHHostKey(ctx, address, dialer.DialContext)
}

func sshHostKeyAddress(host, port string) (string, error) {
	host = strings.TrimSpace(host)
	if host == "" {
		return "", errors.New("SSH server address is empty")
	}
	port = strings.TrimSpace(port)
	portNumber, err := strconv.ParseUint(port, 10, 16)
	if err != nil || portNumber == 0 {
		return "", fmt.Errorf("invalid SSH server port %q", port)
	}
	return net.JoinHostPort(host, port), nil
}

func fetchSSHHostKey(
	ctx context.Context,
	address string,
	dialContext func(context.Context, string, string) (net.Conn, error),
) (string, error) {
	conn, err := dialContext(ctx, "tcp", address)
	if err != nil {
		return "", sshHostKeyError(ctx, "connect to SSH server", err)
	}
	defer conn.Close()

	if deadline, ok := ctx.Deadline(); ok {
		if err := conn.SetDeadline(deadline); err != nil {
			return "", fmt.Errorf("set SSH connection deadline: %w", err)
		}
	}

	var hostKey string
	config := &ssh.ClientConfig{
		User: "host-key-fetch",
		HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			hostKey = strings.TrimSpace(string(ssh.MarshalAuthorizedKey(key)))
			return errSSHHostKeyCaptured
		},
	}
	_, _, _, err = ssh.NewClientConn(conn, address, config)
	if hostKey != "" {
		return hostKey, nil
	}
	if err == nil {
		return "", errors.New("SSH server returned no host key")
	}
	return "", sshHostKeyError(ctx, "perform SSH handshake", err)
}

func sshHostKeyError(ctx context.Context, operation string, err error) error {
	if cause := context.Cause(ctx); cause != nil {
		return cause
	}
	return fmt.Errorf("%s: %w", operation, err)
}
