//go:build with_adblock

package adblock

import "net/http"

type redirectHandler struct {
	service *Service
}

func (h redirectHandler) Handle(requestContext *adblockRequestContext) (bool, error) {
	if requestContext.checkResult.Redirect == "" {
		return false, nil
	}
	h.service.debugContext(requestContext.ctx, "HTTP request redirected: ", requestContext.requestURL)
	if err := writeRedirectResource(requestContext.writer, requestContext.checkResult.Redirect); err != nil {
		h.service.debug("redirect failed: ", err)
		requestContext.writer.Header().Set("Content-Length", "0")
		requestContext.writer.WriteHeader(http.StatusInternalServerError)
	}
	return true, nil
}
