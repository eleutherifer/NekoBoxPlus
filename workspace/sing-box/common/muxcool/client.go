package muxcool

import (
	"bytes"
	"context"
	"encoding/binary"
	stderrors "errors"
	"io"
	"net"
	"sync"
	"time"

	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// Client is a mux.cool multiplexing client. It manages a pool of underlying
// transport connections (workers) and multiplexes many sub-streams over them.
//
// It implements N.Dialer so it can be wrapped by common/mux.Client.
type Client struct {
	dialer         N.Dialer
	logger         logger.Logger
	maxConnections int
	maxStreams     int

	access  sync.Mutex
	workers []*worker
	closed  bool
}

// NewClientWithOptions constructs a mux.cool client from the sing-box multiplex
// options. It returns (nil, nil) when multiplex is disabled, matching the
// common/mux.NewClientWithOptions contract.
func NewClientWithOptions(dialer N.Dialer, logger logger.Logger, options option.OutboundMultiplexOptions) (*Client, error) {
	if !options.Enabled {
		return nil, nil
	}
	if options.Protocol != "mux.cool" {
		return nil, E.New("mux.cool: protocol must be \"mux.cool\", got ", options.Protocol)
	}
	maxConnections := options.MaxConnections
	if maxConnections < 0 {
		maxConnections = 0
	}
	return &Client{
		dialer:         dialer,
		logger:         logger,
		maxConnections: maxConnections,
		maxStreams:     options.MaxStreams,
	}, nil
}

// DialContext opens a TCP sub-stream to destination.
func (c *Client) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		s, err := c.openSessionWithRetry(ctx, TargetNetworkTCP, destination)
		if err != nil {
			return nil, err
		}
		streamCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
		return &sessionConn{session: s, ctx: streamCtx, cancel: cancel}, nil
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
}

// ListenPacket opens a UDP sub-stream to destination.
func (c *Client) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	s, err := c.openSessionWithRetry(ctx, TargetNetworkUDP, destination)
	if err != nil {
		return nil, err
	}
	streamCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
	return &sessionPacketConn{session: s, ctx: streamCtx, cancel: cancel}, nil
}

func (c *Client) openSessionWithRetry(ctx context.Context, transport TargetNetwork, destination M.Socksaddr) (*session, error) {
	for attempts := 0; ; attempts++ {
		w, isNew, err := c.pickWorker(ctx)
		if err != nil {
			return nil, err
		}
		s, err := w.openSession(ctx, transport, destination)
		if err != nil {
			// If this was a freshly dialled worker that failed immediately, give
			// up; otherwise retry against another worker.
			if isNew || attempts > 2 {
				return nil, err
			}
			continue
		}
		return s, nil
	}
}

// pickWorker returns a worker with free capacity, dialling a new one when
// necessary (and permitted by maxConnections). The bool result is true when the
// returned worker was just created.
func (c *Client) pickWorker(ctx context.Context) (*worker, bool, error) {
	c.access.Lock()
	defer c.access.Unlock()
	if c.closed {
		return nil, false, errClosed
	}
	// Drop closed workers.
	active := c.workers[:0]
	for _, w := range c.workers {
		if w.isClosed() {
			continue
		}
		active = append(active, w)
	}
	c.workers = active
	for _, w := range c.workers {
		if w.hasCapacity(c.maxStreams) {
			return w, false, nil
		}
	}
	// Need a new worker, unless capped.
	if c.maxConnections > 0 && len(c.workers) >= c.maxConnections {
		// All in-use and capped: reuse the least loaded one as a fallback.
		if len(c.workers) > 0 {
			return c.workers[0], false, nil
		}
		return nil, false, E.New("mux.cool: max_connections reached")
	}
	w, err := c.newWorkerLocked(ctx)
	if err != nil {
		return nil, false, err
	}
	return w, true, nil
}

func (c *Client) newWorkerLocked(ctx context.Context) (*worker, error) {
	conn, err := c.dialer.DialContext(ctx, N.NetworkTCP, Destination)
	if err != nil {
		return nil, E.Cause(err, "mux.cool: dial underlying connection")
	}
	w := newWorker(conn, c.logger)
	c.workers = append(c.workers, w)
	return w, nil
}

// Reset closes all underlying transport connections (used on interface reload).
func (c *Client) Reset() {
	c.access.Lock()
	workers := c.workers
	c.workers = nil
	c.access.Unlock()
	for _, w := range workers {
		w.close()
	}
}

