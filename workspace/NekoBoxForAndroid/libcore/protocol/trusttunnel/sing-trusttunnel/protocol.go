// Package trusttunnel implements the TrustTunnel protocol.
package trusttunnel

import (
	"bytes"
	"context"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"sync"
	"time"

	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/auth"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
)

const (
	Version = "v0.3.0-beta.6"

	UDPMagicAddress         = "_udp2"
	ICMPMagicAddress        = "_icmp"
	HealthCheckMagicAddress = "_check"

	AppName = "sing-trusttunnel"

	DefaultQuicStreamReceiveWindow  = 131072 // Chrome's default
	DefaultQuicConnectionWindow     = 100 * 1024 * 1024
	DefaultQuicMaxStreamWindow      = 1 * 1024 * 1024
	DefaultQuicMaxStreams           = 4 * 1024
	DefaultQuicMaxUDPPayloadSize    = 1350
	DefaultQuicFallbackProbeTimeout = 400 * time.Millisecond
	DefaultConnectionTimeout        = 30 * time.Second
	DefaultHealthCheckTimeout       = 7 * time.Second
	DefaultQuicMaxIdleTimeout       = 2 * (DefaultConnectionTimeout + DefaultHealthCheckTimeout)
	DefaultSessionTimeout           = 30 * time.Second
)

var ErrQUICNotIncluded = E.New("QUIC is not included")

func buildAuth(user auth.User) string {
	return "Basic " + base64.StdEncoding.EncodeToString([]byte(user.Username+":"+user.Password))
}

func parse16BytesIP(buffer [16]byte) netip.Addr {
	var zeroPrefix [12]byte
	isIPv4 := bytes.HasPrefix(buffer[:], zeroPrefix[:])
	// Special: check ::1
	isIPv4 = isIPv4 && !(buffer[12] == 0 && buffer[13] == 0 && buffer[14] == 0 && buffer[15] == 1)
	if isIPv4 {
		return netip.AddrFrom4([4]byte(buffer[12:16]))
	}
	return netip.AddrFrom16(buffer)
}

func buildPaddingIP(addr netip.Addr) (buffer [16]byte) {
	if addr.Is6() {
		return addr.As16()
	}
	ipv4 := addr.As4()
	copy(buffer[12:16], ipv4[:])
	return buffer
}

type httpConn struct {
	readMutex    sync.Mutex
	writeMutex   sync.Mutex
	writer       io.Writer
	flusher      http.Flusher
	body         io.ReadCloser
	wrapError    func(error) error
	recoveryHook func(error)
	created      chan struct{}
	createErr    error
	closeHook    func()
}

func (h *httpConn) setUp(body io.ReadCloser, err error) {
	h.body = body
	h.createErr = err
	close(h.created)
}

func (h *httpConn) waitCreated() error {
	<-h.created
	return h.createErr
}

func (h *httpConn) waitCreatedContext(ctx context.Context) error {
	select {
	case <-h.created:
		return h.createErr
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (h *httpConn) Close() error {
	if h.closeHook != nil {
		h.closeHook()
	}
	return common.Close(
		h.writer,
		h.body,
	)
}

func (h *httpConn) writeFlush(p []byte) (n int, err error) {
	h.writeMutex.Lock()
	defer h.writeMutex.Unlock()
	n, err = h.writer.Write(p)
	if h.flusher != nil {
		h.flusher.Flush()
	}
	return n, h.wrapRoundTripperError(err)
}

func (h *httpConn) recoverRoundTripper(err error) {
	if h.recoveryHook != nil && shouldRecoverRoundTripper(err) {
		h.recoveryHook(err)
	}
}

func (h *httpConn) wrapRoundTripperError(err error) error {
	if h.wrapError != nil {
		err = h.wrapError(err)
	}
	h.recoverRoundTripper(err)
	return err
}

func (h *httpConn) LocalAddr() net.Addr {
	return M.Socksaddr{}
}

func (h *httpConn) RemoteAddr() net.Addr {
	return M.Socksaddr{}
}

func (h *httpConn) SetDeadline(t time.Time) error {
	return os.ErrInvalid
}

func (h *httpConn) SetReadDeadline(t time.Time) error {
	return os.ErrInvalid
}

func (h *httpConn) SetWriteDeadline(t time.Time) error {
	return os.ErrInvalid
}

var _ net.Conn = (*tcpConn)(nil)

type tcpConn struct {
	httpConn
}

func (t *tcpConn) Read(b []byte) (n int, err error) {
	err = t.waitCreated()
	if err != nil {
		return 0, err
	}
	t.readMutex.Lock()
	defer t.readMutex.Unlock()
	n, err = t.body.Read(b)
	err = t.wrapRoundTripperError(err)
	return
}

func (t *tcpConn) Write(b []byte) (int, error) {
	return t.writeFlush(b)
}
