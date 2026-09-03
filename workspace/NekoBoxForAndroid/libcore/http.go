package libcore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/ech"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	WithUTLS(name string)
	TrySocks5(addr string, port int32, username string, password string)
	TryH3Direct()
	KeepAlive()
	SetTimeoutMillis(timeoutMillis int64)
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetMethod(method string)
	SetHeader(key string, value string)
	SetContent(content []byte)
	SetContentString(content string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	Cancel()
	Execute() (HTTPResponse, error)
}

type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContent() ([]byte, error)
	GetContentString() (*StringBox, error)
	WriteTo(path string) error
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	tls           tls.Config
	h1h2Transport http.Transport
	h1h2Client    http.Client
	trySocks5     bool
	tryH3Direct   bool
	utlsName      string
	utlsTransport *utlsRoundTripper
}

func NewHttpClient() HTTPClient {
	client := new(httpClient)
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.DisableKeepAlives = true
	return client
}

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
	// c.tls.CipherSuites = nekoutils.Map(tls.CipherSuites(), func(it *tls.CipherSuite) uint16 { return it.ID })
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
	// c.tls.CipherSuites = nekoutils.Map(nekoutils.Filter(tls.CipherSuites(), func(it *tls.CipherSuite) bool {
	// 	return nekoutils.Contains(it.SupportedVersions, uint16(tls.VersionTLS13))
	// }), func(it *tls.CipherSuite) uint16 {
	// 	return it.ID
	// })
}

func (c *httpClient) PinnedTLS12() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
}

func (c *httpClient) WithUTLS(name string) {
	if name == "" {
		return
	}
	c.utlsName = name
	c.utlsTransport = newUTLSRoundTripper(c)
	c.h1h2Client.Transport = c.utlsTransport
}

func (c *httpClient) TrySocks5(listenerAddr string, port int32, username string, password string) {
	dialer := new(net.Dialer)
	c.h1h2Transport.DialContext = func(ctx context.Context, network, destination string) (net.Conn, error) {
		for {
			socksConn, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(listenerAddr, strconv.Itoa(int(port))))
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(destination), username, password)
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			return socksConn, err
		}
		return dialer.DialContext(ctx, network, destination)
	}
	c.trySocks5 = true
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) KeepAlive() {
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) SetTimeoutMillis(timeoutMillis int64) {
	c.h1h2Client.Timeout = time.Duration(timeoutMillis) * time.Millisecond
}

func (c *httpClient) NewRequest() HTTPRequest {
	req := &httpRequest{httpClient: c}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	return req
}

func (c *httpClient) Close() {
	if c.utlsTransport != nil {
		c.utlsTransport.Close()
	}
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	request       http.Request
	cancelAccess  sync.Mutex
	cancelRequest context.CancelFunc
	cancelled     bool
}

