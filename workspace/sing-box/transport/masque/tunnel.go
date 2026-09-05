package masque

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing-quic"
	"github.com/sagernet/sing-tun"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	aTLS "github.com/sagernet/sing/common/tls"
)

type tunnelSession struct {
	ipConn  IpConn
	closers []io.Closer
	once    sync.Once
}

func (s *tunnelSession) Close() {
	s.once.Do(func() {
		if s.ipConn != nil {
			_ = s.ipConn.Close()
		}
		for _, closer := range s.closers {
			if closer != nil {
				_ = closer.Close()
			}
		}
	})
}

type connectionAttempt struct {
	done    chan struct{}
	session *tunnelSession
	err     error
}

type Tunnel struct {
	ctx     context.Context
	cancel  context.CancelFunc
	logger  logger.ContextLogger
	options TunnelOptions
	device  Device

	mu      sync.Mutex
	session *tunnelSession
	attempt *connectionAttempt
	closed  bool

	dialH3 func(context.Context) (*tunnelSession, error)
	dialH2 func(context.Context) (*tunnelSession, error)
}

func NewTunnel(ctx context.Context, logger logger.ContextLogger, options TunnelOptions) (*Tunnel, error) {
	tunnelCtx, cancel := context.WithCancel(ctx)
	device, err := NewDevice(DeviceOptions{
		Context:        tunnelCtx,
		Logger:         logger,
		System:         options.System,
		UDPTimeout:     options.UDPTimeout,
		CreateDialer:   options.CreateDialer,
		Name:           options.Name,
		MTU:            options.MTU,
		Address:        options.Address,
		AllowedAddress: options.AllowedAddress,
	})
	if err != nil {
		cancel()
		return nil, E.Cause(err, "create MASQUE device")
	}
	return &Tunnel{ctx: tunnelCtx, cancel: cancel, logger: logger, options: options, device: device}, nil
}

func (t *Tunnel) Start(resolve bool) error {
	if !resolve {
		return nil
	}
	if err := t.device.Start(); err != nil {
		return err
	}
	go t.pumpToTunnel()
	return nil
}

func (t *Tunnel) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if !destination.Addr.IsValid() {
		return nil, E.Cause(os.ErrInvalid, "invalid non-IP destination")
	}
	if _, err := t.ensureSession(ctx); err != nil {
		return nil, err
	}
	return t.device.DialContext(ctx, network, destination)
}

func (t *Tunnel) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if !destination.Addr.IsValid() {
		return nil, E.Cause(os.ErrInvalid, "invalid non-IP destination")
	}
	if _, err := t.ensureSession(ctx); err != nil {
		return nil, err
	}
	return t.device.ListenPacket(ctx, destination)
}

func (t *Tunnel) PortAddresses() (netip.Addr, netip.Addr) {
	return t.device.PortAddresses()
}

func (t *Tunnel) PortMTU() uint32 {
	return t.device.PortMTU()
}

func (t *Tunnel) AttachReturn(returnPath tun.Return) error {
	return t.device.AttachReturn(returnPath)
}

func (t *Tunnel) DetachReturn(returnPath tun.Return) error {
	return t.device.DetachReturn(returnPath)
}

func (t *Tunnel) WritePackets(packets [][]byte) error {
	return t.device.WritePackets(packets)
}

func (t *Tunnel) Close() error {
	t.cancel()
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil
	}
	t.closed = true
	session := t.session
	t.session = nil
	t.mu.Unlock()
	if session != nil {
		session.Close()
	}
	return t.device.Close()
}

func (t *Tunnel) ensureSession(ctx context.Context) (*tunnelSession, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, net.ErrClosed
	}
	if t.session != nil {
		session := t.session
		t.mu.Unlock()
		return session, nil
	}
	attempt := t.attempt
	if attempt == nil {
		attempt = &connectionAttempt{done: make(chan struct{})}
		t.attempt = attempt
		go t.runConnectionAttempt(attempt)
	}
	t.mu.Unlock()

	select {
	case <-attempt.done:
		return attempt.session, attempt.err
	case <-ctx.Done():
		return nil, context.Cause(ctx)
	}
}

