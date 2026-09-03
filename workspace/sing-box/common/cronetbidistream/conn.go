package cronetbidistream

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/cronet-go"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/pipe"
)

const destroyDelay = 100 * time.Millisecond

type BidirectionalConn struct {
	ctx              context.Context
	stream           cronet.BidirectionalStream
	logger           logger.ContextLogger
	cancelled        atomic.Bool
	readWaitHeaders  bool
	writeWaitHeaders bool
	access           sync.Mutex
	close            chan struct{}
	done             chan struct{}
	destroyed        chan struct{}
	err              error
	ready            chan struct{}
	handshake        chan struct{}
	read             chan readResult
	write            chan struct{}
	headers          map[string]string
	readSemaphore    chan struct{}
	writeSemaphore   chan struct{}
	readDone         chan struct{}
	writeDone        chan struct{}
	doneOnce         sync.Once
	readDoneOnce     sync.Once
	writeDoneOnce    sync.Once
	onTerminate      func()
	readDeadline     pipe.Deadline
	writeDeadline    pipe.Deadline
	readBuffer       cronet.Buffer
	readDest         []byte
	writeBuffer      cronet.Buffer
	readEOF          bool
	writeClosed      bool
	stopContext      func() bool
}

type readResult struct {
	n   int
	err error
}

func CreateConn(e cronet.StreamEngine, ctx context.Context, l logger.ContextLogger, readWaitHeaders bool, writeWaitHeaders bool) *BidirectionalConn {
	conn := &BidirectionalConn{
		ctx:              ctx,
		logger:           l,
		readWaitHeaders:  readWaitHeaders,
		writeWaitHeaders: writeWaitHeaders,
		close:            make(chan struct{}),
		done:             make(chan struct{}),
		destroyed:        make(chan struct{}),
		ready:            make(chan struct{}),
		handshake:        make(chan struct{}),
		read:             make(chan readResult),
		write:            make(chan struct{}),
		readSemaphore:    make(chan struct{}, 1),
		writeSemaphore:   make(chan struct{}, 1),
		readDone:         make(chan struct{}),
		writeDone:        make(chan struct{}),
		readDeadline:     pipe.MakeDeadline(),
		writeDeadline:    pipe.MakeDeadline(),
	}
	conn.readSemaphore <- struct{}{}
	conn.writeSemaphore <- struct{}{}
	conn.stream = e.CreateStream(&bidirectionalHandler{BidirectionalConn: conn})
	if ctx != nil {
		conn.stopContext = context.AfterFunc(ctx, func() {
			_ = conn.Close()
		})
	}
	return conn
}

func (c *BidirectionalConn) waitReady(waitHeaders bool, deadline <-chan struct{}) error {
	var gate <-chan struct{}
	if waitHeaders {
		gate = c.handshake
	} else {
		gate = c.ready
	}
	select {
	case <-gate:
		return nil
	case <-c.done:
		return c.err
	case <-c.close:
		return net.ErrClosed
	case <-deadline:
		return os.ErrDeadlineExceeded
	}
}

func (c *BidirectionalConn) Start(method string, url string, headers map[string]string, priority int, endOfStream bool) (err error) {
	defer recoverCronetPanic(&err)
	c.access.Lock()
	if !safeStreamStart(c.stream, method, url, headers, priority, endOfStream) {
		c.access.Unlock()
		c.terminate(os.ErrInvalid)
		return os.ErrInvalid
	}
	c.writeClosed = endOfStream
	c.access.Unlock()
	return nil
}

func (c *BidirectionalConn) markTerminatedLocked(err error) (onTerminate func(), readBuffer cronet.Buffer, writeBuffer cronet.Buffer, marked bool) {
	c.readDoneOnce.Do(func() { close(c.readDone) })
	c.writeDoneOnce.Do(func() { close(c.writeDone) })
	c.cancelled.Store(true)
	c.doneOnce.Do(func() {
		c.err = err
		readBuffer = c.readBuffer
		writeBuffer = c.writeBuffer
		c.readBuffer = cronet.Buffer{}
		c.readDest = nil
		c.writeBuffer = cronet.Buffer{}
		if c.stopContext != nil {
			c.stopContext()
			c.stopContext = nil
		}
		close(c.done)
		onTerminate = c.onTerminate
		marked = true
	})
	return
}

