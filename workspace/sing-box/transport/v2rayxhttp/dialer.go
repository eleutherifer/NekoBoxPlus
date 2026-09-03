package xhttp

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/sagernet/sing-box/common/vision"
	common "github.com/sagernet/sing-box/common/xray"
	"github.com/sagernet/sing-box/common/xray/signal/done"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"golang.org/x/net/http2"
)

// interface to abstract between use of browser dialer, vs net/http
type DialerClient interface {
	IsClosed() bool
	Close() error

	OpenStream(context.Context, string, string, io.Reader, bool) (io.ReadCloser, net.Addr, net.Addr, error)
	// PostPacket takes ownership of body and closes it before returning or transfers ownership to the HTTP transport.
	PostPacket(context.Context, string, string, string, io.Reader, int64) error
}

type acceptedReadCloser interface {
	WaitAccepted(context.Context) error
}

// implements xhttp.DialerClient in terms of direct network connections
type DefaultDialerClient struct {
	options     *option.V2RayXHTTPBaseOptions
	client      *http.Client
	closed      atomic.Bool
	closeOnce   sync.Once
	closeErr    error
	httpVersion string
	rawConns    *rawConnTracker
	// pool of net.Conn, created using dialUploadConn
	uploadRawPool  *h1UploadPool
	dialUploadConn func(ctxInner context.Context) (net.Conn, error)
}

type readDoneCloser struct {
	io.ReadCloser
	done chan struct{}
	once sync.Once
}

func newReadDoneCloser(reader io.Reader) *readDoneCloser {
	return &readDoneCloser{
		ReadCloser: asReadCloser(reader),
		done:       make(chan struct{}),
	}
}

func (r *readDoneCloser) Done() <-chan struct{} {
	return r.done
}

func (r *readDoneCloser) markDone() {
	r.once.Do(func() {
		close(r.done)
	})
}

func (r *readDoneCloser) Read(p []byte) (int, error) {
	n, err := r.ReadCloser.Read(p)
	if err != nil {
		r.markDone()
	}
	return n, err
}

func (r *readDoneCloser) Close() error {
	err := r.ReadCloser.Close()
	r.markDone()
	return err
}

type cancelOnCloseReadCloser struct {
	io.ReadCloser
	requestBody *readDoneCloser
	cancel      context.CancelFunc
}

func (c *cancelOnCloseReadCloser) Read(p []byte) (int, error) {
	n, err := c.ReadCloser.Read(p)
	if _, isStreamError := err.(http2.StreamError); isStreamError {
		err = E.Cause(err, "read XHTTP response")
	}
	return n, err
}

func closeSilently(closer io.Closer) {
	if closer == nil {
		return
	}
	err := closer.Close()
	ignoreCloseError(err)
}

func ignoreCloseError(error) {
}

func (c *cancelOnCloseReadCloser) Close() error {
	err := c.ReadCloser.Close()
	if c.requestBody != nil {
		err = errors.Join(err, c.requestBody.Close())
	}
	c.cancel()
	return err
}

type synchronizedReadCloser struct {
	access   sync.Mutex
	reader   io.Reader
	closer   io.Closer
	closed   bool
	closeErr error
}

func newSynchronizedReadCloser(reader io.Reader) *synchronizedReadCloser {
	closer, _ := reader.(io.Closer)
	return &synchronizedReadCloser{
		reader: reader,
		closer: closer,
	}
}

func (r *synchronizedReadCloser) Read(p []byte) (int, error) {
	r.access.Lock()
	defer r.access.Unlock()
	if r.closed {
		return 0, net.ErrClosed
	}
	return r.reader.Read(p)
}

func (r *synchronizedReadCloser) Close() error {
	r.access.Lock()
	defer r.access.Unlock()
	if r.closed {
		return r.closeErr
	}
	r.closed = true
	if r.closer != nil {
		r.closeErr = r.closer.Close()
	}
	return r.closeErr
}

func (c *DefaultDialerClient) IsClosed() bool {
	return c.closed.Load()
}

