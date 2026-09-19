//go:build with_adblock && with_adblock_cronet

package httpconn

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/sagernet/cronet-go"
	_ "github.com/sagernet/cronet-go/all"
	"github.com/sagernet/sing-box/common/cronetbidistream"
	"github.com/sagernet/sing-box/experimental/adblock/consts"
	"github.com/sagernet/sing-box/experimental/adblock/ctx"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

const SupportsCronet = true

type cronetForwarder struct {
	ctx       context.Context
	cancel    context.CancelFunc
	conn      *ctx.Conn
	tcp       *cronetURLForwarder
	quic      *cronetBidirectionalForwarder
	fallback  ClosableRoundTripper
	waitGroup sync.WaitGroup
	closeOnce sync.Once
}

type cronetURLForwarder struct {
	engine   cronet.Engine
	executor cronet.Executor

	access    sync.Mutex
	closing   bool
	active    map[*cronetURLResponse]struct{}
	waitGroup sync.WaitGroup
}

type cronetBidirectionalForwarder struct {
	engine       cronet.Engine
	streamEngine cronet.StreamEngine
	logger       logger.ContextLogger
	waitGroup    *sync.WaitGroup

	access          sync.Mutex
	closing         bool
	activeResponses map[*cronetBidirectionalResponseBody]struct{}
	activeWaitGroup sync.WaitGroup
}

func NewCronetForwarder(parent context.Context, c *ctx.Conn) ClosableRoundTripper {
	forwarderCtx, cancel := context.WithCancel(parent)
	forwarder := &cronetForwarder{
		ctx:    forwarderCtx,
		cancel: cancel,
		conn:   c,
	}
	forwarder.tcp = forwarder.newCronetURLForwarder(false)
	forwarder.quic = forwarder.newCronetBidirectionalForwarder()
	fallbackConn := *c
	fallbackConn.Cronet = false
	if SupportsUTLS {
		fallbackConn.UTLS = consts.Chrome
	}
	forwarder.fallback = newStandardHTTPForwarder(parent, &fallbackConn)
	return forwarder
}

func (f *cronetForwarder) RoundTrip(request *http.Request) (*http.Response, error) {
	if request.ProtoMajor == 3 {
		return f.quic.RoundTrip(request)
	}
	if !http2Capable(request) {
		return f.fallback.RoundTrip(request)
	}
	return f.tcp.RoundTrip(request)
}

func (f *cronetForwarder) Close() {
	defer recoverCronetPanic(nil)
	if f == nil {
		return
	}
	f.closeOnce.Do(func() {
		f.cancel()
		if f.tcp != nil {
			f.tcp.close()
		}
		if f.quic != nil {
			f.quic.close()
		}
		if f.fallback != nil {
			f.fallback.Close()
		}
		f.waitGroup.Wait()
	})
}

func (f *cronetForwarder) newCronetURLForwarder(enableQUIC bool) (forwarder *cronetURLForwarder) {
	defer func() {
		if value := recover(); value != nil {
			forwarder = &cronetURLForwarder{}
		}
	}()
	engine := cronet.NewEngine()
	engine.SetDialer(f.tcpDialer())
	if enableQUIC {
		engine.SetUDPDialer(f.udpDialer())
	}

	params := cronet.NewEngineParams()
	params.SetEnableHTTP2(true)
	params.SetEnableQuic(enableQUIC)
	params.SetEnableBrotli(true)
	result := engine.StartWithParams(params)
	params.Destroy()
	if result != cronet.ResultSuccess {
		safeDestroyCronetEngine(engine)
		return &cronetURLForwarder{}
	}
	executor := cronet.NewExecutor(func(executor cronet.Executor, command cronet.Runnable) {
		go func() {
			defer recoverCronetPanic(nil)
			defer safeDestroyCronetRunnable(command)
			command.Run()
		}()
	})

	return &cronetURLForwarder{
		engine:   engine,
		executor: executor,
		active:   make(map[*cronetURLResponse]struct{}),
	}
}

func (f *cronetForwarder) newCronetBidirectionalForwarder() (forwarder *cronetBidirectionalForwarder) {
	defer func() {
		if value := recover(); value != nil {
			forwarder = &cronetBidirectionalForwarder{logger: logger.NOP(), waitGroup: &f.waitGroup, activeResponses: make(map[*cronetBidirectionalResponseBody]struct{})}
		}
	}()
	engine := cronet.NewEngine()
	engine.SetDialer(f.tcpDialer())
	engine.SetUDPDialer(f.udpDialer())

	params := cronet.NewEngineParams()
	params.SetEnableHTTP2(false)
	params.SetEnableQuic(true)
	params.SetEnableBrotli(true)
	result := engine.StartWithParams(params)
	params.Destroy()
	if result != cronet.ResultSuccess {
		safeDestroyCronetEngine(engine)
		return &cronetBidirectionalForwarder{logger: logger.NOP(), waitGroup: &f.waitGroup, activeResponses: make(map[*cronetBidirectionalResponseBody]struct{})}
	}

	return &cronetBidirectionalForwarder{
		engine:          engine,
		streamEngine:    engine.StreamEngine(),
		logger:          logger.NOP(),
		waitGroup:       &f.waitGroup,
		activeResponses: make(map[*cronetBidirectionalResponseBody]struct{}),
	}
}