func (c *BidirectionalConn) terminate(err error) {
	c.access.Lock()
	onTerminate, readBuffer, writeBuffer, marked := c.markTerminatedLocked(err)
	c.access.Unlock()

	if onTerminate != nil {
		onTerminate()
	}
	if marked {
		safeDestroyCronetBuffer(readBuffer)
		safeDestroyCronetBuffer(writeBuffer)
		destroyStreamAsync(c.stream, c.destroyed)
	}
}

func (c *BidirectionalConn) Read(p []byte) (n int, err error) {
	defer recoverCronetPanic(&err)
	if len(p) == 0 {
		return 0, nil
	}

	select {
	case <-c.close:
		return 0, net.ErrClosed
	case <-c.done:
		return 0, c.err
	case <-c.readSemaphore:
	}
	defer func() { c.readSemaphore <- struct{}{} }()

	if err := c.waitReady(c.readWaitHeaders, c.readDeadline.Wait()); err != nil {
		return 0, err
	}

	buffer := cronet.NewBuffer()
	buffer.InitWithAlloc(int64(len(p)))

	c.access.Lock()
	select {
	case <-c.close:
		c.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return 0, net.ErrClosed
	case <-c.done:
		c.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return 0, c.err
	default:
	}
	if c.readEOF {
		c.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return 0, io.EOF
	}
	if c.readBuffer != (cronet.Buffer{}) {
		c.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return 0, E.New("concurrent Cronet bidirectional stream reads are unsupported")
	}
	c.readBuffer = buffer
	c.readDest = p
	if !safeStreamRead(c.stream, buffer.DataSlice()) {
		c.readBuffer = cronet.Buffer{}
		c.readDest = nil
		c.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return 0, os.ErrInvalid
	}
	c.access.Unlock()

	select {
	case result := <-c.read:
		return result.n, result.err
	case <-c.readDeadline.Wait():
		if c.cancelled.CompareAndSwap(false, true) {
			safeStreamCancel(c.stream)
		}
		for {
			select {
			case <-c.read:
			case <-c.done:
				return 0, os.ErrDeadlineExceeded
			}
		}
	case <-c.done:
		<-c.readDone
		return 0, c.err
	case <-c.close:
		<-c.readDone
		return 0, net.ErrClosed
	}
}

func (c *BidirectionalConn) Write(p []byte) (n int, err error) {
	defer recoverCronetPanic(&err)
	if len(p) == 0 {
		return 0, nil
	}
	if err := c.writeStream(p, false); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *BidirectionalConn) CloseWrite() (err error) {
	defer recoverCronetPanic(&err)
	return c.writeStream(nil, true)
}

func (c *BidirectionalConn) writeStream(p []byte, endOfStream bool) error {
	select {
	case <-c.close:
		return net.ErrClosed
	case <-c.done:
		return c.err
	case <-c.writeSemaphore:
	}
	defer func() { c.writeSemaphore <- struct{}{} }()

	if err := c.waitReady(c.writeWaitHeaders, c.writeDeadline.Wait()); err != nil {
		return err
	}

	var buffer cronet.Buffer
	var data []byte
	if len(p) > 0 {
		buffer = cronet.NewBuffer()
		buffer.InitWithAlloc(int64(len(p)))
		data = buffer.DataSlice()
		copy(data, p)
	}

	c.access.Lock()
	select {
	case <-c.close:
		c.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return net.ErrClosed
	case <-c.done:
		c.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return c.err
	default:
	}
	if c.writeClosed {
		c.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return net.ErrClosed
	}
	if c.writeBuffer != (cronet.Buffer{}) {
		c.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return E.New("concurrent Cronet bidirectional stream writes are unsupported")
	}
	c.writeBuffer = buffer
	if !safeStreamWrite(c.stream, data, endOfStream) {
		c.writeBuffer = cronet.Buffer{}
		c.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return os.ErrInvalid
	}
	if endOfStream {
		c.writeClosed = true
	}
	c.access.Unlock()

	select {
	case <-c.write:
		return nil
	case <-c.writeDeadline.Wait():
		if c.cancelled.CompareAndSwap(false, true) {
			safeStreamCancel(c.stream)
		}
		for {
			select {
			case <-c.write:
			case <-c.done:
				return os.ErrDeadlineExceeded
			}
		}
	case <-c.done:
		<-c.writeDone
		return c.err
	case <-c.close:
		<-c.writeDone
		return net.ErrClosed
	}
}

