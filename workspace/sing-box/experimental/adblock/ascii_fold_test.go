//go:build with_adblock

package adblock

import "testing"

func TestIndexASCIIFold(t *testing.T) {
	tests := []struct {
		value   string
		pattern string
		want    int
	}{
		{"", "", 0},
		{"abc", "", 0},
		{"ABCdef", "abc", 0},
		{"prefix-ABC-suffix", "abc", 7},
		{"prefix-abc-suffix", "abc", 7},
		{"prefix", "longer-pattern", -1},
		{"abcdef", "xyz", -1},
	}
	for _, test := range tests {
		if got := indexASCIIStringFold(test.value, test.pattern); got != test.want {
			t.Fatalf("indexASCIIStringFold(%q, %q) = %d, want %d", test.value, test.pattern, got, test.want)
		}
	}
}

func BenchmarkIndexASCIIFold(b *testing.B) {
	value := "https://www.reddit.com/svc/shreddit/graphql?operation=CreatePost&token=MixedCaseToken"
	b.ReportAllocs()
	for b.Loop() {
		if indexASCIIStringFold(value, "mixedcasetoken") < 0 {
			b.Fatal("pattern did not match")
		}
	}
}

func BenchmarkABPFastPatternMixedCase(b *testing.B) {
	pattern := compileABPPattern("*mixedcasetoken", false)
	value := "https://www.reddit.com/svc/shreddit/graphql?operation=CreatePost&token=MixedCaseToken"
	b.ReportAllocs()
	for b.Loop() {
		if !pattern.MatchString(value) {
			b.Fatal("pattern did not match")
		}
	}
}
