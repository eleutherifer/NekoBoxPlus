//go:build with_adblock

package adblock

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"errors"
	"io"
	stdlog "log"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	"github.com/sagernet/sing-box/experimental/adblock/ctx"
	"github.com/sagernet/sing-box/experimental/adblock/httpconn"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/ntp"
	"golang.org/x/net/http2"
)

func (s *Service) tlsServerConfig(metadata adapter.InboundContext, outbound adapter.Outbound) *tls.Config {
	return &tls.Config{
		Time:       ntp.TimeFuncFromContext(s.ctx),
		NextProtos: []string{http2.NextProtoTLS, "http/1.1"},
		GetCertificate: func(hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
			s.debug("TLS client hello: server_name=", hello.ServerName, ", supported_protos=", hello.SupportedProtos)
			return s.tlsCA.certificateForServerName(s.ctx, outbound, metadata, hello)
		},
	}
}

type adblockDebugLogger interface {
	Debug(args ...any)
}

type adblockHTTPErrorLogWriter struct {
	logger adblockDebugLogger
}

func (w adblockHTTPErrorLogWriter) Write(message []byte) (int, error) {
	text := strings.TrimSpace(string(message))
	if text == "" || strings.Contains(text, "TLS handshake error") && strings.HasSuffix(text, ": EOF") {
		return len(message), nil
	}
	if w.logger != nil {
		w.logger.Debug(text)
	}
	return len(message), nil
}

func (s *Service) httpServer(ctx *ctx.Conn, forwarder httpconn.ClosableRoundTripper) *PoolItem[*http.Server] {
	scheme := "http"
	if ctx.UseTLS {
		scheme = "https"
	}

	var item *PoolItem[*http.Server]
	if ctx.UseHTTP2 {
		item = s.http2Servers.Get()
	} else {
		item = s.httpServers.Get()
	}

	item.Value.BaseContext = func(net.Listener) context.Context {
		return ctx.Ctx
	}
	item.Value.ErrorLog = stdlog.New(adblockHTTPErrorLogWriter{logger: s.logger}, "", 0)
	item.Value.Handler = http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if s.handleWebAccessibleResourceRequest(writer, request) {
			return
		}
		if s.handleCosmeticSelectorRequest(writer, request) {
			return
		}
		requestType := adblockRequestType(request)
		requestURL := adblockHTTPFilterURL(scheme, request, requestType)
		if requestURL == nil {
			s.debug("forward failed due to URL being absent from the request data")
			return
		}
		normalizeFakeIPRequestURL(requestURL, request, ctx.Metadata)
		s.debugContext(request.Context(), "HTTP request handling: ", request.Method, " ", requestURL, ", type: ", requestType)
		requestContext := newAdblockRequestContext(s, ctx, writer, request, requestURL, requestType, forwarder)
		requestContext.check()
		if err := s.handleAdblockRequest(requestContext); err != nil {
			s.debug("forward failed: ", err)
		}
	})

	return item
}

func (s *Service) peerCertificateIsEV(ctx context.Context, outbound adapter.Outbound, metadata adapter.InboundContext) bool {
	serverName := metadata.Domain
	if serverName == "" && M.IsDomainName(metadata.Destination.Fqdn) {
		serverName = metadata.Destination.Fqdn
	}
	if serverName == "" {
		serverName = metadata.Destination.AddrString()
	}
	certificate := s.tlsCA.peerCertificate(ctx, outbound, metadata.Destination, serverName, []string{http2.NextProtoTLS, "http/1.1"})
	isEV := certificateHasEVPolicy(certificate)
	s.debugContext(ctx, "TLS EV check: server_name=", serverName, ", ev=", isEV)
	return isEV
}

func (s *Service) writeForwardedResponse(writer http.ResponseWriter, response *http.Response, requestMethod string) error {
	copyHTTPHeader(writer.Header(), response.Header)
	removeHopByHopHeaders(writer.Header())
	if response.ContentLength >= 0 {
		writer.Header().Set("Content-Length", strconv.FormatInt(response.ContentLength, 10))
	} else {
		writer.Header().Del("Content-Length")
	}
	writer.WriteHeader(response.StatusCode)
	if !httpResponseAllowsBody(requestMethod, response.StatusCode) || response.Body == nil {
		return nil
	}
	streaming := response.ContentLength < 0 || mediaType(response.Header.Get("Content-Type")) == "text/event-stream"
	if streaming {
		controller := http.NewResponseController(writer)
		if err := controller.Flush(); err != nil && !httpFeatureNotSupported(err) {
			return err
		}
		_, err := io.Copy(flushingResponseWriter{writer: writer, controller: controller}, response.Body)
		return firstUnexpectedTunnelError(err)
	}
	_, err := io.Copy(writer, response.Body)
	return firstUnexpectedTunnelError(err)
}

type flushingResponseWriter struct {
	writer     io.Writer
	controller *http.ResponseController
}

func (w flushingResponseWriter) Write(content []byte) (int, error) {
	written, err := w.writer.Write(content)
	if err != nil {
		return written, err
	}
	if err = w.controller.Flush(); err != nil && !httpFeatureNotSupported(err) {
		return written, err
	}
	return written, nil
}

func httpFeatureNotSupported(err error) bool {
	return errors.Is(err, http.ErrNotSupported) || errors.Is(err, errors.ErrUnsupported)
}

func httpResponseAllowsBody(requestMethod string, statusCode int) bool {
	return requestMethod != http.MethodHead && statusCode >= 200 && statusCode != http.StatusNoContent && statusCode != http.StatusNotModified
}