func (c *BidirectionalConn) Done() <-chan struct{} {
	return c.done
}

// Destroyed is closed after destruction of the native stream has been issued.
// Cronet destroys streams asynchronously, but engine shutdown must not begin
// before the destroy request has been submitted.
func (c *BidirectionalConn) Destroyed() <-chan struct{} {
	return c.destroyed
}

func (c *BidirectionalConn) SetOnTerminate(fn func()) {
	c.access.Lock()
	select {
	case <-c.done:
		c.access.Unlock()
		fn()
		return
	default:
	}
	c.onTerminate = fn
	c.access.Unlock()
}

func (c *BidirectionalConn) Err() error {
	c.access.Lock()
	defer c.access.Unlock()
	return c.err
}

func (c *BidirectionalConn) Close() error {
	c.access.Lock()

	select {
	case <-c.close:
		c.access.Unlock()
		return net.ErrClosed
	case <-c.done:
		c.access.Unlock()
		return nil
	default:
	}

	close(c.close)
	if c.stopContext != nil {
		c.stopContext()
		c.stopContext = nil
	}
	c.access.Unlock()

	if c.cancelled.CompareAndSwap(false, true) {
		safeStreamCancel(c.stream)
	}
	return nil
}

func (c *BidirectionalConn) LocalAddr() net.Addr {
	return nil
}

func (c *BidirectionalConn) RemoteAddr() net.Addr {
	return nil
}

func (c *BidirectionalConn) SetDeadline(t time.Time) error {
	c.SetReadDeadline(t)
	c.SetWriteDeadline(t)
	return nil
}

func (c *BidirectionalConn) SetReadDeadline(t time.Time) error {
	c.readDeadline.Set(t)
	return nil
}

func (c *BidirectionalConn) SetWriteDeadline(t time.Time) error {
	c.writeDeadline.Set(t)
	return nil
}

func (c *BidirectionalConn) WaitForHeaders() (map[string]string, error) {
	select {
	case <-c.handshake:
		return c.headers, nil
	case <-c.done:
		return nil, c.err
	case <-c.close:
		return nil, net.ErrClosed
	}
}

func (c *BidirectionalConn) WaitForHeadersContext(ctx context.Context) (map[string]string, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-c.handshake:
		return c.headers, nil
	case <-c.done:
		return nil, c.err
	case <-c.close:
		return nil, net.ErrClosed
	}
}

type bidirectionalHandler struct {
	*BidirectionalConn
	readyOnce     sync.Once
	handshakeOnce sync.Once
}

func (c *bidirectionalHandler) OnStreamReady(stream cronet.BidirectionalStream) {
	c.readyOnce.Do(func() { close(c.ready) })
}

func (c *bidirectionalHandler) OnResponseHeadersReceived(stream cronet.BidirectionalStream, headers map[string]string, negotiatedProtocol string) {
	c.access.Lock()
	c.headers = headers
	c.access.Unlock()
	c.logger.DebugContext(c.ctx, "response received, protocol: ", negotiatedProtocol, ", status: ", headers[":status"])
	c.handshakeOnce.Do(func() { close(c.handshake) })
}

