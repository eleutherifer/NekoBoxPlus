//go:build with_adblock

package adblock

import (
	"net/http"

	"github.com/sagernet/sing-box/option"
)

type blockHandler struct {
	service *Service
}

func (h blockHandler) Handle(requestContext *adblockRequestContext) (bool, error) {
	if !checkResultBlocked(requestContext.checkResult) {
		return false, nil
	}
	h.service.debugContext(requestContext.ctx, "HTTP request blocked: ", requestContext.requestURL, ", mode: ", h.service.options.Filtering.Mode)
	if h.service.options.Filtering.Mode == option.AdblockModeEmptyResponse {
		requestContext.writer.Header().Set("Content-Length", "0")
		requestContext.writer.Header().Set("X-Adblocked", "1")
		requestContext.writer.WriteHeader(http.StatusNoContent)
		return true, nil
	}
	hijackedConn, _, err := http.NewResponseController(requestContext.writer).Hijack()
	if err == nil {
		_ = hijackedConn.Close()
		return true, nil
	}
	if httpFeatureNotSupported(err) {
		h.service.debugContext(requestContext.ctx, "HTTP block hijack unsupported, using empty response")
	} else {
		h.service.debugContext(requestContext.ctx, "HTTP block hijack failed, using empty response: ", err)
	}
	requestContext.writer.Header().Set("Content-Length", "0")
	requestContext.writer.Header().Set("X-Adblocked", "1")
	requestContext.writer.WriteHeader(http.StatusNoContent)
	return true, nil
}