func (r *httpRequest) Cancel() {
	r.cancelAccess.Lock()
	r.cancelled = true
	cancel := r.cancelRequest
	r.cancelAccess.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (r *httpRequest) AllowInsecure() {
	r.tls.InsecureSkipVerify = true
}

func (r *httpRequest) SetURL(link string) (err error) {
	r.request.URL, err = url.Parse(link)
	if err != nil {
		return
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
	}
	return
}

func (r *httpRequest) SetMethod(method string) {
	r.request.Method = method
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetContent(content []byte) {
	buffer := bytes.Buffer{}
	buffer.Write(content)
	r.request.Body = io.NopCloser(bytes.NewReader(buffer.Bytes()))
	r.request.ContentLength = int64(len(content))
}

func (r *httpRequest) SetContentString(content string) {
	r.SetContent([]byte(content))
}

func (r *httpRequest) Execute() (HTTPResponse, error) {
	defer device.DeferPanicToError("http execute", func(err error) { log.Println(err) })
	ctx, cancel := context.WithCancel(context.Background())
	finish := sync.OnceFunc(func() {
		cancel()
		r.cancelAccess.Lock()
		r.cancelRequest = nil
		r.cancelAccess.Unlock()
	})
	r.cancelAccess.Lock()
	if r.cancelled {
		r.cancelAccess.Unlock()
		finish()
		return nil, context.Canceled
	}
	r.cancelRequest = cancel
	r.cancelAccess.Unlock()
	request := r.request.Clone(ctx)
	var response HTTPResponse
	var err error
	// full direct
	if r.tryH3Direct && !r.trySocks5 && r.utlsName == "" {
		response, err = r.doH3Direct(ctx)
	} else {
		var rawResponse *http.Response
		rawResponse, err = r.h1h2Client.Do(request) //nolint:bodyclose // successful bodies are owned by the returned httpResponse.
		if err != nil && r.tryH3Direct && r.utlsName == "" && errors.Is(err, errFailConnectSocks5) {
			response, err = r.doH3Direct(ctx)
		} else if err == nil {
			httpResp := &httpResponse{Response: rawResponse}
			if rawResponse.StatusCode != http.StatusOK {
				err = errors.New(httpResp.errorString())
			} else {
				response = httpResp
			}
		}
	}
	if err != nil {
		finish()
		return nil, err
	}
	httpResp := response.(*httpResponse)
	if httpResp.Body == nil {
		finish()
	} else {
		httpResp.Body = &closeOnCloseReadCloser{
			ReadCloser: httpResp.Body,
			closeFunc: func() error {
				finish()
				return nil
			},
		}
	}
	return response, nil
}

type requestFunc func() (response *http.Response, err error)

type closeOnCloseReadCloser struct {
	io.ReadCloser
	closeFunc func() error
	once      sync.Once
}

func (c *closeOnCloseReadCloser) Close() error {
	var err error
	c.once.Do(func() {
		err = c.ReadCloser.Close()
		if c.closeFunc != nil {
			err = errors.Join(err, c.closeFunc())
		}
	})
	return err
}

func (r *httpRequest) doH3Direct(parent context.Context) (HTTPResponse, error) {
	timeout := r.h1h2Client.Timeout
	if timeout == 0 {
		timeout = 10 * time.Second
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	cancelOnReturn := true
	defer func() {
		if cancelOnReturn {
			cancel()
		}
	}()

	successCh := make(chan *http.Response, 1)
	var finalErr error
	var failedCount atomic.Uint32
	var successCount atomic.Uint32
	var mu sync.Mutex

	funcs := []requestFunc{
		// Http(s) With Ech
		func() (response *http.Response, err error) {
			request := r.request.Clone(ctx)
			transport := &http.Transport{
				DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
					var d net.Dialer
					c, err := d.DialContext(ctx, network, addr)
					if err != nil {
						return c, err
					}
					domain := addr
					if host, _, splitErr := net.SplitHostPort(addr); splitErr == nil && host != "" {
						domain = host
					}
					echTls := ech.NewECHClientConfig(domain, &r.tls, gLocalDNSTransport)
					return echTls.Client(ctx, c)
				},
				DisableKeepAlives: true,
			}
			echClient := &http.Client{
				Transport: transport,
			}
			response, err = echClient.Do(request)
			if err != nil {
				transport.CloseIdleConnections()
				return response, err
			}
			if response != nil && response.Body != nil {
				response.Body = &closeOnCloseReadCloser{
					ReadCloser: response.Body,
					closeFunc: func() error {
						transport.CloseIdleConnections()
						return nil
					},
				}
			}
			return response, nil
		},
		// H3 HTTPS
		func() (response *http.Response, err error) {
			request := r.request.Clone(ctx)
			transport := &http3.Transport{
				TLSClientConfig: r.tls.Clone(),
				QUICConfig: &quic.Config{
					MaxIdleTimeout: time.Second,
				},
			}
			h3Client := &http.Client{
				Transport: transport,
			}
			response, err = h3Client.Do(request)
			if err != nil {
				return response, errors.Join(err, transport.Close())
			}
			if response != nil && response.Body != nil {
				response.Body = &closeOnCloseReadCloser{
					ReadCloser: response.Body,
					closeFunc:  transport.Close,
				}
			}
			return response, nil
		},
	}

	if r.request.URL.Scheme == "http" {
		funcs = funcs[:1]
	}

	for i, f := range funcs {
		go func(f requestFunc) {
			defer device.DeferPanicToError("http", func(err error) { log.Println(err) })
			defer func() {
				if successCount.Load() == 0 {
					if failedCount.Add(1) >= uint32(len(funcs)) {
						// 全部失败了
						cancel()
					}
				}
			}()

			var t string
			switch i {
			case 0:
				t = "http(s)"
			case 1:
				t = "h3"
			}

			// 执行HTTP请求
			rsp, err := f() //nolint:bodyclose // successful bodies are sent to successCh and owned by the returned httpResponse.
			if rsp == nil || err != nil {
				mu.Lock()
				finalErr = errors.Join(finalErr, fmt.Errorf("%s: %w", t, err))
				mu.Unlock()
				if rsp != nil && rsp.Body != nil {
					closeErr := rsp.Body.Close()
					if closeErr != nil {
						mu.Lock()
						finalErr = errors.Join(finalErr, fmt.Errorf("%s close body: %w", t, closeErr))
						mu.Unlock()
					}
				}
				return
			}

			// 处理 HTTP 状态码
			if rsp.StatusCode != http.StatusOK {
				hr := &httpResponse{Response: rsp}
				err = fmt.Errorf("%s: %s", t, hr.errorString())
				mu.Lock()
				finalErr = errors.Join(finalErr, err)
				mu.Unlock()
				return
			}

			select {
			case successCh <- rsp:
				// 第一个成功的请求，不要关闭 body
				successCount.Add(1)
			default:
				if closeErr := rsp.Body.Close(); closeErr != nil {
					log.Println("close duplicate http response:", closeErr)
				}
			}
		}(f)
	}

	select {
	case result := <-successCh:
		cancelOnReturn = false
		if result.Body != nil {
			result.Body = &closeOnCloseReadCloser{
				ReadCloser: result.Body,
				closeFunc: func() error {
					cancel()
					return nil
				},
			}
		}
		return &httpResponse{Response: result}, nil
	case <-ctx.Done():
		return nil, finalErr
	}
}

type httpResponse struct {
	*http.Response

	getContentOnce sync.Once
	content        []byte
	contentError   error
}

func (h *httpResponse) errorString() string {
	content, err := h.getContentString()
	if err != nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
	if len(content) > 100 {
		content = content[:100] + " ..."
	}
	return fmt.Sprint("HTTP ", h.Status, ": ", content)
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer func() {
			h.contentError = errors.Join(h.contentError, h.Body.Close())
		}()
		h.content, h.contentError = io.ReadAll(h.Body)
	})
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.GetContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) (err error) {
	defer func() {
		err = errors.Join(err, h.Body.Close())
	}()
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer func() {
		err = errors.Join(err, common.Close(file))
	}()
	_, err = io.Copy(file, h.Body)
	return err
}
