//go:build with_adblock

package adblock

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	"github.com/sagernet/sing-box/option"
)

func TestParseAdvancedRuleDropsUnsupportedOption(t *testing.T) {
	// "*$strict3p,ipaddress=0.0.0.0" is a real uBlock rule whose ONLY block
	// condition is the unsupported "ipaddress=" constraint. Ignoring it would
	// turn the rule into a blanket third-party block, so the rule must be
	// dropped entirely rather than kept as a broadened block.
	cases := []string{
		`*$strict3p,ipaddress=0.0.0.0,domain=~0.0.0.0|~127.0.0.1|~[::1]|~[::]|~local|~localhost`,
		`||ads.example^$strict3p,denyallow=partner.example`,
		`||ads.example^$strict3p,app=com.browser.app`,
		`*$strict3p,redirect=nodejs`,
		`*$strict3p,badfilter`,
	}
	for _, raw := range cases {
		if rule, ok := parseAdvancedRule(raw); ok {
			t.Errorf("rule with unsupported option must be dropped, but parsed: %q -> %#v", raw, rule)
		}
	}
}

func TestABPFastPatternMatchesCommonPatterns(t *testing.T) {
	cases := []struct {
		pattern   string
		matchCase bool
		value     string
		want      bool
	}{
		{"*", false, "https://example.com/script.js", true},
		{"ads", false, "https://example.com/ADS/banner.js", true},
		{"ads", true, "https://example.com/ADS/banner.js", false},
		{"|https://example.com", false, "https://example.com/path", true},
		{"|https://example.com", false, "http://example.com/path", false},
		{"*ads", false, "https://cdn.example/path/ads", true},
		{"ads*banner", false, "https://cdn.example/ads/foo/banner.js", true},
		{"ads*banner.js|", false, "https://cdn.example/ads/foo/banner.js", true},
		{"ads*banner.js|", false, "https://cdn.example/ads/foo/banner.js?x=1", false},
	}
	for _, c := range cases {
		pattern := compileABPPattern(c.pattern, c.matchCase)
		if pattern == nil {
			t.Fatalf("pattern did not compile: %q", c.pattern)
		}
		if got := pattern.MatchString(c.value); got != c.want {
			t.Errorf("pattern %q matchCase=%v value=%q: got %v, want %v", c.pattern, c.matchCase, c.value, got, c.want)
		}
	}
}

