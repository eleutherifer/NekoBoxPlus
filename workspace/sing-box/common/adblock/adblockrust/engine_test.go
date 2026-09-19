//go:build with_adblock

package adblockrust

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestEngineCheck(t *testing.T) {
	engine, err := NewEngine([]string{"||ads.example.com^"}, "")
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	matched, err := engine.Check("https://ads.example.com/banner.js", "https://example.com/", "script", RequestMethodGet)
	if err != nil {
		t.Fatal(err)
	}
	if !matched {
		t.Fatal("expected request to match")
	}
	matched, err = engine.Check("https://static.example.com/app.js", "https://example.com/", "script", RequestMethodGet)
	if err != nil {
		t.Fatal(err)
	}
	if matched {
		t.Fatal("expected request not to match")
	}
}

func TestEngineCheckRequestMethod(t *testing.T) {
	engine, err := NewEngine([]string{"||api.example.com^$method=post"}, "")
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()

	matched, err := engine.Check("https://api.example.com/collect", "https://example.com/", "xmlhttprequest", RequestMethodPost)
	if err != nil {
		t.Fatal(err)
	}
	if !matched {
		t.Fatal("expected POST request to match method filter")
	}
	matched, err = engine.Check("https://api.example.com/collect", "https://example.com/", "xmlhttprequest", RequestMethodGet)
	if err != nil {
		t.Fatal(err)
	}
	if matched {
		t.Fatal("expected GET request not to match POST method filter")
	}
}

func TestEngineCheckDetailedRedirect(t *testing.T) {
	adblockResources := filepath.Clean("../../../../adblock-resources")
	if _, err := os.Stat(filepath.Join(adblockResources, "dist", "resources.json")); err != nil {
		t.Skip("adblock resources checkout not available")
	}
	engine, err := NewEngine([]string{"||ads.example.com/banner.js$script,redirect=brave-fix.js"}, adblockResources)
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	result, err := engine.CheckDetailed("https://ads.example.com/banner.js", "https://example.com/", "script", RequestMethodGet)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Matched {
		t.Fatal("expected request to match")
	}
	if result.Redirect == "" {
		t.Fatal("expected redirect resource")
	}
}

func TestEngineCheckDetailedNoFilterOmitsFilter(t *testing.T) {
	engine, err := NewEngine([]string{"||ads.example.com^"}, "")
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()

	detailed, err := engine.CheckDetailed("https://ads.example.com/banner.js", "https://example.com/", "script", RequestMethodGet)
	if err != nil {
		t.Fatal(err)
	}
	if detailed.Filter == "" {
		t.Fatal("CheckDetailed must keep matched filter text")
	}

	noFilter, err := engine.CheckDetailedNoFilter("https://ads.example.com/banner.js", "https://example.com/", "script", RequestMethodGet)
	if err != nil {
		t.Fatal(err)
	}
	if !noFilter.Matched {
		t.Fatal("CheckDetailedNoFilter lost match result")
	}
	if noFilter.Filter != "" {
		t.Fatalf("CheckDetailedNoFilter returned filter text: %q", noFilter.Filter)
	}
}

func TestEngineCheckExceptionFindsPureDocumentException(t *testing.T) {
	engine, err := NewEngine([]string{"@@||adblock-tester.com^$document"}, "")
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	matched, err := engine.CheckException("https://adblock-tester.com/", "https://adblock-tester.com/", "document", RequestMethodGet)
	if err != nil {
		t.Fatal(err)
	}
	if !matched {
		t.Fatal("expected pure document exception to match")
	}
}

func TestEngineCSPDirectives(t *testing.T) {
	engine, err := NewEngine([]string{"||example.com^$csp=script-src 'none'"}, "")
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	directives, err := engine.CSPDirectives("https://example.com/", "https://example.com/", "document", RequestMethodGet)
	if err != nil {
		t.Fatal(err)
	}
	if directives != "script-src 'none'" {
		t.Fatalf("unexpected CSP directives: %q", directives)
	}
}

func TestEngineCheckWithEmptySourceURL(t *testing.T) {
	// The DNS check path passes an empty sourceURL through cgo. Ensure the
	// bridge receives a valid empty C string (not NULL) and still resolves
	// the request hostname correctly.
	engine, err := NewEngine([]string{"||ads.example.com^"}, "")
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	matched, err := engine.Check("https://ads.example.com/banner.js", "", "other", RequestMethodNone)
	if err != nil {
		t.Fatal(err)
	}
	if !matched {
		t.Fatal("expected request to match with empty source URL")
	}
	result, err := engine.CheckDetailed("https://ads.example.com/banner.js", "", "other", RequestMethodNone)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Matched {
		t.Fatal("expected detailed match with empty source URL")
	}
}

