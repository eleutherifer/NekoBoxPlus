package xhttp

import (
	"bufio"
	"errors"
	"io"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/sagernet/sing-box/common/xray/signal/done"
)

type splitConn struct {
	writer         io.WriteCloser
	reader         io.ReadCloser
	remoteAddr     net.Addr
	localAddr      net.Addr
	onClose        func()
	closeWriteOnce sync.Once
	closeReadOnce  sync.Once
	closeOnce      sync.Once
}

type idleTimeoutWriteCloser struct {
	writer  io.WriteCloser
	timer   *time.Timer
	access  sync.Mutex
	closed  bool
	timeout time.Duration
}

func newIdleTimeoutWriteCloser(writer io.WriteCloser, timeout time.Duration) io.WriteCloser {
	if writer == nil || timeout <= 0 {
		return writer
	}
	closer := &idleTimeoutWriteCloser{
		writer:  writer,
		timeout: timeout,
	}
	closer.access.Lock()
	closer.timer = time.AfterFunc(timeout, func() {
		closeSilently(closer)
	})
	closer.access.Unlock()
	return closer
}

func (c *idleTimeoutWriteCloser) Write(b []byte) (int, error) {
	c.access.Lock()
	if c.closed {
		c.access.Unlock()
		return 0, io.ErrClosedPipe
	}
	c.timer.Reset(c.timeout)
	c.access.Unlock()

	n, err := c.writer.Write(b)
	if n > 0 && err == nil {
		c.access.Lock()
		if !c.closed {
			c.timer.Reset(c.timeout)
		}
		c.access.Unlock()
	}
	return n, err
}

func (c *idleTimeoutWriteCloser) Close() error {
	c.access.Lock()
	if c.closed {
		c.access.Unlock()
		return nil
	}
	c.closed = true
	c.timer.Stop()
	c.access.Unlock()
	return c.writer.Close()
}

func (c *splitConn) Write(b []byte) (int, error) {
	return c.writer.Write(b)
}

func (c *splitConn) Read(b []byte) (int, error) {
	return c.reader.Read(b)
}

func (c *splitConn) CloseWrite() error {
	var err error
	c.closeWriteOnce.Do(func() {
		if c.writer != nil {
			err = c.writer.Close()
		}
	})
	return err
}

func (c *splitConn) closeRead() error {
	var err error
	c.closeReadOnce.Do(func() {
		if c.reader != nil {
			err = c.reader.Close()
		}
	})
	return err
}

func (c *splitConn) Close() error {
	c.closeOnce.Do(func() {
		if c.onClose != nil {
			c.onClose()
		}
	})
	return errors.Join(c.CloseWrite(), c.closeRead())
}

func (c *splitConn) LocalAddr() net.Addr {
	return c.localAddr
}

func (c *splitConn) RemoteAddr() net.Addr {
	return c.remoteAddr
}

func (c *splitConn) SetDeadline(t time.Time) error {
	// TODO cannot do anything useful
	return nil
}

func (c *splitConn) SetReadDeadline(t time.Time) error {
	// TODO cannot do anything useful
	return nil
}

func (c *splitConn) SetWriteDeadline(t time.Time) error {
	// TODO cannot do anything useful
	return nil
}

type H1Conn struct {
	UnreadedResponsesCount int
	RespBufReader          *bufio.Reader
	net.Conn
}

func NewH1Conn(conn net.Conn) *H1Conn {
	return &H1Conn{
		RespBufReader: bufio.NewReader(conn),
		Conn:          conn,
	}
}

type h1UploadPool struct {
	access sync.Mutex
	conns  []*H1Conn
	all    map[*H1Conn]struct{}
	closed bool
}

func newH1UploadPool() *h1UploadPool {
	return &h1UploadPool{
		all: make(map[*H1Conn]struct{}),
	}
}

func (p *h1UploadPool) Get() *H1Conn {
	p.access.Lock()
	defer p.access.Unlock()
	if p.closed {
		return nil
	}
	if len(p.conns) == 0 {
		return nil
	}
	index := len(p.conns) - 1
	conn := p.conns[index]
	p.conns[index] = nil
	p.conns = p.conns[:index]
	return conn
}

func (p *h1UploadPool) Track(conn *H1Conn) bool {
	if conn == nil {
		return false
	}
	p.access.Lock()
	defer p.access.Unlock()
	if p.closed {
		return false
	}
	p.all[conn] = struct{}{}
	return true
}

func (p *h1UploadPool) Put(conn *H1Conn) {
	if conn == nil {
		return
	}
	p.access.Lock()
	if p.closed {
		delete(p.all, conn)
		p.access.Unlock()
		closeSilently(conn)
		return
	}
	p.all[conn] = struct{}{}
	p.conns = append(p.conns, conn)
	p.access.Unlock()
}

func (p *h1UploadPool) Discard(conn *H1Conn) error {
	if conn == nil {
		return nil
	}
	p.access.Lock()
	delete(p.all, conn)
	p.access.Unlock()
	return conn.Close()
}

func (p *h1UploadPool) Close() error {
	p.access.Lock()
	conns := make([]*H1Conn, 0, len(p.all))
	for conn := range p.all {
		conns = append(conns, conn)
	}
	p.conns = nil
	p.all = nil
	p.closed = true
	p.access.Unlock()
	var err error
	for _, conn := range conns {
		err = errors.Join(err, conn.Close())
	}
	return err
}

type httpServerConn struct {
	sync.Mutex
	*done.Instance
	io.Reader // no need to Close request.Body
	http.ResponseWriter
}

func (c *httpServerConn) Write(b []byte) (int, error) {
	c.Lock()
	defer c.Unlock()
	if c.Done() {
		return 0, io.ErrClosedPipe
	}
	n, err := c.ResponseWriter.Write(b)
	if err == nil {
		flusher, ok := c.ResponseWriter.(http.Flusher)
		if !ok {
			return n, http.ErrNotSupported
		}
		flusher.Flush()
	}
	return n, err
}

func (c *httpServerConn) Close() error {
	c.Lock()
	defer c.Unlock()
	return c.Instance.Close()
}