func TestAdvancedRuleHostAnchorPrefilterPreservesMatches(t *testing.T) {
	rule, ok := parseAdvancedRule(`||Example.COM^$script,method=get`)
	if !ok {
		t.Fatal("expected advanced rule to parse")
	}
	cases := []struct {
		name       string
		requestURL string
		host       string
		want       bool
	}{
		{"exact host", "https://example.com/banner.js", "example.com", true},
		{"subdomain", "https://Sub.Example.Com/banner.js", "sub.example.com", true},
		{"sibling suffix", "https://badexample.com/banner.js", "badexample.com", false},
		{"other host", "https://other.example/banner.js", "other.example", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := rule.matches(advancedRequest{
				requestURL:  tc.requestURL,
				requestType: "script",
				method:      strings.ToLower(http.MethodGet),
				requestHost: tc.host,
			})
			if got != tc.want {
				t.Fatalf("matches() = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestAdvancedRuleHostAnchorPrefilterKeepsMutationException(t *testing.T) {
	parsed := parseFilterLines([]byte(strings.Join([]string{
		`||tracker.example^$removeparam=utm_source`,
		`@@||tracker.example^$removeparam=utm_source`,
	}, "\n")))
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.advanced = parsed.Advanced
	request := httptest.NewRequest(http.MethodGet, "https://tracker.example/path?utm_source=x&keep=1", nil)
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         t.Context(),
		engine:      &fakeAdblockEngine{},
		request:     request,
		requestURL:  mustParseURL(t, "https://tracker.example/path?utm_source=x&keep=1"),
		requestType: "script",
	}

	changed, redirected := service.advanced.mutateRequest(requestContext, false)
	if changed || redirected {
		t.Fatalf("exception should suppress mutation, changed=%v redirected=%v url=%s", changed, redirected, requestContext.requestURL)
	}
	if got := requestContext.requestURL.String(); got != "https://tracker.example/path?utm_source=x&keep=1" {
		t.Fatalf("unexpected URL after exception: %s", got)
	}
}

func TestParseAdvancedRuleKeepsStandardTypeAliases(t *testing.T) {
	cases := []struct {
		raw       string
		canonical string
	}{
		{`||foo.example^$doc,strict3p`, "document"},
		{`||foo.example^$frame,strict3p`, "subdocument"},
		{`||foo.example^$css,strict3p`, "stylesheet"},
		{`||foo.example^$js,strict3p`, "script"},
		{`||foo.example^$img,strict3p`, "image"},
		{`||foo.example^$object,strict3p`, "object"},
	}
	for _, c := range cases {
		rule, ok := parseAdvancedRule(c.raw)
		if !ok {
			t.Fatalf("rule with recognized type alias must parse: %q", c.raw)
		}
		if !rule.types[c.canonical] {
			t.Errorf("alias not canonicalized for %q: types=%#v (want %q set)", c.raw, rule.types, c.canonical)
		}
		if rule.kind != advancedRuleBlock {
			t.Errorf("expected advancedRuleBlock for %q, got kind=%d", c.raw, rule.kind)
		}
	}
}

func TestParseAdvancedRuleAcceptsAllOption(t *testing.T) {
	// "$all" matches every content type and adds no constraint; it must be
	// recognized so the rule is not dropped.
	rule, ok := parseAdvancedRule(`*$all,strict3p,to=example.com`)
	if !ok {
		t.Fatal("$all must be recognized as a valid request-type option")
	}
	if len(rule.types) != 0 {
		t.Errorf("$all must not add a type constraint, got types=%#v", rule.types)
	}
	if rule.kind != advancedRuleBlock {
		t.Errorf("expected advancedRuleBlock, got kind=%d", rule.kind)
	}
}

func TestParseAdvancedRuleNegatedTypeAlias(t *testing.T) {
	rule, ok := parseAdvancedRule(`||foo.example^$~doc,strict3p`)
	if !ok {
		t.Fatal("negated type alias must parse")
	}
	if enabled, exists := rule.types["document"]; !exists || enabled {
		t.Errorf("expected ~doc to record document=false, got types=%#v", rule.types)
	}
}

func TestParseCompanionRuleDropsUnsupportedOption(t *testing.T) {
	// Companion rules require an action kind (replace/header/...), but a rule
	// carrying an unsupported applicability option must still be dropped rather
	// than kept with the constraint silently ignored.
	if _, ok := parseCompanionRule(`||ads.example^$permissions=collapse,ipaddress=0.0.0.0`); ok {
		t.Error("companion rule with unsupported option must be dropped")
	}
	// A clean permissions rule must still parse.
	if _, ok := parseCompanionRule(`||ads.example^$xhr,permissions=collapse`); !ok {
		t.Error("valid permissions rule must parse")
	}
}

// TestAdvancedRuleStrict3pIpAddressDoesNotBlockStaticAsset is the regression test
// for the reported bug: on a fresh engine build, static third-party assets
// (js/css) were blocked with HTTP 204 because "*$strict3p,ipaddress=0.0.0.0"
// degraded into a blanket strict-third-party block.
func TestAdvancedRuleStrict3pIpAddressDoesNotBlockStaticAsset(t *testing.T) {
	parsed := parseFilterLines([]byte(`*$strict3p,ipaddress=0.0.0.0,domain=~0.0.0.0|~127.0.0.1|~[::1]|~[::]|~local|~localhost`))
	if len(parsed.Advanced.rules) != 0 {
		t.Fatalf("catastrophic strict3p/ipaddress rule must be dropped, got: %#v", parsed.Advanced.rules)
	}
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.advanced = parsed.Advanced

	for _, raw := range []string{
		"https://www.redditstatic.com/shreddit/ru-RU/Dq2Tmcuo_h.js",
		"https://cdn.example.com/assets/style.css",
	} {
		request := httptest.NewRequest(http.MethodGet, raw, nil)
		request.Header.Set("Referer", "https://www.example.com/")
		requestContext := &adblockRequestContext{
			service:     service,
			ctx:         t.Context(),
			engine:      &fakeAdblockEngine{},
			request:     request,
			requestURL:  mustParseURL(t, raw),
			requestType: "script",
		}
		result := service.advanced.applyCheck(requestContext, adblockrust.CheckResult{})
		if checkResultBlocked(result) {
			t.Errorf("static asset must not be blocked by advanced rules: %s -> %#v", raw, result)
		}
	}
}

func TestAdvancedRuleExceptionRecomputedAfterMutation(t *testing.T) {
	parsed := parseFilterLines([]byte(strings.Join([]string{
		`||example.com/start$uritransform=/start/mid/`,
		`@@||example.com/mid$removeparam=keep`,
		`||example.com/mid$removeparam=keep`,
	}, "\n")))
	service := newTestService(t.Context(), option.AdblockOptions{}, &fakeAdblockEngine{})
	service.advanced = parsed.Advanced
	request := httptest.NewRequest(http.MethodGet, "https://example.com/start?keep=1", nil)
	requestContext := &adblockRequestContext{
		service:     service,
		ctx:         t.Context(),
		engine:      &fakeAdblockEngine{},
		request:     request,
		requestURL:  mustParseURL(t, "https://example.com/start?keep=1"),
		requestType: "xmlhttprequest",
	}

	changed, redirected := service.advanced.mutateRequest(requestContext, false)
	if redirected || !changed {
		t.Fatalf("expected URI mutation only, changed=%v redirected=%v", changed, redirected)
	}
	if got := requestContext.requestURL.String(); got != "https://example.com/mid?keep=1" {
		t.Fatalf("removeparam exception was not recomputed after mutation, got %s", got)
	}
}