func TestEngineHostsRuleSet(t *testing.T) {
	engine, err := NewEngineWithRuleSets([]RuleSet{{
		Rules: []string{
			"0.0.0.0 analytics.s3.amazonaws.com",
			"ads-api.twitter.com",
		},
		Format: RuleFormatHosts,
	}}, "")
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()

	for _, requestURL := range []string{
		"https://analytics.s3.amazonaws.com/metrics",
		"https://ads-api.twitter.com/1/ads",
	} {
		matched, err := engine.Check(requestURL, "https://example.com/", "xmlhttprequest", RequestMethodGet)
		if err != nil {
			t.Fatal(err)
		}
		if !matched {
			t.Fatalf("expected request to match hosts rule: %s", requestURL)
		}
	}
}

func TestEngineTags(t *testing.T) {
	engine, err := NewEngine([]string{"||tagged.example^$tag=mobile"}, "")
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()

	result, err := engine.CheckDetailed("https://tagged.example/ad.js", "https://example.com/", "script", RequestMethodGet)
	if err != nil {
		t.Fatal(err)
	}
	if result.Matched {
		t.Fatal("expected tagged rule not to match before tag is enabled")
	}
	if err = engine.UseTags([]string{"mobile"}); err != nil {
		t.Fatal(err)
	}
	exists, err := engine.TagExists("mobile")
	if err != nil {
		t.Fatal(err)
	}
	if !exists {
		t.Fatal("expected enabled tag to exist")
	}
	result, err = engine.CheckDetailed("https://tagged.example/ad.js", "https://example.com/", "script", RequestMethodGet)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Matched {
		t.Fatal("expected tagged rule to match after tag is enabled")
	}
	if err = engine.DisableTags([]string{"mobile"}); err != nil {
		t.Fatal(err)
	}
	result, err = engine.CheckDetailed("https://tagged.example/ad.js", "https://example.com/", "script", RequestMethodGet)
	if err != nil {
		t.Fatal(err)
	}
	if result.Matched {
		t.Fatal("expected tagged rule not to match after tag is disabled")
	}
}

func TestEngineCosmeticResources(t *testing.T) {
	engine, err := NewEngine([]string{
		"example.com##.ad-banner",
		"##.generic-ad",
		"example.com#@#.generic-ad",
		"example.com##.sponsored:has-text(Ad)",
		"example.com##body [data-ad] ~ script ~ [id][class]:style(background-image: none !important)",
	}, "")
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()

	resources, err := engine.URLCosmeticResources("https://example.com/page")
	if err != nil {
		t.Fatal(err)
	}
	if !contains(resources.HideSelectors, ".ad-banner") {
		t.Fatalf("expected hostname hide selector in %v", resources.HideSelectors)
	}
	if !contains(resources.Exceptions, ".generic-ad") {
		t.Fatalf("expected generic exception in %v", resources.Exceptions)
	}
	if len(resources.ProceduralActions) == 0 {
		t.Fatal("expected procedural action")
	}
	if !containsSubstring(resources.ProceduralActions, "background-image: none !important") {
		t.Fatalf("expected style action in %v", resources.ProceduralActions)
	}

	selectors, err := engine.HiddenClassIDSelectors([]string{"generic-ad"}, nil, resources.Exceptions)
	if err != nil {
		t.Fatal(err)
	}
	if contains(selectors, ".generic-ad") {
		t.Fatalf("expected exception to suppress generic selector: %v", selectors)
	}
}

func containsSubstring(values []string, expected string) bool {
	for _, value := range values {
		if strings.Contains(value, expected) {
			return true
		}
	}
	return false
}

func contains(values []string, expected string) bool {
	for _, value := range values {
		if value == expected {
			return true
		}
	}
	return false
}

func TestEngineLoadsCurrentAdblockResourcesResourcesWhenAvailable(t *testing.T) {
	adblockResources := filepath.Clean("../../../../adblock-resources")
	if _, err := os.Stat(filepath.Join(adblockResources, "dist", "resources.json")); err != nil {
		t.Skip("adblock resources checkout not available")
	}
	engine, err := NewEngine([]string{
		"example.com##+js(brave-fix)",
	}, adblockResources)
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()

	resources, err := engine.URLCosmeticResources("https://example.com/page")
	if err != nil {
		t.Fatal(err)
	}
	if resources.InjectedScript == "" {
		t.Fatal("expected current adblock-resources scriptlet resources to produce injected script")
	}
	if !strings.Contains(resources.InjectedScript, "delete Navigator.prototype.brave") {
		t.Fatalf("expected brave-fix.js in injected script: %s", resources.InjectedScript)
	}
}