func (f *cronetForwarder) tcpDialer() cronet.Dialer {
	return func(address string, port uint16) (fd int) {
		defer func() {
			if recover() != nil {
				fd = cronet.NetErrorConnectionFailed.Code()
			}
		}()
		destination := M.ParseSocksaddrHostPort(address, port)
		conn, err := dialForwarder(f.ctx, N.NetworkTCP, destination.String(), f.conn)
		if err != nil {
			return cronetNetError(err).Code()
		}
		if syscallConn, ok := conn.(syscall.Conn); ok {
			fd, duplicateErr := duplicateSocketFD(syscallConn)
			if duplicateErr == nil {
				_ = conn.Close()
				return fd
			}
		}
		fd, pipeConn, err := createCronetTCPBridge()
		if err != nil {
			_ = conn.Close()
			return cronet.NetErrorConnectionFailed.Code()
		}
		f.waitGroup.Add(1)
		go func() {
			defer recoverCronetPanic(nil)
			defer f.waitGroup.Done()
			bufio.CopyConn(f.ctx, conn, pipeConn)
			_ = conn.Close()
			_ = pipeConn.Close()
		}()
		return fd
	}
}

func (f *cronetForwarder) udpDialer() cronet.UDPDialer {
	return func(address string, port uint16) (fd int, localAddress string, localPort uint16) {
		defer func() {
			if recover() != nil {
				fd = cronet.NetErrorConnectionFailed.Code()
				localAddress = ""
				localPort = 0
			}
		}()
		destination := M.ParseSocksaddrHostPort(address, port)
		conn, err := dialForwarder(f.ctx, N.NetworkUDP, destination.String(), f.conn)
		if err != nil {
			return cronetNetError(err).Code(), "", 0
		}
		localAddr := M.SocksaddrFromNet(conn.LocalAddr())
		if localAddr.IsValid() {
			localAddress = localAddr.AddrString()
			localPort = localAddr.Port
		}
		if syscallConn, ok := conn.(syscall.Conn); ok {
			fd, duplicateErr := duplicateSocketFD(syscallConn)
			if duplicateErr == nil {
				_ = conn.Close()
				return fd, localAddress, localPort
			}
		}
		fd, pipeConn, err := createCronetPacketSocketPair()
		if err != nil {
			_ = conn.Close()
			return cronet.NetErrorConnectionFailed.Code(), "", 0
		}
		remoteAddress := M.SocksaddrFromNet(conn.RemoteAddr())
		packetConn := bufio.NewUnbindPacketConn(conn)
		pipePacketConn := bufio.NewUnbindPacketConnWithAddr(pipeConn.(net.Conn), remoteAddress)
		f.waitGroup.Add(1)
		go func() {
			defer recoverCronetPanic(nil)
			defer f.waitGroup.Done()
			_ = bufio.CopyPacketConn(f.ctx, packetConn, pipePacketConn)
			_ = conn.Close()
			_ = pipeConn.Close()
		}()
		return fd, localAddress, localPort
	}
}

func (f *cronetURLForwarder) RoundTrip(request *http.Request) (*http.Response, error) {
	if f == nil || f.engine == (cronet.Engine{}) || f.executor == (cronet.Executor{}) {
		return nil, E.New("failed to start adblock Cronet engine")
	}
	f.access.Lock()
	closing := f.closing
	f.access.Unlock()
	if closing {
		return nil, net.ErrClosed
	}
	return (&cronetURLRequest{
		owner:         f,
		engine:        f.engine,
		executor:      f.executor,
		checkRedirect: func(string) bool { return false },
	}).RoundTrip(request)
}

