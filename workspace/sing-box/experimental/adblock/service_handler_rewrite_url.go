//go:build with_adblock

package adblock

import "net/url"

type rewriteURLHandler struct {
	service *Service
}

func (h rewriteURLHandler) Handle(requestContext *adblockRequestContext) (bool, error) {
	if requestContext.checkResult.RewrittenURL == "" || checkResultBlocked(requestContext.checkResult) {
		return false, nil
	}
	rewritten, err := url.Parse(requestContext.checkResult.RewrittenURL)
	if err != nil {
		return false, err
	}
	h.service.debugContext(requestContext.ctx, "HTTP request URL rewritten: ", requestContext.requestURL, " -> ", requestContext.checkResult.RewrittenURL)
	requestContext.setRequestURL(rewritten)
	return forwardHandler{service: h.service}.Handle(requestContext)
}