func (c *DefaultDialerClient) OpenStream(ctx context.Context, url string, sessionId string, body io.Reader, uploadOnly bool) (wrc io.ReadCloser, remoteAddr, localAddr net.Addr, err error) {
	gotConn := done.New()
	stopContextCancel := func() bool {
		return false
	}
	var traceCtx context.Context
	traceCtx = httptrace.WithClientTrace(ctx, &httptrace.ClientTrace{
		GotConn: func(connInfo httptrace.GotConnInfo) {
			remoteAddr = connInfo.Conn.RemoteAddr()
			localAddr = connInfo.Conn.LocalAddr()
			if hook, ok := vision.HookFromContext(traceCtx); ok {
				hook(connInfo.Conn)
			}
			stopContextCancel()
			closeSilently(gotConn)
		},
	})
	requestCtx, cancelRequest := context.WithCancel(context.WithoutCancel(traceCtx))
	stopContextCancel = context.AfterFunc(ctx, cancelRequest)
	method := "GET"
	var bodyReadCloser *readDoneCloser
	if body != nil {
		method = c.options.GetNormalizedUplinkHTTPMethod()
		bodyReadCloser = newReadDoneCloser(body)
		body = bodyReadCloser
	}
	req, err := http.NewRequestWithContext(requestCtx, method, url, body)
	if err != nil {
		stopContextCancel()
		cancelRequest()
		if bodyReadCloser != nil {
			closeSilently(bodyReadCloser)
		}
		return nil, nil, nil, err
	}
	FillStreamRequest(req, sessionId, "", c.options)
	wrc = &WaitReadCloser{Wait: make(chan struct{})}
	go func() {
		//nolint:bodyclose // successful stream response bodies are exposed through wrc and closed by the caller.
		resp, err := c.client.Do(req)
		if err != nil {
			if !uploadOnly {
				c.closed.Store(true)
			}
			stopContextCancel()
			cancelRequest()
			closeSilently(gotConn)
			if bodyReadCloser != nil {
				closeSilently(bodyReadCloser)
			}
			if waitReadCloser, ok := wrc.(*WaitReadCloser); ok {
				waitReadCloser.SetError(err)
			} else {
				closeSilently(wrc)
			}
			return
		}
		if resp.StatusCode != 200 {
			c.closed.Store(true)
			closeSilently(resp.Body)
			cancelRequest()
			if bodyReadCloser != nil {
				closeSilently(bodyReadCloser)
			}
			statusErr := E.New("bad status code: ", resp.Status)
			if waitReadCloser, ok := wrc.(*WaitReadCloser); ok {
				waitReadCloser.SetError(statusErr)
			} else {
				closeSilently(wrc)
			}
			return
		}
		if uploadOnly {
			if bodyReadCloser != nil {
				select {
				case <-bodyReadCloser.Done():
				case <-requestCtx.Done():
				}
			}
			closeErr := resp.Body.Close()
			cancelRequest()
			closeSilently(wrc)
			if closeErr != nil {
				return
			}
			return
		}
		waitReadCloser, ok := wrc.(*WaitReadCloser)
		if !ok {
			closeSilently(resp.Body)
			cancelRequest()
			return
		}
		waitReadCloser.Set(&cancelOnCloseReadCloser{
			ReadCloser:  resp.Body,
			requestBody: bodyReadCloser,
			cancel:      cancelRequest,
		})
	}()
	<-gotConn.Wait()
	return
}

func (c *DefaultDialerClient) PostPacket(ctx context.Context, url string, sessionId string, seqStr string, body io.Reader, contentLength int64) (err error) {
	requestBody := newSynchronizedReadCloser(body)
	bodyOwnedByTransport := false
	defer func() {
		if !bodyOwnedByTransport {
			err = errors.Join(err, requestBody.Close())
		}
	}()
	if c.closed.Load() {
		return net.ErrClosed
	}
	method := c.options.GetNormalizedUplinkHTTPMethod()
	req, err := http.NewRequestWithContext(ctx, method, url, nil)
	if err != nil {
		return err
	}
	if err = FillPacketRequest(req, sessionId, seqStr, requestBody, contentLength, c.options); err != nil {
		return err
	}
	if c.httpVersion != "1.1" {
		bodyOwnedByTransport = req.Body != nil
		resp, err := c.client.Do(req)
		if err != nil {
			c.closed.Store(true)
			return err
		}
		if resp.StatusCode != 200 {
			c.closed.Store(true)
			closeErr := resp.Body.Close()
			return errors.Join(E.New("bad status code: ", resp.Status), closeErr)
		}
		_, copyErr := io.Copy(io.Discard, resp.Body)
		closeErr := resp.Body.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	} else {
		requestBuff := new(bytes.Buffer)
		requestBuff.Grow(512 + int(req.ContentLength))
		common.Must(req.Write(requestBuff))
		var h1UploadConn *H1Conn
		for {
			h1UploadConn = c.uploadRawPool.Get()
			newConnection := h1UploadConn == nil
			if newConnection {
				newConn, err := c.dialUploadConn(ctx)
				if err != nil {
					return err
				}
				h1UploadConn = NewH1Conn(newConn)
				if !c.uploadRawPool.Track(h1UploadConn) {
					return errors.Join(net.ErrClosed, h1UploadConn.Close())
				}
			} else {
				for h1UploadConn.UnreadedResponsesCount > 0 {
					resp, err := http.ReadResponse(h1UploadConn.RespBufReader, req)
					if err != nil {
						c.closed.Store(true)
						discardErr := c.uploadRawPool.Discard(h1UploadConn)
						return errors.Join(fmt.Errorf("error while reading response: %s", err.Error()), discardErr)
					}
					if resp.StatusCode != 200 {
						c.closed.Store(true)
						h1UploadConn.UnreadedResponsesCount--
						discardErr := c.uploadRawPool.Discard(h1UploadConn)
						return errors.Join(fmt.Errorf("got non-200 error response code: %d", resp.StatusCode), discardErr)
					}
					_, copyErr := io.Copy(io.Discard, resp.Body)
					closeErr := resp.Body.Close()
					h1UploadConn.UnreadedResponsesCount--
					if copyErr != nil {
						discardErr := c.uploadRawPool.Discard(h1UploadConn)
						return errors.Join(copyErr, discardErr)
					}
					if closeErr != nil {
						discardErr := c.uploadRawPool.Discard(h1UploadConn)
						return errors.Join(closeErr, discardErr)
					}
				}
			}
			_, err := h1UploadConn.Write(requestBuff.Bytes())
			if err == nil {
				h1UploadConn.UnreadedResponsesCount++
				break
			} else if newConnection {
				discardErr := c.uploadRawPool.Discard(h1UploadConn)
				return errors.Join(err, discardErr)
			} else {
				discardErr := c.uploadRawPool.Discard(h1UploadConn)
				if discardErr != nil {
					return discardErr
				}
			}
		}
		c.uploadRawPool.Put(h1UploadConn)
	}
	return nil
}