func (c *bidirectionalHandler) OnReadCompleted(stream cronet.BidirectionalStream, bytesRead int) {
	defer recoverCronetPanic(nil)

	var result readResult
	var buffer cronet.Buffer

	c.access.Lock()
	buffer = c.readBuffer
	if buffer == (cronet.Buffer{}) {
		result.err = net.ErrClosed
	} else if bytesRead == 0 {
		c.readEOF = true
		result.err = io.EOF
	} else {
		data := buffer.DataSlice()
		if bytesRead > len(data) || bytesRead > len(c.readDest) {
			result.err = E.New("Cronet bidirectional stream read larger than destination buffer: ", bytesRead)
		} else {
			result.n = bytesRead
			copy(c.readDest, data[:bytesRead])
		}
	}
	if c.readBuffer == buffer {
		c.readBuffer = cronet.Buffer{}
		c.readDest = nil
	}
	c.access.Unlock()

	safeDestroyCronetBuffer(buffer)

	if result.err != nil && result.err != io.EOF && result.err != net.ErrClosed {
		c.terminate(result.err)
		return
	}

	select {
	case <-c.close:
		c.readDoneOnce.Do(func() { close(c.readDone) })
	case <-c.done:
		c.readDoneOnce.Do(func() { close(c.readDone) })
	case c.read <- result:
	}
}

func (c *bidirectionalHandler) OnWriteCompleted(stream cronet.BidirectionalStream) {
	defer recoverCronetPanic(nil)

	c.access.Lock()
	buffer := c.writeBuffer
	c.writeBuffer = cronet.Buffer{}
	c.access.Unlock()

	safeDestroyCronetBuffer(buffer)

	select {
	case <-c.close:
		c.writeDoneOnce.Do(func() { close(c.writeDone) })
	case <-c.done:
		c.writeDoneOnce.Do(func() { close(c.writeDone) })
	case c.write <- struct{}{}:
	}
}

func (c *bidirectionalHandler) OnResponseTrailersReceived(stream cronet.BidirectionalStream, trailers map[string]string) {
}

func (c *bidirectionalHandler) OnSucceeded(stream cronet.BidirectionalStream) {
	defer recoverCronetPanic(nil)
	c.terminate(io.EOF)
}

func (c *bidirectionalHandler) OnFailed(stream cronet.BidirectionalStream, netError int) {
	defer recoverCronetPanic(nil)
	c.logger.WarnContext(c.ctx, "stream failed: ", cronet.NetError(netError))
	c.terminate(cronet.NetError(netError))
}

func (c *bidirectionalHandler) OnCanceled(stream cronet.BidirectionalStream) {
	defer recoverCronetPanic(nil)
	c.logger.DebugContext(c.ctx, "stream canceled")
	c.terminate(context.Canceled)
}

func destroyStreamAsync(stream cronet.BidirectionalStream, destroyed chan<- struct{}) {
	go func() {
		defer close(destroyed)
		if stream == (cronet.BidirectionalStream{}) {
			return
		}
		time.Sleep(destroyDelay)
		safeStreamDestroy(stream)
	}()
}

func recoverCronetPanic(errp *error) {
	if value := recover(); value != nil && errp != nil && *errp == nil {
		*errp = E.New("Cronet native bridge panic: ", fmt.Sprint(value))
	}
}

func safeDestroyCronetBuffer(buffer cronet.Buffer) {
	defer recoverCronetPanic(nil)
	if buffer != (cronet.Buffer{}) {
		buffer.Destroy()
	}
}

func safeStreamStart(stream cronet.BidirectionalStream, method string, url string, headers map[string]string, priority int, endOfStream bool) (ok bool) {
	defer recoverCronetPanic(nil)
	return stream.Start(method, url, headers, priority, endOfStream)
}

func safeStreamRead(stream cronet.BidirectionalStream, buffer []byte) (ok bool) {
	ok = true
	defer func() {
		if recover() != nil {
			ok = false
		}
	}()
	_ = stream.Read(buffer)
	return ok
}

func safeStreamWrite(stream cronet.BidirectionalStream, buffer []byte, endOfStream bool) (ok bool) {
	ok = true
	defer func() {
		if recover() != nil {
			ok = false
		}
	}()
	_ = stream.Write(buffer, endOfStream)
	return ok
}

func safeStreamCancel(stream cronet.BidirectionalStream) {
	defer recoverCronetPanic(nil)
	if stream != (cronet.BidirectionalStream{}) {
		stream.Cancel()
	}
}

func safeStreamDestroy(stream cronet.BidirectionalStream) {
	defer recoverCronetPanic(nil)
	if stream != (cronet.BidirectionalStream{}) {
		stream.Destroy()
	}
}
