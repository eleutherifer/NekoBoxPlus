//go:build with_adblock

package adblock

type exceptionHandler struct {
	service *Service
}

func (h exceptionHandler) Handle(requestContext *adblockRequestContext) (bool, error) {
	if requestContext.checkResult.Exception == "" || requestContext.checkResult.Important {
		return false, nil
	}
	h.service.debugContext(requestContext.ctx, "HTTP request allowed by exception: ", requestContext.requestURL)
	if requestContext.requestType == "document" || requestContext.requestType == "subdocument" {
		requestContext.skipResponseFiltering = true
	}
	return forwardHandler{service: h.service}.Handle(requestContext)
}