func TestEngineLoadsBundledBraveAndUBlockResources(t *testing.T) {
	engine, err := NewEngine([]string{
		"example.com##+js(brave-fix)",
		"example.com##+js(no-xhr-if, /ads/)",
		"example.com##+js(nano-setTimeout-booster, timer)",
	}, "")
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()

	resources, err := engine.URLCosmeticResources("https://example.com/page")
	if err != nil {
		t.Fatal(err)
	}
	if resources.InjectedScript == "" {
		t.Fatal("expected bundled Brave and uBlock resources to produce injected script")
	}
	for _, expected := range []string{"delete Navigator.prototype.brave", "XMLHttpRequest", "setTimeout", "timer"} {
		if !strings.Contains(resources.InjectedScript, expected) {
			t.Fatalf("bundled resource script is missing %q: %s", expected, resources.InjectedScript)
		}
	}
}

func TestEngineTrustControlsPrivilegedResourceScriptlets(t *testing.T) {
	resourcesDir := t.TempDir()
	if err := os.Mkdir(filepath.Join(resourcesDir, "dist"), 0o755); err != nil {
		t.Fatal(err)
	}
	resourcesJSON := `[
  {
    "name": "permissioned.js",
    "aliases": [],
    "kind": { "mime": "application/javascript" },
    "content": "ZnVuY3Rpb24gcGVybWlzc2lvbmVkKCl7d2luZG93LmFkYmxvY2tUcnVzdGVkVmFsdWU9dHJ1ZX0=",
    "dependencies": [],
    "permission": 255
  }
]`
	if err := os.WriteFile(filepath.Join(resourcesDir, "dist", "resources.json"), []byte(resourcesJSON), 0o644); err != nil {
		t.Fatal(err)
	}

	untrustedEngine, err := NewEngine([]string{
		"example.com##+js(permissioned)",
	}, resourcesDir)
	if err != nil {
		t.Fatal(err)
	}
	defer untrustedEngine.Close()

	untrustedResources, err := untrustedEngine.URLCosmeticResources("https://example.com/page")
	if err != nil {
		t.Fatal(err)
	}
	if untrustedResources.InjectedScript != "" {
		t.Fatalf("expected untrusted rule set not to inject privileged scriptlet: %s", untrustedResources.InjectedScript)
	}

	trustedEngine, err := NewEngineWithRuleSets([]RuleSet{{
		Rules:       []string{"example.com##+js(permissioned)"},
		Permissions: 0xff,
	}}, resourcesDir)
	if err != nil {
		t.Fatal(err)
	}
	defer trustedEngine.Close()

	trustedResources, err := trustedEngine.URLCosmeticResources("https://example.com/page")
	if err != nil {
		t.Fatal(err)
	}
	if trustedResources.InjectedScript == "" {
		t.Fatal("expected trusted rule set to inject privileged scriptlet")
	}
	if !strings.Contains(trustedResources.InjectedScript, "adblockTrustedValue") {
		t.Fatalf("expected trusted scriptlet arguments in injected script: %s", trustedResources.InjectedScript)
	}
}

func BenchmarkEngineCheckDetailed(b *testing.B) {
	engine, err := NewEngine([]string{"||ads.example.com^"}, "")
	if err != nil {
		b.Fatal(err)
	}
	defer engine.Close()
	b.ReportAllocs()
	b.ResetTimer()
	for b.Loop() {
		_, _ = engine.CheckDetailed("https://ads.example.com/banner.js", "https://example.com/", "script", RequestMethodGet)
	}
}

func BenchmarkEngineCheckDetailedEmptySource(b *testing.B) {
	engine, err := NewEngine([]string{"||ads.example.com^"}, "")
	if err != nil {
		b.Fatal(err)
	}
	defer engine.Close()
	b.ReportAllocs()
	b.ResetTimer()
	for b.Loop() {
		_, _ = engine.CheckDetailed("https://ads.example.com/banner.js", "", "other", RequestMethodNone)
	}
}

func BenchmarkEngineCheckDetailedNoFilter(b *testing.B) {
	engine, err := NewEngine([]string{"||ads.example.com^"}, "")
	if err != nil {
		b.Fatal(err)
	}
	defer engine.Close()
	b.ReportAllocs()
	b.ResetTimer()
	for b.Loop() {
		_, _ = engine.CheckDetailedNoFilter("https://ads.example.com/banner.js", "https://example.com/", "script", RequestMethodGet)
	}
}