func (t *Tunnel) runConnectionAttempt(attempt *connectionAttempt) {
	session, err := t.connectSession(t.ctx)
	t.mu.Lock()
	if t.closed && session != nil {
		session.Close()
		session = nil
		err = net.ErrClosed
	}
	if err == nil {
		t.session = session
	}
	attempt.session = session
	attempt.err = err
	if t.attempt == attempt {
		t.attempt = nil
	}
	close(attempt.done)
	t.mu.Unlock()
	if err == nil {
		go t.pumpFromTunnel(session)
	}
}

func (t *Tunnel) connectSession(ctx context.Context) (*tunnelSession, error) {
	switch t.options.Transport {
	case "h2":
		return t.establishH2(ctx)
	case "h3":
		return t.establishH3(ctx)
	case "auto":
		h3Ctx, cancel := context.WithTimeout(ctx, t.options.H3FallbackTimeout)
		session, err := t.establishH3(h3Ctx)
		cancel()
		if err == nil {
			return session, nil
		}
		if !canFallbackToH2(err) {
			return nil, err
		}
		if t.logger != nil {
			t.logger.WarnContext(ctx, "MASQUE HTTP/3 failed, falling back to HTTP/2: ", err)
		}
		return t.establishH2(ctx)
	default:
		return nil, E.New("invalid MASQUE transport: ", t.options.Transport)
	}
}

func (t *Tunnel) establishH3(ctx context.Context) (*tunnelSession, error) {
	if t.dialH3 != nil {
		return t.dialH3(ctx)
	}
	return t.connectH3(ctx)
}

func (t *Tunnel) establishH2(ctx context.Context) (*tunnelSession, error) {
	if t.dialH2 != nil {
		return t.dialH2(ctx)
	}
	return t.connectH2(ctx)
}

func canFallbackToH2(err error) bool {
	var statusErr *ConnectResponseError
	if errors.As(err, &statusErr) && statusErr.StatusCode >= 400 && statusErr.StatusCode < 500 {
		return false
	}
	message := strings.ToLower(err.Error())
	for _, marker := range []string{"access denied", "login failed", "certificate", "public key"} {
		if strings.Contains(message, marker) {
			return false
		}
	}
	return true
}

func (t *Tunnel) connectH3(ctx context.Context) (*tunnelSession, error) {
	if t.options.H3Endpoint == nil {
		return nil, E.New("missing HTTP/3 endpoint")
	}
	udpConn, err := t.options.Dialer.DialContext(ctx, N.NetworkUDP, M.SocksaddrFromNetIP(t.options.H3Endpoint.AddrPort()))
	if err != nil {
		return nil, E.Cause(err, "dial UDP")
	}
	quicConn, err := qtls.DialEarly(
		ctx,
		udpConn,
		t.options.TLSConfig,
		DefaultQuicConfig(t.options.UDPKeepalivePeriod, t.options.UDPInitialPacketSize, t.options.DisablePathMTUDiscovery),
	)
	if err != nil {
		_ = udpConn.Close()
		return nil, E.Cause(err, "dial QUIC")
	}
	tr, ipConn, err := ConnectTunnelH3(ctx, CloudflareProfile, quicConn, "https://cloudflareaccess.com")
	if err != nil {
		_ = quicConn.CloseWithError(0, "connect-ip failed")
		_ = udpConn.Close()
		return nil, err
	}
	return &tunnelSession{ipConn: ipConn, closers: []io.Closer{tr, udpConn}}, nil
}