func (c *DefaultDialerClient) Close() error {
	c.closeOnce.Do(func() {
		c.closed.Store(true)
		if c.uploadRawPool != nil {
			c.closeErr = c.uploadRawPool.Close()
		}
		if c.client != nil && c.client.Transport != nil {
			if closer, ok := c.client.Transport.(interface{ CloseIdleConnections() }); ok {
				closer.CloseIdleConnections()
			}
			if closer, ok := c.client.Transport.(interface{ Close() error }); ok {
				c.closeErr = errors.Join(c.closeErr, closer.Close())
			}
		}
		if c.rawConns != nil {
			c.closeErr = errors.Join(c.closeErr, c.rawConns.Close())
		}
	})
	return c.closeErr
}

type WaitReadCloser struct {
	Wait chan struct{}
	io.ReadCloser
	mu     sync.Mutex
	once   sync.Once
	closed bool
	err    error
}

func (w *WaitReadCloser) notify() {
	w.once.Do(func() {
		close(w.Wait)
	})
}

func (w *WaitReadCloser) Set(rc io.ReadCloser) {
	w.mu.Lock()
	if w.closed || w.ReadCloser != nil {
		w.mu.Unlock()
		closeSilently(rc)
		return
	}
	w.ReadCloser = rc
	w.mu.Unlock()
	w.notify()
}

func (w *WaitReadCloser) SetError(err error) {
	w.mu.Lock()
	if w.closed || w.ReadCloser != nil || w.err != nil {
		w.mu.Unlock()
		return
	}
	w.err = err
	w.mu.Unlock()
	w.notify()
}

func (w *WaitReadCloser) WaitAccepted(ctx context.Context) error {
	select {
	case <-w.Wait:
	case <-ctx.Done():
		return ctx.Err()
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.err
}

func (w *WaitReadCloser) Read(b []byte) (int, error) {
	w.mu.Lock()
	rc := w.ReadCloser
	err := w.err
	w.mu.Unlock()

	if err != nil {
		return 0, err
	}
	if rc == nil {
		<-w.Wait
		w.mu.Lock()
		rc = w.ReadCloser
		err = w.err
		w.mu.Unlock()
		if err != nil {
			return 0, err
		}
		if rc == nil {
			return 0, io.ErrClosedPipe
		}
	}
	return rc.Read(b)
}

func (w *WaitReadCloser) Close() error {
	w.mu.Lock()
	if w.closed {
		w.mu.Unlock()
		return nil
	}
	w.closed = true
	rc := w.ReadCloser
	w.ReadCloser = nil
	w.mu.Unlock()

	if rc != nil {
		return rc.Close()
	}

	w.notify()
	return nil
}

func ApplyMetaToRequest(options *option.V2RayXHTTPBaseOptions, req *http.Request, sessionId string, seqStr string) {
	sessionPlacement := options.GetNormalizedSessionPlacement()
	seqPlacement := options.GetNormalizedSeqPlacement()
	sessionKey := options.GetNormalizedSessionKey()
	seqKey := options.GetNormalizedSeqKey()
	if sessionId != "" {
		switch sessionPlacement {
		case option.PlacementPath:
			req.URL.Path = appendToPath(req.URL.Path, sessionId)
		case option.PlacementQuery:
			q := req.URL.Query()
			q.Set(sessionKey, sessionId)
			req.URL.RawQuery = q.Encode()
		case option.PlacementHeader:
			req.Header.Set(sessionKey, sessionId)
		case option.PlacementCookie:
			req.AddCookie(&http.Cookie{Name: sessionKey, Value: sessionId})
		}
	}
	if seqStr != "" {
		switch seqPlacement {
		case option.PlacementPath:
			req.URL.Path = appendToPath(req.URL.Path, seqStr)
		case option.PlacementQuery:
			q := req.URL.Query()
			q.Set(seqKey, seqStr)
			req.URL.RawQuery = q.Encode()
		case option.PlacementHeader:
			req.Header.Set(seqKey, seqStr)
		case option.PlacementCookie:
			req.AddCookie(&http.Cookie{Name: seqKey, Value: seqStr})
		}
	}
}

func appendToPath(path, value string) string {
	if strings.HasSuffix(path, "/") {
		return path + value
	}
	return path + "/" + value
}
