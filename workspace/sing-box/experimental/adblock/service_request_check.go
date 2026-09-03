//go:build with_adblock

package adblock

import (
	"net/http"
	"net/url"

	"github.com/sagernet/sing-box/common/adblock/adblockrust"
)

func (s *Service) requestCheck(engine adblockrust.Engine, requestURL string, sourceURL string, requestType string, method adblockrust.RequestMethod) (adblockrust.CheckResult, error) {
	key := adblockCheckCacheKey{
		requestURL:  requestURL,
		sourceURL:   sourceURL,
		requestType: requestType,
		method:      method,
	}
	if result, loaded := s.checkCacheGet(key); loaded {
		return result, nil
	}
	result, err := engine.CheckDetailedNoFilter(requestURL, sourceURL, requestType, method)
	if err != nil {
		s.debug("check failed: ", err)
		return adblockrust.CheckResult{}, err
	}
	s.checkCacheStore(key, result)
	return result, nil
}

func (s *Service) requestException(engine adblockrust.Engine, requestURL string, sourceURL string, requestType string, method adblockrust.RequestMethod) bool {
	key := adblockCheckCacheKey{
		requestURL:  requestURL,
		sourceURL:   sourceURL,
		requestType: requestType,
		method:      method,
	}
	if cached, loaded := s.exceptionCacheGet(key); loaded {
		return cached
	}
	matched, err := engine.CheckException(requestURL, sourceURL, requestType, method)
	if err != nil {
		s.debug("exception check failed: ", requestURL, ", type: ", requestType, ", error: ", err)
		return false
	}
	s.exceptionCacheStore(key, matched)
	return matched
}

func (s *Service) httpRequestCheck(engine adblockrust.Engine, request *http.Request, requestURL *url.URL, requestType string) adblockrust.CheckResult {
	requestContext := &adblockRequestContext{
		service:     s,
		ctx:         request.Context(),
		engine:      engine,
		request:     request,
		requestURL:  requestURL,
		requestType: requestType,
	}
	return requestContext.check()
}

func (c *adblockRequestContext) check() adblockrust.CheckResult {
	sourceURL, err := c.sourceURLValue()
	if err != nil {
		c.service.debugContext(c.ctx, "failed to check HTTP request, source URL parser error: ", c.requestURL, ", type: ", c.requestType, ", err: ", err)
		c.service.stats.recordRequest(false)
		c.checkResult = adblockrust.CheckResult{Exception: "NetworkFilter"}
		return c.checkResult
	}
	requestURLString := c.requestURLValue()
	sourceURLString, _ := c.sourceURLValueString()
	method := adblockrust.ParseRequestMethod(c.request.Method)
	result, err := c.service.requestCheck(c.engine, requestURLString, sourceURLString, c.requestType, method)
	if err == nil {
		result = c.service.readyAdvancedRules().applyCheck(c, result)
	}
	if err == nil && checkResultActionable(result) {
		if checkResultBlocked(result) && !result.Important && c.service.sourceDocumentException(c.engine, sourceURLString) {
			c.service.debugContext(c.ctx, "HTTP request allowed by source document exception: ", c.requestURL, ", type: ", c.requestType, ", source: ", sourceURL)
			c.service.stats.recordRequest(false)
			c.checkResult = adblockrust.CheckResult{Exception: "NetworkFilter"}
			return c.checkResult
		}
		blocked := checkResultBlocked(result)
		c.service.debugContext(c.ctx, "HTTP request checked: ", c.requestURL, ", type: ", c.requestType, ", blocked: ", blocked, ", redirect: ", result.Redirect != "", ", rewrite: ", result.RewrittenURL != "", ", exception: ", result.Exception != "")
		c.service.stats.recordRequest(blocked)
		c.checkResult = result
		return c.checkResult
	}
	if shouldForceExceptionCheck(c.requestType, sourceURLString) && c.service.requestException(c.engine, requestURLString, sourceURLString, c.requestType, method) {
		c.service.debugContext(c.ctx, "HTTP request excluded by exception: ", c.requestURL, ", type: ", c.requestType, ", source: ", sourceURL)
		c.service.stats.recordRequest(false)
		c.checkResult = adblockrust.CheckResult{Exception: "NetworkFilter"}
		return c.checkResult
	}
	inferredRequestType := adblockRequestTypeFromURL(requestURLString)
	if !shouldCheckInferredRequestType(c.requestType, inferredRequestType) {
		c.service.debugContext(c.ctx, "HTTP request allowed: ", c.requestURL, ", type: ", c.requestType)
		c.service.stats.recordRequest(false)
		c.checkResult = result
		return c.checkResult
	}
	inferredResult, err := c.service.requestCheck(c.engine, requestURLString, sourceURLString, inferredRequestType, method)
	if err == nil {
		inferredResult = c.service.readyAdvancedRules().applyCheck(c, inferredResult)
	}
	if err != nil {
		c.service.debugContext(c.ctx, "HTTP inferred check failed: ", c.requestURL, ", type: ", inferredRequestType, ", error: ", err)
		c.service.stats.recordRequest(false)
		c.checkResult = result
		return c.checkResult
	}
	blocked := checkResultBlocked(inferredResult)
	if blocked && !inferredResult.Important && c.service.sourceDocumentException(c.engine, sourceURLString) {
		c.service.debugContext(c.ctx, "HTTP inferred request allowed by source document exception: ", c.requestURL, ", type: ", inferredRequestType, ", source: ", sourceURL)
		c.service.stats.recordRequest(false)
		c.checkResult = adblockrust.CheckResult{Exception: "NetworkFilter"}
		return c.checkResult
	}
	c.service.debugContext(c.ctx, "HTTP inferred result: ", c.requestURL, ", type: ", inferredRequestType, ", blocked: ", blocked, ", redirect: ", inferredResult.Redirect != "", ", rewrite: ", inferredResult.RewrittenURL != "", ", exception: ", inferredResult.Exception != "")
	c.service.stats.recordRequest(blocked)
	c.checkResult = inferredResult
	return c.checkResult
}

func (s *Service) sourceDocumentException(engine adblockrust.Engine, sourceURL string) bool {
	if sourceURL == "" {
		return false
	}
	return s.requestException(engine, sourceURL, sourceURL, "document", adblockrust.RequestMethodGet)
}

func checkResultBlocked(result adblockrust.CheckResult) bool {
	return result.Matched && (result.Exception == "" || result.Important)
}

func checkResultException(result adblockrust.CheckResult) bool {
	return result.Exception != "" && !result.Important
}

func checkResultActionable(result adblockrust.CheckResult) bool {
	return checkResultBlocked(result) || result.Exception != "" || result.Redirect != "" || result.RewrittenURL != ""
}

func shouldForceExceptionCheck(requestType string, sourceURL string) bool {
	return requestType == "document" || requestType == "subdocument" || sourceURL != ""
}

func shouldCheckInferredRequestType(requestType string, inferredRequestType string) bool {
	return requestType == "document" && inferredRequestType != "other" && inferredRequestType != requestType
}
