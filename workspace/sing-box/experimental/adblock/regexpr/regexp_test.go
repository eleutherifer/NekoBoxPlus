package regexpr

import (
	"slices"
	"testing"
)

func TestRegexpContract(t *testing.T) {
	if _, err := Compile("("); err == nil {
		t.Fatal("expected invalid regexp error")
	}
	if got := QuoteMeta(`a.b[c]`); got != `a\.b\[c\]` {
		t.Fatalf("QuoteMeta() = %q", got)
	}
	matchCases := []struct {
		name    string
		pattern string
		input   string
	}{
		{"literal", `example`, "example"},
		{"case insensitive", `(?i)example`, "EXAMPLE"},
		{"anchored", `^https://`, "https://example.com"},
		{"anchored case insensitive", `(?i)^https://`, "HTTPS://example.com"},
		{"scheme", `(?i)^[a-z][a-z0-9+.-]*://`, "https://example.com"},
		{"optional host", `(?i)^https://([^/?#]*\.)?example`, "https://example.com"},
		{"separator", `(?i)^https://example\.com([^\w\d_.%-]|$)`, "https://example.com/banner.js"},
		{"host", `(?i)^[a-z][a-z0-9+.-]*://([^/?#]*\.)*Example\.COM([^\w\d_.%-]|$)`, "https://example.com/banner.js"},
	}
	for _, testCase := range matchCases {
		t.Run(testCase.name, func(t *testing.T) {
			if !MustCompile(testCase.pattern).MatchString(testCase.input) {
				t.Fatalf("%q did not match %q", testCase.pattern, testCase.input)
			}
		})
	}

	matcher := MustCompile(`(?i:^https://([^/]+)/(ads|track)/(.+)$)`)
	input := "https://EXAMPLE.com/ads/banner.js"
	if !matcher.MatchString(input) {
		t.Fatal("expected regexp to match")
	}
	wantSubmatches := []string{input, "EXAMPLE.com", "ads", "banner.js"}
	if got := matcher.FindStringSubmatch(input); !slices.Equal(got, wantSubmatches) {
		t.Fatalf("FindStringSubmatch() = %#v, want %#v", got, wantSubmatches)
	}
	if got := matcher.ReplaceAllString(input, "https://$1/blocked/$3"); got != "https://EXAMPLE.com/blocked/banner.js" {
		t.Fatalf("ReplaceAllString() = %q", got)
	}
	if got := matcher.ReplaceAll([]byte(input), []byte(`$1/$3`)); string(got) != "EXAMPLE.com/banner.js" {
		t.Fatalf("ReplaceAll() = %q", got)
	}
}