// Close closes the client and all of its workers.
func (c *Client) Close() error {
	c.access.Lock()
	c.closed = true
	workers := c.workers
	c.workers = nil
	c.access.Unlock()
	for _, w := range workers {
		w.close()
	}
	return nil
}

// worker is a single underlying transport connection carrying many sessions.
type worker struct {
	conn           net.Conn
	logger         logger.Logger
	sessionManager *sessionManager

	writeMu sync.Mutex

	closeOnce sync.Once
	done      chan struct{}
	doneErr   error
}

func newWorker(conn net.Conn, logger logger.Logger) *worker {
	w := &worker{
		conn:           conn,
		logger:         logger,
		sessionManager: newSessionManager(),
		done:           make(chan struct{}),
	}
	go w.readLoop()
	return w
}

func (w *worker) isClosed() bool {
	select {
	case <-w.done:
		return true
	default:
		return false
	}
}

func (w *worker) close() {
	w.closeOnce.Do(func() {
		close(w.done)
		_ = w.conn.Close()
		w.sessionManager.closeAll()
	})
}

func (w *worker) closeWithError(err error) {
	w.closeOnce.Do(func() {
		w.doneErr = err
		close(w.done)
		_ = w.conn.Close()
		w.sessionManager.closeAll()
	})
}

// openSession allocates a session id, writes the New frame, and returns the
// session so the caller can wrap it as a net.Conn or net.PacketConn.
func (w *worker) openSession(ctx context.Context, transport TargetNetwork, dest M.Socksaddr) (*session, error) {
	if w.isClosed() {
		return nil, errClosed
	}
	s := newSession(0, w, transport, dest)
	id, ok := w.sessionManager.allocate(s)
	if !ok {
		return nil, E.New("mux.cool: no session IDs available")
	}
	s.id = id

	if err := w.writeNewFrame(s); err != nil {
		w.sessionManager.remove(id)
		return nil, err
	}
	return s, nil
}

// readLoop demultiplexes incoming frames into sessions. The underlying conn is
// single-reader: only this loop reads from it.
func (w *worker) readLoop() {
	for {
		meta, err := readMeta(w.conn)
		if err != nil {
			if !stderrors.Is(err, io.EOF) {
				w.logger.Debug("mux.cool: read error: ", err)
			}
			w.closeWithError(err)
			return
		}
		switch meta.SessionStatus {
		case SessionStatusKeepAlive:
			if optionHas(meta.Option, OptionData) {
				if err := discardData(w.conn); err != nil {
					w.closeWithError(err)
					return
				}
			}
		case SessionStatusNew:
			// A server does not normally open sessions toward the client; treat
			// any inbound New like a keep-alive: discard its data if present.
			if optionHas(meta.Option, OptionData) {
				if err := discardData(w.conn); err != nil {
					w.closeWithError(err)
					return
				}
			}
		case SessionStatusEnd:
			if s, ok := w.sessionManager.get(meta.SessionID); ok {
				s.deliverEOF()
				w.sessionManager.remove(meta.SessionID)
			}
			if optionHas(meta.Option, OptionData) {
				if err := discardData(w.conn); err != nil {
					w.closeWithError(err)
					return
				}
			}
		case SessionStatusKeep:
			if err := w.handleKeep(meta); err != nil {
				w.closeWithError(err)
				return
			}
		default:
			w.closeWithError(E.New("mux.cool: unknown status: ", meta.SessionStatus))
			return
		}
	}
}

// handleKeep reads the single length-prefixed payload following a Keep frame
// and delivers it to the owning session.
func (w *worker) handleKeep(meta *FrameMetadata) error {
	if !optionHas(meta.Option, OptionData) {
		return nil
	}
	payload, err := readData(w.conn)
	if err != nil {
		return err
	}
	s, ok := w.sessionManager.get(meta.SessionID)
	if !ok {
		// Unknown session: notify peer and discard.
		_ = w.writeEndFrame(meta.SessionID)
		return nil
	}
	s.deliver(payload)
	return nil
}

// --- frame writers ---

func (w *worker) writeNewFrame(s *session) error {
	meta := FrameMetadata{
		SessionID:     s.id,
		SessionStatus: SessionStatusNew,
		Target:        s.dest,
	}
	return w.writeRawMeta(&meta, s.transport, false, nil)
}

