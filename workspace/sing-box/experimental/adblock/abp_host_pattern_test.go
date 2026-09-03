//go:build with_adblock

package adblock

import "testing"

func TestABPHostPattern(t *testing.T) {
	tests := []struct {
		name      string
		pattern   string
		matchCase bool
		value     string
		want      bool
	}{
		{"host", "||example.com^", false, "https://example.com/banner.js", true},
		{"subdomain", "||example.com^", false, "https://cdn.example.com/banner.js", true},
		{"case insensitive", "||example.com^", false, "https://CDN.Example.COM/banner.js", true},
		{"port", "||example.com^", false, "https://example.com:8443/banner.js", true},
		{"different host", "||example.com^", false, "https://notexample.com/banner.js", false},
		{"host in path", "||example.com^", false, "https://invalid.test/example.com/banner.js", false},
		{"invalid scheme", "||example.com^", false, "1https://example.com/banner.js", false},
		{"case sensitive mismatch", "||Example.com^", true, "https://example.com/banner.js", false},
		{"path is not specialized", "||example.com/path^", false, "https://example.com/path/file", false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			pattern, ok := newABPHostPattern(test.pattern, test.matchCase)
			if !ok {
				if test.name == "path is not specialized" {
					return
				}
				t.Fatal("pattern was not accepted")
			}
			if got := pattern.match(test.value); got != test.want {
				t.Fatalf("match = %v, want %v", got, test.want)
			}
		})
	}
}