func forwardHTTPUpgrade(writer http.ResponseWriter, response *http.Response) error {
	upstream, loaded := response.Body.(io.ReadWriteCloser)
	if !loaded {
		_ = response.Body.Close()
		return E.New("upstream upgrade response body is not bidirectional")
	}
	downstream, buffered, err := http.NewResponseController(writer).Hijack()
	if err != nil {
		_ = upstream.Close()
		return err
	}
	responseCopy := new(http.Response)
	*responseCopy = *response
	responseCopy.Body = nil
	responseCopy.ContentLength = 0
	responseCopy.TransferEncoding = nil
	if err = responseCopy.Write(buffered); err == nil {
		err = buffered.Flush()
	}
	if err != nil {
		_ = downstream.Close()
		_ = upstream.Close()
		return err
	}

	errorChannel := make(chan error, 2)
	var copyGroup sync.WaitGroup
	copyGroup.Go(func() {
		_, copyErr := io.Copy(upstream, buffered)
		errorChannel <- copyErr
	})
	copyGroup.Go(func() {
		_, copyErr := io.Copy(downstream, upstream)
		errorChannel <- copyErr
	})
	firstErr := <-errorChannel
	_ = downstream.Close()
	_ = upstream.Close()
	secondErr := <-errorChannel
	copyGroup.Wait()
	return firstUnexpectedTunnelError(firstErr, secondErr)
}

func firstUnexpectedTunnelError(errs ...error) error {
	for _, err := range errs {
		if err == nil || errors.Is(err, io.EOF) || errors.Is(err, net.ErrClosed) || E.IsClosedOrCanceled(err) {
			continue
		}
		return err
	}
	return nil
}

func (s *Service) shouldRewriteHTML(response *http.Response) bool {
	// Cosmetic filtering is applied to a response whenever it is an HTML/XHTML
	// document the browser will render, mirroring how uBlock Origin injects its
	// content filters into rendered document frames regardless of request type.
	// A document fetched by a service worker (sec-fetch-dest: empty) is
	// classified as an XHR but is still rendered as a document via respondWith,
	// so gating on the inferred request type would wrongly skip it. The reliable
	// signal at the network layer is the response Content-Type. Compression is
	// handled separately by prepareRewritableBody.
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return false
	}
	mediaType, _, err := mime.ParseMediaType(response.Header.Get("Content-Type"))
	if err != nil {
		return strings.Contains(strings.ToLower(response.Header.Get("Content-Type")), "text/html")
	}
	return strings.EqualFold(mediaType, "text/html") || strings.EqualFold(mediaType, "application/xhtml+xml")
}

func (s *Service) rewriteHTMLResponse(engine adblockrust.Engine, requestURL string, response *http.Response) (bool, error) {
	state, err := s.loadBrowserFilterState(engine, requestURL)
	if err != nil {
		return false, err
	}
	if state.cosmetic.GenericHide {
		if !s.cronet && !s.prepareRewritableBody(response) {
			return false, nil
		}
		streamBody, styleSource, scriptSource, streamed, handled, err := s.streamBrowserFiltersWithState(state, response.Body)
		if err != nil {
			return false, err
		}
		if handled {
			if streamed {
				response.Body = streamBody
				response.ContentLength = -1
				response.Header.Del("Content-Encoding")
				response.Header.Del("Content-Length")
				patchCSPHeaders(response.Header, styleSource, scriptSource)
			}
			return true, nil
		}
	}
	limit := s.options.Filtering.ReplaceMaxBodyValue()
	body, ok, err := s.readBoundedRewritableBody(http.MethodGet, response, limit, true)
	if err != nil {
		return false, err
	}
	if !ok {
		return false, nil
	}
	rewritten, changed, styleSource, scriptSource, err := s.injectBrowserFiltersWithState(engine, state, body.content)
	if err != nil {
		body.restore(response)
		return false, err
	}
	if !changed {
		body.restore(response)
		return false, nil
	}
	body.replace(response, rewritten)
	patchCSPHeaders(response.Header, styleSource, scriptSource)
	return true, nil
}

func writeRedirectResource(writer http.ResponseWriter, resource string) error {
	mediaType, content, err := decodeRedirectResource(resource)
	if err != nil {
		return err
	}
	if mediaType != "" {
		writer.Header().Set("Content-Type", mediaType)
	}
	writer.Header().Set("Content-Length", strconv.Itoa(len(content)))
	writer.WriteHeader(http.StatusOK)
	_, err = writer.Write(content)
	return err
}

func decodeRedirectResource(resource string) (string, []byte, error) {
	if !strings.HasPrefix(resource, "data:") {
		return "", nil, E.New("unsupported redirect resource")
	}
	header, payload, found := strings.Cut(resource[len("data:"):], ",")
	if !found {
		return "", nil, E.New("invalid redirect resource")
	}
	parts := strings.Split(header, ";")
	mediaType := parts[0]
	base64Encoded := false
	for _, part := range parts[1:] {
		if strings.EqualFold(part, "base64") {
			base64Encoded = true
		}
	}
	if !base64Encoded {
		content, err := url.QueryUnescape(payload)
		if err != nil {
			return "", nil, err
		}
		return mediaType, []byte(content), nil
	}
	content, err := base64.StdEncoding.DecodeString(payload)
	if err != nil {
		return "", nil, err
	}
	return mediaType, content, nil
}
