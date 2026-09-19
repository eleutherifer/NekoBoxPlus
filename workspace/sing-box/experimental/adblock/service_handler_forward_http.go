//go:build with_adblock

package adblock

import (
	"mime"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	"github.com/sagernet/sing-box/experimental/adblock/assets"
	"github.com/sagernet/sing-box/experimental/adblock/ctx"
	"github.com/sagernet/sing-box/experimental/adblock/httpconn"
)

func (s *Service) forwardHTTPRequestURL(requestContext *adblockRequestContext) error {
	if requestContext.useTLS && requestContext.request.ProtoMajor == 3 && !s.cronet {
		s.debugContext(requestContext.ctx, "forwarding HTTP/3 request")
		return s.forwardHTTP3RequestURL(requestContext)
	}
	response, err := s.roundTripForwardedHTTPRequest(requestContext)
	if err != nil {
		return s.writeForwardRoundTripError(requestContext, response, err)
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusSwitchingProtocols {
		s.debugContext(requestContext.ctx, "forwarding upgraded response: ", requestContext.requestURL)
		return forwardHTTPUpgrade(requestContext.writer, response)
	}
	s.debugContext(requestContext.ctx, "forward response: ", requestContext.requestURL, ", status: ", response.StatusCode)
	if err = s.filterForwardedHTTPResponse(requestContext, response); err != nil {
		return err
	}
	return s.writeForwardedResponse(requestContext.writer, response, requestContext.request.Method)
}

func (s *Service) roundTripForwardedHTTPRequest(requestContext *adblockRequestContext) (*http.Response, error) {
	if err := adblockHTTPForwardURL(requestContext.requestURL); err != nil {
		return nil, err
	}
	outRequest := newForwardedHTTPRequest(requestContext)
	if err := http.NewResponseController(requestContext.writer).EnableFullDuplex(); err != nil && !httpFeatureNotSupported(err) {
		return nil, err
	}
	if requestContext.forwarder == nil {
		connContext := &ctx.Conn{Outbound: requestContext.outbound, UseTLS: requestContext.useTLS, UTLS: s.utls, Cronet: s.cronet}
		requestContext.forwarder = httpconn.NewHTTPForwarder(s.ctx, connContext)
		defer requestContext.forwarder.Close()
	}
	return requestContext.forwarder.RoundTrip(outRequest)
}

func newForwardedHTTPRequest(requestContext *adblockRequestContext) *http.Request {
	outRequest := requestContext.request.Clone(requestContext.ctx)
	outRequest.URL = requestContext.requestURL
	outRequest.Host = requestContext.requestURL.Host
	outRequest.RequestURI = ""
	return outRequest
}

func (s *Service) writeForwardRoundTripError(requestContext *adblockRequestContext, response *http.Response, err error) error {
	s.debugContext(requestContext.ctx, "forward round trip failed: ", requestContext.requestURL, ": ", err)
	if response != nil && response.Body != nil {
		_ = response.Body.Close()
	}
	desc := textsForRoundTripError(err)
	rawError := ""
	if err != nil {
		rawError = err.Error()
	}
	if !requestAcceptsErrorHTML(requestContext) {
		writeForwardRoundTripErrorText(requestContext.writer, desc, rawError, requestContext.requestURLValue())
		return err
	}
	errPage, pageBuildErr := assets.GetErrorPage(assets.ErrorContext{
		Heading:            desc.Heading,
		TitleHumanReadable: desc.TitleHumanReadable,
		Description:        desc.Description,
		RawError:           rawError,
		URL:                requestContext.requestURLValue(),
		Timestamp:          time.Now().Format(time.RFC3339),
	})
	if pageBuildErr != nil {
		s.debug("failed to serve proper error page, falling back to text:", pageBuildErr)
		writeForwardRoundTripErrorText(requestContext.writer, desc, rawError, requestContext.requestURLValue())
		return err
	}
	requestContext.writer.Header().Set("Content-Type", "text/html; charset=UTF-8")
	requestContext.writer.WriteHeader(http.StatusServiceUnavailable)
	requestContext.writer.Write(errPage)
	return err
}

func requestAcceptsErrorHTML(requestContext *adblockRequestContext) bool {
	if requestContext == nil || requestContext.request == nil {
		return false
	}
	for accept := range strings.SplitSeq(requestContext.request.Header.Get("Accept"), ",") {
		mediaType, params, err := mime.ParseMediaType(strings.TrimSpace(accept))
		if err != nil {
			continue
		}
		mediaType = strings.ToLower(mediaType)
		if mediaType != "text/html" && mediaType != "application/xhtml+xml" {
			continue
		}
		if q, ok := params["q"]; ok {
			qValue, err := strconv.ParseFloat(q, 64)
			if err != nil || qValue <= 0 {
				continue
			}
		}
		return true
	}
	return false
}

func writeForwardRoundTripErrorText(writer http.ResponseWriter, desc roundTripErrorTexts, rawError string, requestURL string) {
	var body strings.Builder
	body.WriteString(desc.TitleHumanReadable)
	body.WriteString("\n\n")
	body.WriteString(desc.Description)
	if requestURL != "" {
		body.WriteString("\n\nURL: ")
		body.WriteString(requestURL)
	}
	if rawError != "" {
		body.WriteString("\n\nError: ")
		body.WriteString(rawError)
	}
	body.WriteByte('\n')

	writer.Header().Set("Content-Type", "text/plain; charset=UTF-8")
	writer.WriteHeader(http.StatusServiceUnavailable)
	writer.Write([]byte(body.String()))
}

func (s *Service) filterForwardedHTTPResponse(requestContext *adblockRequestContext, response *http.Response) error {
	if requestContext.skipResponseFiltering || shouldSkipResponseFiltering(requestContext) {
		return nil
	}
	companion := s.readyCompanion()
	companion.applyHeaders(requestContext, response)
	htmlFilters := s.readyHTMLFilters()
	htmlFilters.applyHeaders(requestContext, response)
	if _, err := companion.applyReplace(s, requestContext, response); err != nil {
		s.debug("response replace failed: ", err)
	}
	if s.shouldRewriteHTML(response) {
		if _, err := htmlFilters.apply(s, requestContext, response); err != nil {
			s.debug("HTML filtering failed: ", err)
		}
	}
	s.applyCSPDirectives(requestContext, response)
	if !s.shouldRewriteHTML(response) {
		return nil
	}
	handled, err := s.rewriteHTMLResponse(requestContext.engine, requestContext.requestURLValue(), response)
	if err != nil {
		s.debug("browser filtering failed: ", err)
	}
	if !handled && err == nil {
		s.debug("browser filtering skipped, nothing to add")
	}
	return nil
}

func shouldSkipResponseFiltering(requestContext *adblockRequestContext) bool {
	if requestContext == nil || requestContext.requestURL == nil {
		return false
	}
	if isCloudflareChallengeHost(requestContext.requestURL.Hostname()) {
		return true
	}
	return strings.HasPrefix(requestContext.requestURL.EscapedPath(), "/cdn-cgi/challenge-platform/")
}

func isCloudflareChallengeHost(host string) bool {
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	return host == "challenges.cloudflare.com" || strings.HasSuffix(host, ".challenges.cloudflare.com")
}

func (s *Service) applyCSPDirectives(requestContext *adblockRequestContext, response *http.Response) {
	if requestContext.requestType != "document" && requestContext.requestType != "subdocument" {
		return
	}
	if _, err := requestContext.sourceURLValue(); err != nil {
		s.debug("CSP check failed due to URL parsing error: ", err)
		return
	}
	requestURLString := requestContext.requestURLValue()
	sourceURLString, _ := requestContext.sourceURLValueString()
	directives, err := requestContext.engine.CSPDirectives(requestURLString, sourceURLString, requestContext.requestType, adblockrust.ParseRequestMethod(requestContext.request.Method))
	if err != nil {
		s.debug("CSP check failed: ", err)
		return
	}
	if strings.TrimSpace(directives) == "" {
		return
	}
	s.debugContext(requestContext.ctx, "applying CSP directives: ", requestURLString)
	response.Header.Add("Content-Security-Policy", directives)
}