func (t *Tunnel) connectH2(ctx context.Context) (*tunnelSession, error) {
	if t.options.H2Endpoint == nil {
		return nil, E.New("missing HTTP/2 endpoint")
	}
	rawConn, err := t.options.Dialer.DialContext(ctx, N.NetworkTCP, M.SocksaddrFromNetIP(t.options.H2Endpoint.AddrPort()))
	if err != nil {
		return nil, E.Cause(err, "dial TCP")
	}
	tlsConfig := t.options.TLSConfig.Clone()
	tlsConfig.SetNextProtos([]string{"h2"})
	tlsConn, err := aTLS.ClientHandshake(ctx, rawConn, tlsConfig)
	if err != nil {
		_ = rawConn.Close()
		return nil, E.Cause(err, "TLS handshake")
	}
	closer, ipConn, err := ConnectTunnelH2(ctx, CloudflareProfile, tlsConn, "https://cloudflareaccess.com")
	if err != nil {
		_ = tlsConn.Close()
		return nil, err
	}
	return &tunnelSession{ipConn: ipConn, closers: []io.Closer{closer}}, nil
}

func (t *Tunnel) closeSession(session *tunnelSession) bool {
	session.Close()
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.session == session {
		t.session = nil
		return true
	}
	return false
}

func (t *Tunnel) handleSessionLoss(session *tunnelSession, err error) {
	if !t.closeSession(session) || t.ctx.Err() != nil {
		return
	}
	if t.logger != nil {
		t.logger.WarnContext(t.ctx, E.Cause(err, "MASQUE session closed; reconnecting"))
	}
	go t.reconnectAfterSessionLoss()
}

func (t *Tunnel) reconnectAfterSessionLoss() {
	if !sleepContext(t.ctx, t.options.ReconnectDelay) {
		return
	}
	for {
		_, err := t.ensureSession(t.ctx)
		if err == nil {
			if t.logger != nil {
				t.logger.InfoContext(t.ctx, "MASQUE session reconnected")
			}
			return
		}
		if t.ctx.Err() != nil {
			return
		}
		if t.logger != nil {
			t.logger.ErrorContext(t.ctx, E.Cause(err, "reconnect MASQUE session"))
		}
		if !sleepContext(t.ctx, t.options.ReconnectDelay) {
			return
		}
	}
}

func (t *Tunnel) pumpToTunnel() {
	batchSize := max(t.device.BatchSize(), 1)
	offset := tun.PacketOffset
	bufs := make([][]byte, batchSize)
	sizes := make([]int, batchSize)
	for i := range bufs {
		bufs[i] = make([]byte, offset+int(t.options.MTU))
	}
	for t.ctx.Err() == nil {
		count, err := t.device.Read(bufs, sizes, offset)
		if err != nil {
			if t.ctx.Err() == nil {
				t.logger.ErrorContext(t.ctx, E.Cause(err, "read MASQUE device"))
			}
			return
		}
		session, err := t.ensureSession(t.ctx)
		if err != nil {
			t.logger.ErrorContext(t.ctx, E.Cause(err, "connect MASQUE tunnel"))
			if !sleepContext(t.ctx, t.options.ReconnectDelay) {
				return
			}
			continue
		}
		for i := range count {
			icmp, writeErr := session.ipConn.WritePacket(bufs[i][offset : offset+sizes[i]])
			if writeErr != nil {
				t.handleSessionLoss(session, E.Cause(writeErr, "write MASQUE session"))
				break
			}
			if len(icmp) > 0 {
				_, _ = t.device.Write([][]byte{addPacketOffset(icmp)}, offset)
			}
		}
	}
}

func (t *Tunnel) pumpFromTunnel(session *tunnelSession) {
	for t.ctx.Err() == nil {
		packet, err := session.ipConn.ReadPacket()
		if err != nil {
			t.handleSessionLoss(session, E.Cause(err, "read MASQUE session"))
			return
		}
		if _, err = t.device.Write([][]byte{addPacketOffset(packet)}, tun.PacketOffset); err != nil {
			t.handleSessionLoss(session, E.Cause(err, "write MASQUE device"))
			return
		}
	}
}

func addPacketOffset(packet []byte) []byte {
	if tun.PacketOffset == 0 {
		return packet
	}
	buffer := make([]byte, tun.PacketOffset+len(packet))
	copy(buffer[tun.PacketOffset:], packet)
	return buffer
}

func sleepContext(ctx context.Context, delay time.Duration) bool {
	if delay <= 0 {
		delay = time.Second
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

var _ io.Closer = (*http3.Transport)(nil)
