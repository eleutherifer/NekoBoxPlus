//go:build with_adblock

package adblock

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	"github.com/sagernet/sing-box/option"
)

var replayBrowsingRules = []string{
	`||ads.example^$script,third-party`,
	`@@||ads.example/allowed.js$script,domain=news.example`,
	`||metrics.example/collect$xmlhttprequest,third-party`,
	`||cdn.example/adframe.html$subdocument,third-party`,
	`||images.example/banner*.jpg$image,third-party`,
	`||tracker.example/path$removeparam=/^utm_/`,
	`||short.example/out$document,urlskip=?target -uricomponent +https`,
	`||rewrite.example/start$uritransform=/start/final/`,
	`||rewrite.example/final$xmlhttprequest`,
	`||case.example/CaseAd.js$script,match-case,to=case.example`,
	`news.example##.sponsor`,
	`news.example##^aside.sponsor`,
	`news.example##^div.ad-slot`,
	`news.example##^script:has-text(/adPlacements/)`,
}

type replayBrowsingRequest struct {
	name     string
	method   string
	rawURL   string
	referer  string
	accept   string
	dest     string
	response string
}

type replayBrowsingResult struct {
	name             string
	requestType      string
	blocked          bool
	exception        bool
	mutated          bool
	redirected       bool
	finalURL         string
	redirectLocation string
	responseChanged  bool
	removedHTMLAd    bool
	injectedStyle    bool
}

func TestReplayBrowsingFilteringQuality(t *testing.T) {
	results := runReplayBrowsing(t, true)
	got := formatReplayBrowsingResults(results)
	const want = `document requestType=document blocked=false exception=false mutated=false redirected=false final=https://news.example/article responseChanged=true removedHTMLAd=true injectedStyle=true
ad-script requestType=script blocked=true exception=false mutated=false redirected=false final=https://ads.example/banner.js responseChanged=false removedHTMLAd=false injectedStyle=false
allowed-script requestType=script blocked=false exception=true mutated=false redirected=false final=https://ads.example/allowed.js responseChanged=false removedHTMLAd=false injectedStyle=false
banner-image requestType=image blocked=true exception=false mutated=false redirected=false final=https://images.example/banner-top.jpg responseChanged=false removedHTMLAd=false injectedStyle=false
metrics-xhr requestType=xmlhttprequest blocked=true exception=false mutated=false redirected=false final=https://metrics.example/collect responseChanged=false removedHTMLAd=false injectedStyle=false
removeparam-xhr requestType=xmlhttprequest blocked=false exception=false mutated=true redirected=false final=https://tracker.example/path?keep=1 responseChanged=false removedHTMLAd=false injectedStyle=false
urlskip-document requestType=document blocked=false exception=false mutated=false redirected=true final=https://short.example/out?target=destination.example%2Flanding redirect=https://destination.example/landing responseChanged=false removedHTMLAd=false injectedStyle=false
rewrite-xhr requestType=xmlhttprequest blocked=true exception=false mutated=true redirected=false final=https://rewrite.example/final responseChanged=false removedHTMLAd=false injectedStyle=false
case-sensitive-hit requestType=script blocked=true exception=false mutated=false redirected=false final=https://case.example/CaseAd.js responseChanged=false removedHTMLAd=false injectedStyle=false
case-sensitive-miss requestType=script blocked=false exception=false mutated=false redirected=false final=https://case.example/casead.js responseChanged=false removedHTMLAd=false injectedStyle=false
stylesheet requestType=stylesheet blocked=false exception=false mutated=false redirected=false final=https://cdn.example/styles/site.css responseChanged=false removedHTMLAd=false injectedStyle=false`
	if got != want {
		t.Fatalf("unexpected browsing replay filtering decisions:\nwant:\n%s\n\ngot:\n%s", want, got)
	}
}

func BenchmarkReplayBrowsing(b *testing.B) {
	replay := newReplayBrowsingFixture(b)
	b.ReportAllocs()
	b.SetBytes(int64(len(replay.requests)))
	for b.Loop() {
		_ = replay.run(false)
	}
}

func runReplayBrowsing(tb testing.TB, keepResults bool) []replayBrowsingResult {
	tb.Helper()
	replay := newReplayBrowsingFixture(tb)
	return replay.run(keepResults)
}

type replayBrowsingFixture struct {
	tb       testing.TB
	service  *Service
	engine   adblockrust.Engine
	requests []replayBrowsingRequest
}

func newReplayBrowsingFixture(tb testing.TB) *replayBrowsingFixture {
	tb.Helper()
	parsed := parseFilterLines([]byte(strings.Join(replayBrowsingRules, "\n")))
	engine, err := adblockrust.NewEngine(parsed.Rules, "")
	if err != nil {
		tb.Fatal(err)
	}
	service := newTestService(context.Background(), option.AdblockOptions{}, engine)
	service.advanced = parsed.Advanced
	service.companion = parsed.Companion
	service.htmlFilters = parsed.HTML
	tb.Cleanup(func() {
		_ = engine.Close()
	})
	return &replayBrowsingFixture{
		tb:      tb,
		service: service,
		engine:  engine,
		requests: []replayBrowsingRequest{
			{
				name:     "document",
				rawURL:   "https://news.example/article",
				accept:   "text/html,application/xhtml+xml",
				dest:     "document",
				response: replayBrowsingHTML,
			},
			{name: "ad-script", rawURL: "https://ads.example/banner.js", referer: "https://news.example/article", dest: "script"},
			{name: "allowed-script", rawURL: "https://ads.example/allowed.js", referer: "https://news.example/article", dest: "script"},
			{name: "banner-image", rawURL: "https://images.example/banner-top.jpg", referer: "https://news.example/article", dest: "image"},
			{name: "metrics-xhr", method: http.MethodPost, rawURL: "https://metrics.example/collect", referer: "https://news.example/article", accept: "application/json"},
			{name: "removeparam-xhr", method: http.MethodPost, rawURL: "https://tracker.example/path?utm_source=feed&utm_medium=social&keep=1", referer: "https://news.example/article", accept: "application/json"},
			{name: "urlskip-document", rawURL: "https://short.example/out?target=destination.example%2Flanding", accept: "text/html", dest: "document"},
			{name: "rewrite-xhr", method: http.MethodPost, rawURL: "https://rewrite.example/start", referer: "https://news.example/article", accept: "application/json"},
			{name: "case-sensitive-hit", rawURL: "https://case.example/CaseAd.js", referer: "https://news.example/article", dest: "script"},
			{name: "case-sensitive-miss", rawURL: "https://case.example/casead.js", referer: "https://news.example/article", dest: "script"},
			{name: "stylesheet", rawURL: "https://cdn.example/styles/site.css", referer: "https://news.example/article", dest: "style"},
		},
	}
}

