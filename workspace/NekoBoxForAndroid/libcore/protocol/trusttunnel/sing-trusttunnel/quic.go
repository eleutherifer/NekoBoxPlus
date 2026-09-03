//go:build with_quic

package trusttunnel

import (
	"context"
	stdTLS "crypto/tls"
	"net"
	"reflect"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/congestion"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing-quic"
	"github.com/sagernet/sing-quic/congestion_bbr1"
	"github.com/sagernet/sing-quic/congestion_bbr2"
	congestion_meta1 "github.com/sagernet/sing-quic/congestion_meta1"
	congestion_meta2 "github.com/sagernet/sing-quic/congestion_meta2"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/tls"
)

type observedHTTP3RoundTripper struct {
	*http3.Transport
	mu      sync.Mutex
	watched map[*quic.Conn]struct{}
	ignored map[*quic.Conn]struct{}
}

func (t *observedHTTP3RoundTripper) watchConn(client *Client, conn *quic.Conn) {
	t.mu.Lock()
	if t.watched == nil {
		t.watched = make(map[*quic.Conn]struct{})
	}
	if t.ignored == nil {
		t.ignored = make(map[*quic.Conn]struct{})
	}
	t.watched[conn] = struct{}{}
	t.mu.Unlock()

	go func() {
		<-conn.Context().Done()
		if t.ignoreConn(conn) {
			return
		}
		client.schedulePassiveRoundTripperRecovery(t, context.Cause(conn.Context()))
	}()
}

func (t *observedHTTP3RoundTripper) ignoreConn(conn *quic.Conn) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.watched, conn)
	if _, ignored := t.ignored[conn]; ignored {
		delete(t.ignored, conn)
		return true
	}
	return false
}

func (t *observedHTTP3RoundTripper) ignoreCurrentConns() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.ignored == nil {
		t.ignored = make(map[*quic.Conn]struct{})
	}
	if idleConns := http3TransportIdleConns(t.Transport); idleConns != nil {
		for _, conn := range idleConns {
			t.ignored[conn] = struct{}{}
		}
		return
	}
	for conn := range t.watched {
		t.ignored[conn] = struct{}{}
	}
}

func (t *observedHTTP3RoundTripper) Close() error {
	t.ignoreAllConns()
	return t.Transport.Close()
}

func (t *observedHTTP3RoundTripper) ignoreAllConns() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.ignored == nil {
		t.ignored = make(map[*quic.Conn]struct{})
	}
	for conn := range t.watched {
		t.ignored[conn] = struct{}{}
	}
}

func (t *observedHTTP3RoundTripper) CloseIdleConnections() {
	t.ignoreCurrentConns()
	t.Transport.CloseIdleConnections()
}

type h3RoundTripperWithCount struct {
	cancel     context.CancelFunc
	dialing    chan struct{}
	dialErr    error
	conn       *quic.Conn
	clientConn any
	useCount   atomic.Int64
}

func http3TransportIdleConns(transport *http3.Transport) []*quic.Conn {
	if transport == nil {
		return nil
	}
	transportType := reflect.TypeFor[http3.Transport]()
	mutexField, loaded := transportType.FieldByName("mutex")
	if !loaded || mutexField.Type != reflect.TypeFor[sync.Mutex]() {
		return nil
	}
	clientsField, loaded := transportType.FieldByName("clients")
	if !loaded || clientsField.Type.Kind() != reflect.Map {
		return nil
	}
	mutex := (*sync.Mutex)(unsafe.Add(unsafe.Pointer(transport), mutexField.Offset))
	mutex.Lock()
	defer mutex.Unlock()
	clients := *(*map[string]*h3RoundTripperWithCount)(unsafe.Add(unsafe.Pointer(transport), clientsField.Offset))
	idleConns := make([]*quic.Conn, 0, len(clients))
	for _, client := range clients {
		if client != nil && client.conn != nil && client.useCount.Load() == 0 {
			idleConns = append(idleConns, client.conn)
		}
	}
	return idleConns
}

