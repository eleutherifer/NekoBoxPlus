//go:build with_adblock

package adblock

type forwardHandler struct {
	service *Service
}

func (h forwardHandler) Handle(requestContext *adblockRequestContext) (bool, error) {
	changed, redirected := h.service.readyAdvancedRules().mutateRequest(requestContext, checkResultBlocked(requestContext.checkResult))
	if redirected {
		h.service.debugContext(requestContext.ctx, "HTTP request URL skipped by redirect: ", requestContext.requestURL)
		return true, nil
	}
	if changed {
		h.service.debugContext(requestContext.ctx, "HTTP request URL modified: ", requestContext.requestURL)
		requestContext.check()
		if requestContext.checkResult.Redirect != "" {
			return redirectHandler{service: h.service}.Handle(requestContext)
		}
		if requestContext.checkResult.RewrittenURL != "" && !checkResultBlocked(requestContext.checkResult) {
			return rewriteURLHandler{service: h.service}.Handle(requestContext)
		}
		if checkResultBlocked(requestContext.checkResult) {
			return blockHandler{service: h.service}.Handle(requestContext)
		}
	}
	h.service.debugContext(requestContext.ctx, "HTTP request forwarding: ", requestContext.requestURL)
	return true, h.service.forwardHTTPRequestURL(requestContext)
}