// writeStreamData sends TCP payload as one or more Keep+OptionData frames,
// chunking at maxChunkSize.
func (w *worker) writeStreamData(s *session, p []byte) error {
	if w.isClosed() {
		return errClosed
	}
	for len(p) > 0 {
		chunk := p
		if len(chunk) > maxChunkSize {
			chunk = chunk[:maxChunkSize]
		}
		meta := FrameMetadata{
			SessionID:     s.id,
			SessionStatus: SessionStatusKeep,
			Option:        OptionData,
			Target:        s.dest,
		}
		if err := w.writeRawMeta(&meta, TargetNetworkTCP, false, chunk); err != nil {
			return err
		}
		p = p[len(chunk):]
	}
	return nil
}

// writePacketData sends a single UDP datagram as one Keep+OptionData frame.
func (w *worker) writePacketData(s *session, p []byte) error {
	if w.isClosed() {
		return errClosed
	}
	if len(p) > maxPacketSize {
		return E.New("mux.cool: packet too large: ", len(p))
	}
	meta := FrameMetadata{
		SessionID:     s.id,
		SessionStatus: SessionStatusKeep,
		Option:        OptionData,
		Target:        s.dest,
	}
	// UDP Keep frames must re-encode the address so the server can route.
	return w.writeRawMeta(&meta, TargetNetworkUDP, true, p)
}

func (w *worker) writeEndFrame(id uint16) error {
	if w.isClosed() {
		return errClosed
	}
	// Best-effort End frame: bound the write so a slow or stuck peer cannot hang
	// session close. The deadline is scoped to this write only and restored
	// afterwards so other writers on the shared connection are unaffected.
	_ = w.conn.SetWriteDeadline(time.Now().Add(endFrameWriteTimeout))
	defer func() { _ = w.conn.SetWriteDeadline(time.Time{}) }()
	meta := FrameMetadata{
		SessionID:     id,
		SessionStatus: SessionStatusEnd,
	}
	return w.writeRawMeta(&meta, TargetNetworkTCP, false, nil)
}

// writeRawMeta serialises one complete frame under the write mutex. data may be
// nil (meta-only frame) or a single payload (sets OptionData framing).
func (w *worker) writeRawMeta(meta *FrameMetadata, network TargetNetwork, includeUDPAddr bool, data []byte) error {
	w.writeMu.Lock()
	defer w.writeMu.Unlock()

	if data != nil {
		meta.Option |= OptionData
	}

	// Build the metadata body first to know its length.
	var body bytes.Buffer
	if err := meta.writeMetaTo(&body, network, includeUDPAddr); err != nil {
		return err
	}
	metaLen := body.Len()
	if metaLen > maxMetaLen {
		return E.New("mux.cool: metadata too large: ", metaLen)
	}

	var buf bytes.Buffer
	var lenBuf [2]byte
	binary.BigEndian.PutUint16(lenBuf[:], uint16(metaLen))
	buf.Write(lenBuf[:])
	buf.Write(body.Bytes())
	if data != nil {
		var dataLen [2]byte
		binary.BigEndian.PutUint16(dataLen[:], uint16(len(data)))
		buf.Write(dataLen[:])
		buf.Write(data)
	}
	return writeAll(w.conn, buf.Bytes())
}

func (w *worker) hasCapacity(maxStreams int) bool {
	size := w.sessionManager.size()
	return size < maxSessionID && (maxStreams <= 0 || size < maxStreams)
}

func writeAll(writer io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := writer.Write(data)
		if n > 0 {
			data = data[n:]
		}
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
	}
	return nil
}

// discardData reads and discards a single length-prefixed payload.
func discardData(r io.Reader) error {
	_, err := readData(r)
	return err
}

// readData reads one [2-byte len][payload] segment.
func readData(r io.Reader) ([]byte, error) {
	var lenBuf [2]byte
	if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
		return nil, err
	}
	size := binary.BigEndian.Uint16(lenBuf[:])
	if int(size) > maxPacketSize {
		return nil, E.New("mux.cool: data segment too large: ", size)
	}
	payload := make([]byte, size)
	if size == 0 {
		return payload, nil
	}
	if _, err := io.ReadFull(r, payload); err != nil {
		return nil, err
	}
	return payload, nil
}

const (
	maxChunkSize         = 8 * 1024
	maxPacketSize        = 65535
	endFrameWriteTimeout = 2 * time.Second
)

// Compile-time interface assertions.
var (
	_ N.Dialer = (*Client)(nil)
)