func (c *Client) quicRoundTripper(tlsConfig tls.Config, congestionControlName string) error {
	stdConfig, err := tlsConfig.STDConfig()
	if err != nil {
		return err
	}
	roundTripper := &observedHTTP3RoundTripper{}
	transport := &http3.Transport{
		TLSClientConfig: stdConfig,
		QUICConfig: &quic.Config{
			Versions:                       []quic.Version{quic.Version1},
			MaxIdleTimeout:                 DefaultQuicMaxIdleTimeout,
			InitialStreamReceiveWindow:     DefaultQuicMaxStreamWindow,
			MaxStreamReceiveWindow:         DefaultQuicMaxStreamWindow,
			InitialConnectionReceiveWindow: DefaultQuicConnectionWindow,
			MaxConnectionReceiveWindow:     DefaultQuicConnectionWindow,
			MaxIncomingStreams:             DefaultQuicMaxStreams,
			MaxIncomingUniStreams:          DefaultQuicMaxStreams,
			InitialPacketSize:              DefaultQuicMaxUDPPayloadSize,
			DisablePathMTUDiscovery:        true,
			DisablePathManager:             true,
			Allow0RTT:                      false,
		},
		Dial: func(ctx context.Context, addr string, tlsCfg *stdTLS.Config, cfg *quic.Config) (*quic.Conn, error) {
			if c.clientRandomSpec != nil {
				tlsCfg = tlsCfg.Clone()
				randReader, randErr := c.clientRandomSpec.RandReader()
				if randErr != nil {
					return nil, randErr
				}
				tlsCfg.Rand = randReader
			}
			udpConn, err := c.detour.DialContext(ctx, N.NetworkUDP, c.server)
			if err != nil {
				return nil, err
			}
			quicConn, err := quic.DialEarly(ctx, bufio.NewUnbindPacketConn(udpConn), c.server.UDPAddr(), tlsCfg, cfg)
			if err != nil {
				_ = udpConn.Close()
				return nil, err
			}
			// quic-go does not take ownership of the packet connection passed to
			// DialEarly. Close the underlying connected UDP socket once QUIC stops
			// reading from it.
			context.AfterFunc(quicConn.Context(), func() {
				_ = udpConn.Close()
			})
			setCongestionControl(c.timeFunc, quicConn, congestionControlName)
			roundTripper.watchConn(c, quicConn)
			return quicConn, nil
		},
	}
	roundTripper.Transport = transport
	c.roundTripper = roundTripper
	c.wrapError = qtls.WrapError
	return nil
}

func (s *Service) configHTTP3Server(tlsConfig tls.ServerConfig, udpConn net.PacketConn) error {
	tlsConfig = tlsConfig.Clone().(tls.ServerConfig)
	err := qtls.ConfigureHTTP3(tlsConfig)
	if err != nil {
		return err
	}
	// https://github.com/SagerNet/sing-quic/blob/2afc335e0cddca3346d22ac42b26098faa783975/quic.go#L125
	// qtls.ConfigureHTTP3 never work because http3.ConfigureTLSConfig modified and returns a copy.
	// https://github.com/quic-go/quic-go/blob/c56e8c79d1627cc1ed6005b421b4b0adadd83665/http3/server.go#L47-L63
	tlsConfig.SetNextProtos([]string{http3.NextProtoH3})
	quicListener, err := qtls.ListenEarly(udpConn, tlsConfig, &quic.Config{
		Versions:           []quic.Version{quic.Version1},
		MaxIdleTimeout:     DefaultQuicMaxIdleTimeout,
		MaxIncomingStreams: 1 << 60,
		Allow0RTT:          true,
	})
	if err != nil {
		return err
	}
	h3Server := &http3.Server{
		Handler:     s,
		IdleTimeout: DefaultSessionTimeout,
		ConnContext: func(ctx context.Context, conn *quic.Conn) context.Context {
			setCongestionControl(s.timeFunc, conn, s.quicCongestionControl)
			ctx = contextWithWrapError(ctx, qtls.WrapError)
			return ctx
		},
	}
	s.h3Server = h3Server
	s.udpConn = udpConn
	go func() {
		sErr := h3Server.ServeListener(quicListener)
		if sErr != nil && !E.IsClosedOrCanceled(sErr) {
			s.logger.ErrorContext(s.ctx, "HTTP3 server close: ", sErr)
		}
	}()
	return nil
}

func setCongestionControl(timeFunc func() time.Time, conn *quic.Conn, name string) {
	if timeFunc == nil {
		timeFunc = time.Now
	}
	var congestionControl congestion.CongestionControl
	switch name {
	case "bbr_standard":
		congestionControl = congestion_bbr1.NewBbrSender(
			congestion_bbr1.DefaultClock{TimeFunc: timeFunc},
			congestion.ByteCount(conn.Config().InitialPacketSize),
			congestion_bbr1.InitialCongestionWindowPackets,
			congestion_bbr1.MaxCongestionWindowPackets,
		)
	case "bbr2":
		congestionControl = congestion_bbr2.NewBBR2Sender(
			congestion_bbr2.DefaultClock{TimeFunc: timeFunc},
			congestion.ByteCount(conn.Config().InitialPacketSize),
			0,
			false,
		)
	case "bbr_variant":
		congestionControl = congestion_bbr2.NewBBR2Sender(
			congestion_bbr2.DefaultClock{TimeFunc: timeFunc},
			congestion.ByteCount(conn.Config().InitialPacketSize),
			32*congestion.ByteCount(conn.Config().InitialPacketSize),
			true,
		)
	case "cubic":
		congestionControl = congestion_meta1.NewCubicSender(
			congestion_meta1.DefaultClock{TimeFunc: timeFunc},
			congestion.ByteCount(conn.Config().InitialPacketSize),
			false,
		)
	case "reno":
		congestionControl = congestion_meta1.NewCubicSender(
			congestion_meta1.DefaultClock{TimeFunc: timeFunc},
			congestion.ByteCount(conn.Config().InitialPacketSize),
			true,
		)
	case "", "bbr":
		fallthrough
	default:
		congestionControl = congestion_meta2.NewBbrSenderWithProfile(
			congestion.ByteCount(conn.Config().InitialPacketSize),
			congestion_meta2.ProfileStandard,
		)
	}
	conn.SetCongestionControl(congestionControl)
}