func (f *cronetURLForwarder) close() {
	if f == nil || f.engine == (cronet.Engine{}) {
		return
	}

	f.access.Lock()
	f.closing = true
	active := make([]*cronetURLResponse, 0, len(f.active))
	for response := range f.active {
		active = append(active, response)
	}
	f.access.Unlock()

	for _, response := range active {
		response.cancelRequest()
	}
	f.waitGroup.Wait()

	safeCronetCloseAllConnections(f.engine)
	if shutdownResult, shutdownOK := safeCronetEngineShutdown(f.engine); shutdownOK && shutdownResult == cronet.ResultSuccess {
		destroyCronetEngine(f.engine)
	} else if f.engine != (cronet.Engine{}) {
		// Cronet documents Shutdown as succeeding only when no active requests
		// remain. If native Cronet disagrees, keep the handle alive rather than
		// destroying an engine that may still be referenced by Cronet threads.
	}
	if f.executor != (cronet.Executor{}) && runtime.GOOS != "android" {
		safeDestroyCronetExecutor(f.executor)
	}
	f.engine = cronet.Engine{}
	f.executor = cronet.Executor{}
}

func (f *cronetURLForwarder) register(response *cronetURLResponse) bool {
	f.access.Lock()
	defer f.access.Unlock()
	if f.closing {
		return false
	}
	if f.active == nil {
		f.active = make(map[*cronetURLResponse]struct{})
	}
	f.active[response] = struct{}{}
	f.waitGroup.Add(1)
	return true
}

func (f *cronetURLForwarder) unregister(response *cronetURLResponse) {
	if f == nil {
		return
	}
	f.access.Lock()
	if _, loaded := f.active[response]; loaded {
		delete(f.active, response)
		f.waitGroup.Done()
	}
	f.access.Unlock()
}

type cronetURLRequest struct {
	owner         *cronetURLForwarder
	engine        cronet.Engine
	executor      cronet.Executor
	checkRedirect func(newLocationURL string) bool
}

const (
	hostHeader              = "host"
	acceptEncodingHeader    = "Accept-Encoding"
	contentEncodingHeader   = "Content-Encoding"
	contentLengthHeader     = "Content-Length"
	identityContentEncoding = "identity"
)

func (t *cronetURLRequest) RoundTrip(request *http.Request) (response *http.Response, err error) {
	defer recoverCronetPanic(&err)
	requestParams := cronet.NewURLRequestParams()
	if request.Method == "" {
		requestParams.SetMethod(http.MethodGet)
	} else {
		requestParams.SetMethod(request.Method)
	}

	var hasHost bool
	for key, values := range request.Header {
		for _, value := range values {
			if !hasHost && strings.EqualFold(key, hostHeader) {
				hasHost = true
			}

			header := cronet.NewHTTPHeader()
			header.SetName(key)
			header.SetValue(value)
			requestParams.AddHeader(header)
			header.Destroy()
		}
	}
	if !hasHost && request.Host != "" {
		header := cronet.NewHTTPHeader()
		header.SetName("Host")
		header.SetValue(request.Host)
		requestParams.AddHeader(header)
		header.Destroy()
	}

	if request.Body != nil {
		uploadProvider := cronet.NewUploadDataProvider(&cronetBodyUploadProvider{
			body:          request.Body,
			getBody:       request.GetBody,
			contentLength: request.ContentLength,
		})
		requestParams.SetUploadDataProvider(uploadProvider)
		requestParams.SetUploadDataExecutor(t.executor)
	}

	responseHandler := &cronetURLResponse{
		owner:         t.owner,
		checkRedirect: t.checkRedirect,
		response: http.Response{
			Request:    request,
			Proto:      request.Proto,
			ProtoMajor: request.ProtoMajor,
			ProtoMinor: request.ProtoMinor,
			Header:     make(http.Header),
		},
		read:   make(chan cronetURLReadResult),
		cancel: make(chan struct{}),
		done:   make(chan struct{}),
	}
	responseHandler.response.Body = responseHandler
	responseHandler.wg.Add(1)
	go responseHandler.monitorContext(request.Context())

	callback := cronet.NewURLRequestCallback(responseHandler)
	responseHandler.callback = callback
	urlRequest := cronet.NewURLRequest()
	responseHandler.request = urlRequest
	if t.owner != nil && !t.owner.register(responseHandler) {
		requestParams.Destroy()
		callback.Destroy()
		urlRequest.Destroy()
		return nil, net.ErrClosed
	}
	if result := urlRequest.InitWithParams(t.engine, request.URL.String(), requestParams, callback, t.executor); result != cronet.ResultSuccess {
		requestParams.Destroy()
		responseHandler.finish(E.New("initialize Cronet URL request failed: ", result))
		return nil, E.New("initialize Cronet URL request failed: ", result)
	}
	requestParams.Destroy()
	if result := urlRequest.Start(); result != cronet.ResultSuccess {
		responseHandler.finish(E.New("start Cronet URL request failed: ", result))
		return nil, E.New("start Cronet URL request failed: ", result)
	}

	responseHandler.wg.Wait()
	if err := responseHandler.initialErr(); err != nil {
		return &responseHandler.response, err
	}
	return &responseHandler.response, nil
}

