//go:build with_adblock

package adblock

import (
	"testing"

	"github.com/sagernet/sing-box/common/adblock/adblockrust"
)

func newBenchmarkRequestCheckService() (*Service, *fakeAdblockEngine) {
	engine := &fakeAdblockEngine{
		detailedResult:  adblockrust.CheckResult{Matched: true, Redirect: "data:text/plain;base64,eA"},
		exceptionResult: true,
	}
	service := &Service{
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(nil),
	}
	return service, engine
}

func BenchmarkRequestCheckMiss(b *testing.B) {
	service, engine := newBenchmarkRequestCheckService()
	b.ReportAllocs()
	for b.Loop() {
		service.clearCheckCache()
		_, _ = service.requestCheck(engine, "https://ads.example/banner.js", "https://example.com/", "script", adblockrust.RequestMethodGet)
	}
}

func BenchmarkRequestCheckHit(b *testing.B) {
	service, engine := newBenchmarkRequestCheckService()
	_, _ = service.requestCheck(engine, "https://ads.example/banner.js", "https://example.com/", "script", adblockrust.RequestMethodGet)
	b.ReportAllocs()
	for b.Loop() {
		_, _ = service.requestCheck(engine, "https://ads.example/banner.js", "https://example.com/", "script", adblockrust.RequestMethodGet)
	}
}

func BenchmarkRequestExceptionHit(b *testing.B) {
	service, engine := newBenchmarkRequestCheckService()
	_ = service.requestException(engine, "https://example.com/", "https://example.com/", "document", adblockrust.RequestMethodGet)
	b.ReportAllocs()
	for b.Loop() {
		_ = service.requestException(engine, "https://example.com/", "https://example.com/", "document", adblockrust.RequestMethodGet)
	}
}
