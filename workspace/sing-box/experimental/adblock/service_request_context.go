//go:build with_adblock

package adblock

import (
	"context"
	"net/http"
	"net/url"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	"github.com/sagernet/sing-box/experimental/adblock/ctx"
	"github.com/sagernet/sing-box/experimental/adblock/httpconn"
)

type adblockRequestContext struct {
	service     *Service
	ctx         context.Context
	engine      adblockrust.Engine
	writer      http.ResponseWriter
	outbound    adapter.Outbound
	metadata    adapter.InboundContext
	request     *http.Request
	requestURL  *url.URL
	useTLS      bool
	requestType string
	forwarder   httpconn.ClosableRoundTripper
	checkResult adblockrust.CheckResult

	skipResponseFiltering bool
	requestURLString      string
	sourceURL             *url.URL
	sourceURLString       string
	sourceURLError        error
}

func newAdblockRequestContext(s *Service, connContext *ctx.Conn, writer http.ResponseWriter, request *http.Request, requestURL *url.URL, requestType string, forwarder httpconn.ClosableRoundTripper) *adblockRequestContext {
	return &adblockRequestContext{
		service:     s,
		ctx:         request.Context(),
		engine:      connContext.Engine,
		writer:      writer,
		outbound:    connContext.Outbound,
		metadata:    connContext.Metadata,
		request:     request,
		requestURL:  requestURL,
		useTLS:      connContext.UseTLS,
		requestType: requestType,
		forwarder:   forwarder,
	}
}

func (c *adblockRequestContext) setRequestURL(requestURL *url.URL) {
	c.requestURL = requestURL
	c.requestURLString = ""
	c.sourceURL = nil
	c.sourceURLString = ""
	c.sourceURLError = nil
}

func (c *adblockRequestContext) requestURLValue() string {
	if c.requestURLString == "" && c.requestURL != nil {
		c.requestURLString = c.requestURL.String()
	}
	return c.requestURLString
}

func (c *adblockRequestContext) sourceURLValue() (*url.URL, error) {
	if c.sourceURL != nil || c.sourceURLError != nil {
		return c.sourceURL, c.sourceURLError
	}
	c.sourceURL, c.sourceURLError = adblockSourceURL(c.request, c.requestURL, c.requestType)
	return c.sourceURL, c.sourceURLError
}

func (c *adblockRequestContext) sourceURLValueString() (string, error) {
	if c.sourceURLString != "" || c.sourceURLError != nil {
		return c.sourceURLString, c.sourceURLError
	}
	sourceURL, err := c.sourceURLValue()
	if err != nil {
		return "", err
	}
	c.sourceURLString = sourceURL.String()
	return c.sourceURLString, nil
}