type cronetURLReadResult struct {
	n   int
	err error
}

type cronetURLResponse struct {
	owner         *cronetURLForwarder
	checkRedirect func(newLocationURL string) bool

	wg     sync.WaitGroup
	wgDone sync.Once

	access   sync.Mutex
	request  cronet.URLRequest
	callback cronet.URLRequestCallback
	response http.Response
	err      error

	readBuffer cronet.Buffer
	readDest   []byte

	read chan cronetURLReadResult

	cancel     chan struct{}
	cancelOnce sync.Once
	done       chan struct{}
	doneOnce   sync.Once
}

func (r *cronetURLResponse) recoverCallbackPanic() {
	if value := recover(); value != nil {
		r.finish(E.New("Cronet URL request callback panic: ", fmt.Sprint(value)))
	}
}

func (r *cronetURLResponse) monitorContext(ctx context.Context) {
	if ctx == nil || ctx.Done() == nil {
		return
	}
	select {
	case <-r.cancel:
	case <-r.done:
	case <-ctx.Done():
		r.setErr(ctx.Err())
		_ = r.Close()
	}
}

func (r *cronetURLResponse) OnRedirectReceived(self cronet.URLRequestCallback, request cronet.URLRequest, info cronet.URLResponseInfo, newLocationURL string) {
	defer r.recoverCallbackPanic()
	if r.checkRedirect != nil && !r.checkRedirect(newLocationURL) {
		r.setResponseInfo(info)
		r.response.Body = cronetRedirectBody{response: r}
		r.ready()
		return
	}
	request.FollowRedirect()
}

func (r *cronetURLResponse) OnResponseStarted(self cronet.URLRequestCallback, request cronet.URLRequest, info cronet.URLResponseInfo) {
	defer r.recoverCallbackPanic()
	r.setResponseInfo(info)
	r.ready()
}

func (r *cronetURLResponse) setResponseInfo(info cronet.URLResponseInfo) {
	statusCode := info.StatusCode()
	statusText := info.StatusText()
	if statusText == "" {
		statusText = http.StatusText(statusCode)
	}
	r.response.StatusCode = statusCode
	r.response.Status = strconv.Itoa(statusCode) + " " + statusText

	headerLen := info.HeaderSize()
	for i := range headerLen {
		header := info.HeaderAt(i)
		r.response.Header.Add(header.Name(), header.Value())
	}
	normalizeCronetURLResponseHeaders(&r.response)
}

func (r *cronetURLResponse) Read(p []byte) (n int, err error) {
	defer recoverCronetPanic(&err)
	if len(p) == 0 {
		return 0, nil
	}

	select {
	case <-r.done:
		return 0, r.resultErr()
	default:
	}

	// Do not pass p directly to Cronet with InitWithDataAndCallback.
	// URLRequest.Read is asynchronous, so Cronet would keep a pointer to
	// Go-owned memory after this cgo/purego call returns. That is invalid and
	// can corrupt native state on Android. Allocate a Cronet-owned buffer and
	// copy into p only after OnReadCompleted fires.
	buffer := cronet.NewBuffer()
	buffer.InitWithAlloc(int64(len(p)))

	r.access.Lock()
	request := r.request
	if request == (cronet.URLRequest{}) {
		r.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return 0, net.ErrClosed
	}
	if r.readBuffer != (cronet.Buffer{}) {
		r.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return 0, E.New("concurrent Cronet URL response reads are unsupported")
	}
	r.readBuffer = buffer
	r.readDest = p
	r.access.Unlock()

	if result := request.Read(buffer); result != cronet.ResultSuccess {
		r.access.Lock()
		if r.readBuffer == buffer {
			r.readBuffer = cronet.Buffer{}
			r.readDest = nil
		}
		r.access.Unlock()
		safeDestroyCronetBuffer(buffer)
		return 0, E.New("read Cronet URL response failed: ", result)
	}

	select {
	case result := <-r.read:
		return result.n, result.err
	case <-r.cancel:
		<-r.done
		return 0, net.ErrClosed
	case <-r.done:
		return 0, r.resultErr()
	}
}

func (r *cronetURLResponse) Close() error {
	r.cancelRequest()
	return nil
}

func (r *cronetURLResponse) cancelRequest() {
	r.cancelOnce.Do(func() {
		close(r.cancel)

		r.access.Lock()
		request := r.request
		r.access.Unlock()
		if request != (cronet.URLRequest{}) {
			safeCronetURLRequestCancel(request)
		}
	})
}

