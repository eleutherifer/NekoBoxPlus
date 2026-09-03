package muxcool

import (
	"context"
	"io"
	"net"
	"sync"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
)

// session is one multiplexed sub-stream over a mux.cool worker connection.
// It carries the destination negotiated in the opening New frame and feeds
// downstream data (received by the worker's read loop) into a read queue.
type session struct {
	id        uint16
	worker    *worker
	transport TargetNetwork
	dest      M.Socksaddr

	closed bool
	mu     sync.Mutex

	// Downstream delivery. The worker's read loop pushes whole payloads here;
	// the per-session conn reads them back out in order.
	readReady chan struct{}
	readQueue [][]byte
	eof       bool
}

func newSession(id uint16, w *worker, transport TargetNetwork, dest M.Socksaddr) *session {
	s := &session{
		id:        id,
		worker:    w,
		transport: transport,
		dest:      dest,
	}
	s.readReady = make(chan struct{}, 1)
	return s
}

// deliver is called by the worker read loop to forward a downstream payload
// (one frame's data) to the session consumer. For TCP each payload is appended
// to the stream; for UDP each payload is one packet.
func (s *session) deliver(data []byte) {
	s.mu.Lock()
	if s.eof {
		s.mu.Unlock()
		return
	}
	s.readQueue = append(s.readQueue, data)
	s.mu.Unlock()
	s.notifyReader()
}

// deliverEOF marks the session's read side as closed (End frame received or
// connection lost).
func (s *session) deliverEOF() {
	s.mu.Lock()
	s.eof = true
	s.mu.Unlock()
	s.notifyReader()
}

func (s *session) notifyReader() {
	select {
	case s.readReady <- struct{}{}:
	default:
	}
}

// closeLockedUpstream sends an End frame to the server. It is idempotent.
func (s *session) closeUpstream() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	id := s.id
	w := s.worker
	s.mu.Unlock()
	if w != nil {
		_ = w.writeEndFrame(id)
		w.sessionManager.remove(id)
	}
}

// nextChunk blocks until at least one byte of downstream data is available (or
// EOF). It returns the next chunk to hand to a TCP Read. Callers must hold no
// lock on entry.
func (s *session) nextChunk(ctx context.Context) ([]byte, bool, error) {
	for {
		s.mu.Lock()
		if len(s.readQueue) > 0 {
			chunk := s.readQueue[0]
			s.readQueue[0] = nil
			s.readQueue = s.readQueue[1:]
			s.mu.Unlock()
			return chunk, true, nil
		}
		if s.eof {
			s.mu.Unlock()
			return nil, false, io.EOF
		}
		s.mu.Unlock()

		select {
		case <-ctx.Done():
			return nil, false, ctx.Err()
		case <-s.readReady:
		}
	}
}

func (s *session) writeStreamData(p []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return errClosed
	}
	return s.worker.writeStreamData(s, p)
}

func (s *session) writePacketData(p []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return errClosed
	}
	return s.worker.writePacketData(s, p)
}

// sessionConn adapts a session to net.Conn for TCP streams.
type sessionConn struct {
	session *session
	ctx     context.Context
	cancel  context.CancelFunc

	mu     sync.Mutex
	left   []byte
	closed bool
}

func (c *sessionConn) Read(p []byte) (int, error) {
	c.mu.Lock()
	if len(c.left) > 0 {
		n := copy(p, c.left)
		c.left = c.left[n:]
		c.mu.Unlock()
		return n, nil
	}
	c.mu.Unlock()
	chunk, ok, err := c.session.nextChunk(c.ctx)
	if err != nil {
		return 0, err
	}
	if !ok {
		return 0, io.EOF
	}
	n := copy(p, chunk)
	if n < len(chunk) {
		c.mu.Lock()
		c.left = append(c.left[:0], chunk[n:]...)
		c.mu.Unlock()
	}
	return n, nil
}

func (c *sessionConn) Write(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	err := c.session.writeStreamData(p)
	if err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *sessionConn) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	c.mu.Unlock()
	c.cancel()
	// Wake any reader parked in nextChunk so it observes the cancellation.
	c.session.deliverEOF()
	c.session.closeUpstream()
	return nil
}

func (c *sessionConn) LocalAddr() net.Addr                { return dummyAddr{} }
func (c *sessionConn) RemoteAddr() net.Addr               { return dummyAddr{} }
func (c *sessionConn) SetDeadline(t time.Time) error      { return nil }
func (c *sessionConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *sessionConn) SetWriteDeadline(t time.Time) error { return nil }

// sessionPacketConn adapts a UDP session to net.PacketConn. Each downstream
// frame is one datagram.
type sessionPacketConn struct {
	session *session
	ctx     context.Context
	cancel  context.CancelFunc

	mu     sync.Mutex
	closed bool
}

func (c *sessionPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	chunk, ok, err := c.session.nextChunk(c.ctx)
	if err != nil {
		return 0, nil, err
	}
	if !ok {
		return 0, nil, io.EOF
	}
	n := copy(p, chunk)
	return n, c.session.dest, nil
}

func (c *sessionPacketConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	err := c.session.writePacketData(p)
	if err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *sessionPacketConn) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	c.mu.Unlock()
	c.cancel()
	// Wake any reader parked in nextChunk so it observes the cancellation.
	c.session.deliverEOF()
	c.session.closeUpstream()
	return nil
}

func (c *sessionPacketConn) LocalAddr() net.Addr { return dummyAddr{} }

func (c *sessionPacketConn) SetDeadline(t time.Time) error      { return nil }
func (c *sessionPacketConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *sessionPacketConn) SetWriteDeadline(t time.Time) error { return nil }

type dummyAddr struct{}

func (dummyAddr) Network() string { return "mux.cool" }
func (dummyAddr) String() string  { return "mux.cool" }

// sessionManager owns the set of active sessions on a worker.
type sessionManager struct {
	mu       sync.Mutex
	sessions map[uint16]*session
	nextID   uint16
}

func newSessionManager() *sessionManager {
	return &sessionManager{sessions: make(map[uint16]*session)}
}

// allocate reserves a new session id and registers s. The returned id is
// always non-zero (mux.cool uses 0 as a sentinel in some peers) and
// monotonically increasing within the worker.
func (m *sessionManager) allocate(s *session) (uint16, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.sessions) >= maxSessionID {
		return 0, false
	}
	for range maxSessionID {
		m.nextID++
		if m.nextID == 0 {
			m.nextID = 1
		}
		if _, exists := m.sessions[m.nextID]; !exists {
			s.id = m.nextID
			m.sessions[s.id] = s
			return s.id, true
		}
	}
	return 0, false
}

func (m *sessionManager) get(id uint16) (*session, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s, ok := m.sessions[id]
	return s, ok
}

func (m *sessionManager) remove(id uint16) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.sessions, id)
}

func (m *sessionManager) size() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.sessions)
}

func (m *sessionManager) closeAll() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, s := range m.sessions {
		s.deliverEOF()
	}
	m.sessions = map[uint16]*session{}
}

// errClosed is returned when operating on a closed worker.
var errClosed = E.New("mux.cool: connection closed")

const maxSessionID = int(^uint16(0))