const replayBrowsingHTML = `<!doctype html><html><head>` +
	`<meta http-equiv="Content-Security-Policy" content="default-src 'self'">` +
	`<script>window.adPlacements = ["banner"];</script>` +
	`</head><body><main class="content">article</main>` +
	`<aside class="sponsor">sponsor</aside><div class="ad-slot">ad</div></body></html>`

func (r *replayBrowsingFixture) run(keepResults bool) []replayBrowsingResult {
	var results []replayBrowsingResult
	if keepResults {
		results = make([]replayBrowsingResult, 0, len(r.requests))
	}
	for _, replayRequest := range r.requests {
		result := r.runRequest(replayRequest)
		if keepResults {
			results = append(results, result)
		}
	}
	return results
}

func (r *replayBrowsingFixture) runRequest(replayRequest replayBrowsingRequest) replayBrowsingResult {
	method := replayRequest.method
	if method == "" {
		method = http.MethodGet
	}
	request := httptest.NewRequest(method, replayRequest.rawURL, nil)
	if replayRequest.referer != "" {
		request.Header.Set("Referer", replayRequest.referer)
	}
	if replayRequest.accept != "" {
		request.Header.Set("Accept", replayRequest.accept)
	}
	if replayRequest.dest != "" {
		request.Header.Set("Sec-Fetch-Dest", replayRequest.dest)
	}
	requestURL := mustParseReplayBrowsingURL(r.tb, replayRequest.rawURL)
	requestType := adblockRequestType(request)
	writer := httptest.NewRecorder()
	requestContext := &adblockRequestContext{
		service:     r.service,
		ctx:         request.Context(),
		engine:      r.engine,
		writer:      writer,
		request:     request,
		requestURL:  requestURL,
		requestType: requestType,
	}
	requestContext.check()
	mutated, redirected := r.service.readyAdvancedRules().mutateRequest(requestContext, checkResultBlocked(requestContext.checkResult))
	if mutated {
		requestContext.check()
	}
	result := replayBrowsingResult{
		name:             replayRequest.name,
		requestType:      requestType,
		blocked:          checkResultBlocked(requestContext.checkResult),
		exception:        checkResultException(requestContext.checkResult),
		mutated:          mutated,
		redirected:       redirected,
		finalURL:         requestContext.requestURL.String(),
		redirectLocation: writer.Header().Get("Location"),
	}
	if replayRequest.response != "" && !result.blocked && !result.redirected {
		body := r.filterReplayBrowsingResponse(requestContext, replayRequest.response)
		result.responseChanged = body != replayRequest.response
		result.removedHTMLAd = !strings.Contains(body, "adPlacements") &&
			!strings.Contains(body, `class="sponsor"`) &&
			!strings.Contains(body, `class="ad-slot"`) &&
			strings.Contains(body, `<main class="content">article</main>`)
		result.injectedStyle = strings.Contains(body, `<style`) && strings.Contains(body, "display:none!important")
	}
	return result
}

func mustParseReplayBrowsingURL(tb testing.TB, rawURL string) *url.URL {
	tb.Helper()
	parsedURL, err := url.Parse(rawURL)
	if err != nil {
		tb.Fatal(err)
	}
	return parsedURL
}

func (r *replayBrowsingFixture) filterReplayBrowsingResponse(requestContext *adblockRequestContext, content string) string {
	response := &http.Response{
		StatusCode:    http.StatusOK,
		Header:        http.Header{"Content-Type": {"text/html; charset=utf-8"}},
		Body:          io.NopCloser(strings.NewReader(content)),
		ContentLength: int64(len(content)),
	}
	if err := r.service.filterForwardedHTTPResponse(requestContext, response); err != nil {
		r.tb.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	if err != nil {
		r.tb.Fatal(err)
	}
	return string(body)
}

func formatReplayBrowsingResults(results []replayBrowsingResult) string {
	var builder strings.Builder
	for index, result := range results {
		if index > 0 {
			builder.WriteByte('\n')
		}
		builder.WriteString(fmt.Sprintf("%s requestType=%s blocked=%v exception=%v mutated=%v redirected=%v final=%s",
			result.name,
			result.requestType,
			result.blocked,
			result.exception,
			result.mutated,
			result.redirected,
			result.finalURL,
		))
		if result.redirectLocation != "" {
			builder.WriteString(" redirect=")
			builder.WriteString(result.redirectLocation)
		}
		builder.WriteString(fmt.Sprintf(" responseChanged=%v removedHTMLAd=%v injectedStyle=%v",
			result.responseChanged,
			result.removedHTMLAd,
			result.injectedStyle,
		))
	}
	return builder.String()
}