type cronetRedirectBody struct {
	response *cronetURLResponse
}

func (b cronetRedirectBody) Read([]byte) (int, error) {
	return 0, io.EOF
}

func (b cronetRedirectBody) Close() error {
	if b.response == nil {
		return nil
	}
	return b.response.Close()
}

func (r *cronetURLResponse) OnReadCompleted(self cronet.URLRequestCallback, request cronet.URLRequest, info cronet.URLResponseInfo, buffer cronet.Buffer, bytesRead int64) {
	defer r.recoverCallbackPanic()
	defer safeDestroyCronetBuffer(buffer)

	var n int
	var readErr error

	r.access.Lock()
	if r.readBuffer == buffer {
		if bytesRead > 0 {
			data := buffer.DataSlice()
			if bytesRead > int64(len(data)) || bytesRead > int64(len(r.readDest)) {
				readErr = E.New("Cronet URL response read larger than destination buffer: ", bytesRead)
			} else {
				n = int(bytesRead)
				copy(r.readDest, data[:n])
			}
		} else {
			readErr = io.EOF
		}
		r.readBuffer = cronet.Buffer{}
		r.readDest = nil
	} else {
		readErr = net.ErrClosed
	}
	r.access.Unlock()

	if readErr != nil && readErr != io.EOF {
		r.finish(readErr)
		return
	}
	if bytesRead == 0 {
		r.finish(io.EOF)
		return
	}

	select {
	case <-r.cancel:
	case <-r.done:
	case r.read <- cronetURLReadResult{n: n, err: readErr}:
	}
}

func (r *cronetURLResponse) OnSucceeded(self cronet.URLRequestCallback, request cronet.URLRequest, info cronet.URLResponseInfo) {
	defer r.recoverCallbackPanic()
	r.finish(io.EOF)
}

func (r *cronetURLResponse) OnFailed(self cronet.URLRequestCallback, request cronet.URLRequest, info cronet.URLResponseInfo, err cronet.Error) {
	defer r.recoverCallbackPanic()
	r.finish(cronet.ErrorFromError(err))
}

func (r *cronetURLResponse) OnCanceled(self cronet.URLRequestCallback, request cronet.URLRequest, info cronet.URLResponseInfo) {
	defer r.recoverCallbackPanic()
	r.finish(context.Canceled)
}

func (r *cronetURLResponse) ready() {
	r.wgDone.Do(r.wg.Done)
}

func (r *cronetURLResponse) setErr(err error) {
	if err == nil {
		return
	}
	r.access.Lock()
	if r.err == nil {
		r.err = err
	}
	r.access.Unlock()
}

func (r *cronetURLResponse) initialErr() error {
	r.access.Lock()
	err := r.err
	r.access.Unlock()
	if err == io.EOF {
		return nil
	}
	return err
}

func (r *cronetURLResponse) resultErr() error {
	r.access.Lock()
	err := r.err
	r.access.Unlock()
	if err == nil {
		return io.EOF
	}
	return err
}

func (r *cronetURLResponse) finish(err error) {
	if err == nil {
		err = io.EOF
	}
	r.doneOnce.Do(func() {
		r.access.Lock()
		if r.err == nil {
			r.err = err
		}
		request := r.request
		callback := r.callback
		r.request = cronet.URLRequest{}
		r.callback = cronet.URLRequestCallback{}
		r.access.Unlock()

		r.ready()
		close(r.done)

		destroyCronetURLRequestAsync(request, callback)
		if r.owner != nil {
			r.owner.unregister(r)
		}
	})
}

type cronetBodyUploadProvider struct {
	access        sync.Mutex
	body          io.ReadCloser
	getBody       func() (io.ReadCloser, error)
	contentLength int64
	closed        bool
}

func (p *cronetBodyUploadProvider) Length(self cronet.UploadDataProvider) (length int64) {
	defer recoverCronetPanic(nil)
	if p == nil {
		return 0
	}
	return p.contentLength
}

func (p *cronetBodyUploadProvider) Read(self cronet.UploadDataProvider, sink cronet.UploadDataSink, buffer cronet.Buffer) {
	defer recoverCronetUploadPanic(sink, "read Cronet upload body")
	if p == nil || sink == (cronet.UploadDataSink{}) || buffer == (cronet.Buffer{}) {
		return
	}
	p.access.Lock()
	defer p.access.Unlock()
	if p.closed {
		return
	}
	if p.body == nil {
		sink.OnReadError("read Cronet upload body: request body is closed")
		return
	}
	n, err := p.body.Read(buffer.DataSlice())
	if err != nil {
		if err == io.EOF && n > 0 {
			sink.OnReadSucceeded(int64(n), false)
			return
		}
		if err == io.EOF && p.contentLength == -1 {
			sink.OnReadSucceeded(0, true)
			return
		}
		sink.OnReadError(err.Error())
		return
	}
	sink.OnReadSucceeded(int64(n), false)
}

