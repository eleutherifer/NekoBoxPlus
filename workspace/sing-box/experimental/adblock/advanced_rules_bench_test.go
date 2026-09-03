//go:build with_adblock

package adblock

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	"github.com/sagernet/sing-box/option"
)

func benchmarkAdvancedRulesCorpus() string {
	lines := make([]string, 0, 800)
	for index := range 200 {
		suffix := strconv.Itoa(index)
		lines = append(lines,
			"||ads"+suffix+`.example^$script,third-party,method=get`,
			"@@||ads"+suffix+`.example/allowed/$script,third-party,method=get`,
			"||tracker"+suffix+`.example^$removeparam=/^utm_(source|medium|campaign)=/`,
			"||redirect"+suffix+`.example^$uritransform=/campaign([0-9]+)/offer$1/`,
		)
	}
	return strings.Join(lines, "\n")
}

func BenchmarkAdvancedRulesCompile(b *testing.B) {
	corpus := []byte(benchmarkAdvancedRulesCorpus())
	b.ReportAllocs()
	b.SetBytes(int64(len(corpus)))
	for b.Loop() {
		parsed := parseFilterLines(corpus)
		if len(parsed.Advanced.rules) != 800 {
			b.Fatalf("compiled %d advanced rules, want 800", len(parsed.Advanced.rules))
		}
	}
}

func newBenchmarkAdvancedContext(b *testing.B, rawRules string, rawURL string) (*Service, *adblockRequestContext) {
	b.Helper()
	parsed := parseFilterLines([]byte(rawRules))
	service := newTestService(b.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.advanced = parsed.Advanced
	request := httptest.NewRequest(http.MethodGet, rawURL, nil)
	request.Header.Set("Referer", "https://source.example/")
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         b.Context(),
		engine:      &fakeAdblockEngine{},
		request:     request,
		requestURL:  mustParseBenchmarkURL(b, rawURL),
		requestType: "script",
	}
	return service, requestContext
}

func mustParseBenchmarkURL(b *testing.B, rawURL string) *url.URL {
	b.Helper()
	parsed, err := url.Parse(rawURL)
	if err != nil {
		b.Fatal(err)
	}
	return parsed
}

func BenchmarkAdvancedRulesApplyCheck(b *testing.B) {
	var lines []string
	for index := range 400 {
		lines = append(lines, "||ads"+string(rune('a'+index%26))+".example^$script,third-party,method=get")
		lines = append(lines, "@@||allow"+string(rune('a'+index%26))+".example^$script,third-party,method=get")
	}
	service, requestContext := newBenchmarkAdvancedContext(b, strings.Join(lines, "\n"), "https://adsz.example/banner.js")
	b.ReportAllocs()
	for b.Loop() {
		_ = service.advanced.applyCheck(requestContext, adblockrust.CheckResult{})
	}
}

func BenchmarkAdvancedRulesMutateRequest(b *testing.B) {
	var lines []string
	for index := range 400 {
		lines = append(lines, "||tracker"+string(rune('a'+index%26))+".example^$removeparam=utm_source")
		lines = append(lines, "@@||safe"+string(rune('a'+index%26))+".example^$removeparam=utm_source")
	}
	service, requestContext := newBenchmarkAdvancedContext(b, strings.Join(lines, "\n"), "https://trackerz.example/path?utm_source=x&keep=1")
	b.ReportAllocs()
	for b.Loop() {
		requestContext.requestURL = mustParseBenchmarkURL(b, "https://trackerz.example/path?utm_source=x&keep=1")
		_, _ = service.advanced.mutateRequest(requestContext, false)
	}
}