func (p *cronetBodyUploadProvider) Rewind(self cronet.UploadDataProvider, sink cronet.UploadDataSink) {
	defer recoverCronetRewindPanic(sink, "rewind Cronet upload body")
	if p == nil || sink == (cronet.UploadDataSink{}) {
		return
	}
	p.access.Lock()
	defer p.access.Unlock()
	if p.closed {
		sink.OnRewindError("rewind Cronet upload body: request body is closed")
		return
	}
	if p.getBody == nil {
		sink.OnRewindError("unsupported")
		return
	}
	newBody, err := p.getBody()
	if err != nil {
		sink.OnRewindError(err.Error())
		return
	}
	if newBody == nil {
		sink.OnRewindError("rewind Cronet upload body: request body is nil")
		return
	}
	if p.body != nil {
		_ = p.body.Close()
	}
	p.body = newBody
	sink.OnRewindSucceeded()
}

func (p *cronetBodyUploadProvider) Close(self cronet.UploadDataProvider) {
	defer recoverCronetPanic(nil)
	var destroy bool
	if p != nil {
		p.access.Lock()
		if !p.closed {
			p.closed = true
			destroy = true
			if p.body != nil {
				_ = p.body.Close()
				p.body = nil
			}
		}
		p.access.Unlock()
	}
	if destroy || p == nil {
		destroyCronetUploadDataProviderAsync(self)
	}
}

func (f *cronetBidirectionalForwarder) RoundTrip(request *http.Request) (response *http.Response, err error) {
	defer recoverCronetPanic(&err)
	if f == nil || f.engine == (cronet.Engine{}) || f.streamEngine == (cronet.StreamEngine{}) {
		return nil, E.New("failed to start adblock Cronet QUIC engine")
	}
	f.access.Lock()
	closing := f.closing
	f.access.Unlock()
	if closing {
		return nil, net.ErrClosed
	}
	conn := cronetbidistream.CreateConn(f.streamEngine, request.Context(), f.logger, true, false)
	headers := make(map[string]string, len(request.Header)+2)
	for key, values := range request.Header {
		if strings.EqualFold(key, acceptEncodingHeader) {
			continue
		}
		if len(values) > 0 {
			headers[key] = values[0]
		}
	}
	// Keep the BidirectionalStream path from negotiating compressed response
	// bodies. Unlike URLRequest, this path is treated as a raw stream here, so
	// forwarding encoded lengths after implicit decoding would be unsafe.
	headers[acceptEncodingHeader] = identityContentEncoding
	headers["-force-quic"] = "true"
	endOfStream := request.Body == nil || request.Body == http.NoBody
	if err := conn.Start(request.Method, request.URL.String(), headers, 0, endOfStream); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if !endOfStream {
		f.waitGroup.Go(func() {
			_, copyErr := io.Copy(conn, request.Body)
			_ = request.Body.Close()
			if copyErr != nil {
				_ = conn.Close()
				return
			}
			_ = conn.CloseWrite()
		})
	}
	responseHeaders, err := conn.WaitForHeadersContext(request.Context())
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	status := http.StatusOK
	if statusValue := responseHeaders[":status"]; statusValue != "" {
		if parsedStatus, parseErr := strconv.Atoi(statusValue); parseErr == nil {
			status = parsedStatus
		}
	}
	body := &cronetBidirectionalResponseBody{owner: f, conn: conn}
	if !f.registerResponseBody(body) {
		_ = body.Close()
		return nil, net.ErrClosed
	}
	response = &http.Response{
		StatusCode: status,
		Status:     strconv.Itoa(status) + " " + http.StatusText(status),
		Header:     make(http.Header),
		Body:       body,
		Request:    request,
		Proto:      "HTTP/3.0",
		ProtoMajor: 3,
		ProtoMinor: 0,
	}
	for key, value := range responseHeaders {
		if strings.HasPrefix(key, ":") {
			continue
		}
		response.Header.Set(key, value)
	}
	normalizeCronetBidirectionalResponseHeaders(response)
	return response, nil
}

type cronetBidirectionalResponseBody struct {
	owner *cronetBidirectionalForwarder
	conn  io.ReadWriteCloser
	once  sync.Once
}

func (b *cronetBidirectionalResponseBody) Read(p []byte) (n int, err error) {
	defer recoverCronetPanic(&err)
	if b == nil || b.conn == nil {
		return 0, net.ErrClosed
	}
	n, err = b.conn.Read(p)
	if err != nil {
		_ = b.Close()
	}
	return n, err
}

func (b *cronetBidirectionalResponseBody) Close() (err error) {
	defer recoverCronetPanic(&err)
	if b == nil {
		return nil
	}
	b.once.Do(func() {
		if b.conn != nil {
			err = b.conn.Close()
			b.conn = nil
		}
		if b.owner != nil {
			b.owner.unregisterResponseBody(b)
		}
	})
	return err
}

func (f *cronetBidirectionalForwarder) registerResponseBody(body *cronetBidirectionalResponseBody) bool {
	f.access.Lock()
	defer f.access.Unlock()
	if f.closing {
		return false
	}
	if f.activeResponses == nil {
		f.activeResponses = make(map[*cronetBidirectionalResponseBody]struct{})
	}
	f.activeResponses[body] = struct{}{}
	f.activeWaitGroup.Add(1)
	return true
}

func (f *cronetBidirectionalForwarder) unregisterResponseBody(body *cronetBidirectionalResponseBody) {
	if f == nil {
		return
	}
	f.access.Lock()
	if _, loaded := f.activeResponses[body]; loaded {
		delete(f.activeResponses, body)
		f.activeWaitGroup.Done()
	}
	f.access.Unlock()
}

func normalizeCronetBidirectionalResponseHeaders(response *http.Response) {
	if response == nil {
		return
	}

	// BidirectionalStream is used here as a raw HTTP/3 body stream. We request
	// identity encoding above, so Content-Encoding should normally be absent. If
	// an upstream still sends an encoded body, avoid forwarding a stale or
	// misleading Content-Length. Keeping Content-Encoding preserves correctness
	// for raw compressed bytes while preventing length-based truncation.
	if response.Header.Get(contentEncodingHeader) != "" && !strings.EqualFold(response.Header.Get(contentEncodingHeader), identityContentEncoding) {
		response.Header.Del(contentLengthHeader)
		response.ContentLength = -1
		return
	}

	normalizeContentLength(response)
}

func normalizeCronetURLResponseHeaders(response *http.Response) {
	if response == nil {
		return
	}

	// Cronet URLRequest exposes a decoded body to callers when Content-Encoding is
	// present. The Content-Length header still describes the encoded wire body, so
	// forwarding it makes downstream writers/clients truncate the decoded payload.
	if response.Header.Get(contentEncodingHeader) != "" {
		response.Header.Del(contentEncodingHeader)
		response.Header.Del(contentLengthHeader)
		response.ContentLength = -1
		return
	}

	normalizeContentLength(response)
}

func normalizeContentLength(response *http.Response) {
	if response == nil {
		return
	}
	contentLength := response.Header.Get(contentLengthHeader)
	if contentLength == "" {
		response.ContentLength = -1
		response.Header.Del(contentLengthHeader)
		return
	}
	parsedContentLength, err := strconv.ParseInt(contentLength, 10, 64)
	if err != nil || parsedContentLength < 0 {
		response.ContentLength = -1
		response.Header.Del(contentLengthHeader)
		return
	}
	response.ContentLength = parsedContentLength
}

func normalizeCronetContentLength(response *http.Response) {
	normalizeContentLength(response)
}

func (f *cronetBidirectionalForwarder) close() {
	if f == nil || f.engine == (cronet.Engine{}) {
		return
	}

	f.access.Lock()
	f.closing = true
	active := make([]*cronetBidirectionalResponseBody, 0, len(f.activeResponses))
	for body := range f.activeResponses {
		active = append(active, body)
	}
	f.access.Unlock()

	for _, body := range active {
		_ = body.Close()
	}
	f.activeWaitGroup.Wait()

	safeCronetCloseAllConnections(f.engine)
	if shutdownResult, shutdownOK := safeCronetEngineShutdown(f.engine); shutdownOK && shutdownResult == cronet.ResultSuccess {
		destroyCronetEngine(f.engine)
	}
	f.engine = cronet.Engine{}
}

func destroyCronetEngine(engine cronet.Engine) {
	if engine == (cronet.Engine{}) || runtime.GOOS == "android" {
		return
	}
	safeDestroyCronetEngine(engine)
}

func destroyCronetURLRequestAsync(request cronet.URLRequest, callback cronet.URLRequestCallback) {
	if request == (cronet.URLRequest{}) && callback == (cronet.URLRequestCallback{}) {
		return
	}
	go func() {
		// Finish can be called from a Cronet callback. Destroying the native
		// request/callback from that callback stack is unsafe on Android builds.
		time.Sleep(100 * time.Millisecond)
		if request != (cronet.URLRequest{}) {
			safeDestroyCronetURLRequest(request)
		}
		if callback != (cronet.URLRequestCallback{}) {
			safeDestroyCronetURLRequestCallback(callback)
		}
	}()
}

func destroyCronetUploadDataProviderAsync(provider cronet.UploadDataProvider) {
	if provider == (cronet.UploadDataProvider{}) {
		return
	}
	go func() {
		time.Sleep(100 * time.Millisecond)
		safeDestroyCronetUploadDataProvider(provider)
	}()
}

func recoverCronetPanic(errp *error) {
	if value := recover(); value != nil && errp != nil && *errp == nil {
		*errp = E.New("Cronet native bridge panic: ", fmt.Sprint(value))
	}
}

func recoverCronetUploadPanic(sink cronet.UploadDataSink, action string) {
	if value := recover(); value != nil {
		defer recoverCronetPanic(nil)
		sink.OnReadError(action + ": " + fmt.Sprint(value))
	}
}

func recoverCronetRewindPanic(sink cronet.UploadDataSink, action string) {
	if value := recover(); value != nil {
		defer recoverCronetPanic(nil)
		sink.OnRewindError(action + ": " + fmt.Sprint(value))
	}
}

func safeDestroyCronetRunnable(command cronet.Runnable) {
	defer recoverCronetPanic(nil)
	if command != (cronet.Runnable{}) {
		command.Destroy()
	}
}

func safeDestroyCronetBuffer(buffer cronet.Buffer) {
	defer recoverCronetPanic(nil)
	if buffer != (cronet.Buffer{}) {
		buffer.Destroy()
	}
}

func safeDestroyCronetUploadDataProvider(provider cronet.UploadDataProvider) {
	defer recoverCronetPanic(nil)
	if provider != (cronet.UploadDataProvider{}) {
		provider.Destroy()
	}
}

func safeDestroyCronetURLRequest(request cronet.URLRequest) {
	defer recoverCronetPanic(nil)
	if request != (cronet.URLRequest{}) {
		request.Destroy()
	}
}

func safeDestroyCronetURLRequestCallback(callback cronet.URLRequestCallback) {
	defer recoverCronetPanic(nil)
	if callback != (cronet.URLRequestCallback{}) {
		callback.Destroy()
	}
}

func safeDestroyCronetExecutor(executor cronet.Executor) {
	defer recoverCronetPanic(nil)
	if executor != (cronet.Executor{}) {
		executor.Destroy()
	}
}

func safeDestroyCronetEngine(engine cronet.Engine) {
	defer recoverCronetPanic(nil)
	if engine != (cronet.Engine{}) {
		engine.Destroy()
	}
}

func safeCronetURLRequestCancel(request cronet.URLRequest) {
	defer recoverCronetPanic(nil)
	if request != (cronet.URLRequest{}) {
		request.Cancel()
	}
}

func safeCronetCloseAllConnections(engine cronet.Engine) {
	defer recoverCronetPanic(nil)
	if engine != (cronet.Engine{}) {
		engine.CloseAllConnections()
	}
}

func safeCronetEngineShutdown(engine cronet.Engine) (cronet.Result, bool) {
	var err error
	defer recoverCronetPanic(&err)
	if engine == (cronet.Engine{}) {
		return 0, false
	}
	result := engine.Shutdown()
	return result, err == nil
}

func cronetNetError(err error) cronet.NetError {
	if err == nil {
		return 0
	}
	if urlErr, ok := err.(*url.Error); ok {
		err = urlErr.Err
	}
	switch {
	case strings.Contains(err.Error(), "refused"):
		return cronet.NetErrorConnectionRefused
	case strings.Contains(err.Error(), "timeout"):
		return cronet.NetErrorConnectionTimedOut
	case strings.Contains(err.Error(), "network is unreachable"), strings.Contains(err.Error(), "no route"):
		return cronet.NetErrorAddressUnreachable
	default:
		return cronet.NetErrorConnectionFailed
	}
}

func duplicateSocketFD(syscallConn syscall.Conn) (int, error) {
	rawConn, err := syscallConn.SyscallConn()
	if err != nil {
		return -1, E.Cause(err, "get syscall conn")
	}
	var fd int
	var controlError error
	err = rawConn.Control(func(fdPtr uintptr) {
		newFD, dupError := syscall.Dup(int(fdPtr))
		if dupError != nil {
			controlError = E.Cause(dupError, "dup socket fd")
			return
		}
		syscall.CloseOnExec(newFD)
		fd = newFD
	})
	if err != nil {
		return -1, E.Cause(err, "control raw conn")
	}
	if controlError != nil {
		return -1, controlError
	}
	return fd, nil
}
