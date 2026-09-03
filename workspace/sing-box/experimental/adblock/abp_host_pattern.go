//go:build with_adblock

package adblock

import "strings"

// abpHostPattern handles the common ||example.com^ form without crossing the
// WASM-backed regular expression boundary for every rule and request.
type abpHostPattern struct {
	valid     bool
	matchCase bool
	host      string
}

func newABPHostPattern(pattern string, matchCase bool) (abpHostPattern, bool) {
	host, ok := strings.CutPrefix(pattern, "||")
	if !ok {
		return abpHostPattern{}, false
	}
	host, ok = strings.CutSuffix(host, "^")
	if !ok || host == "" || strings.ContainsAny(host, "*/?#[]:@^") {
		return abpHostPattern{}, false
	}
	if !matchCase {
		host = strings.ToLower(host)
	}
	return abpHostPattern{valid: true, matchCase: matchCase, host: host}, true
}

func (p abpHostPattern) match(rawURL string) bool {
	schemeEnd := strings.Index(rawURL, "://")
	if schemeEnd <= 0 || !validABPScheme(rawURL[:schemeEnd]) {
		return false
	}
	authority := rawURL[schemeEnd+3:]
	if end := strings.IndexAny(authority, "/?#"); end >= 0 {
		authority = authority[:end]
	}
	if userEnd := strings.LastIndexByte(authority, '@'); userEnd >= 0 {
		authority = authority[userEnd+1:]
	}
	host := authority
	if colon := strings.LastIndexByte(host, ':'); colon >= 0 {
		host = host[:colon]
	}
	if host == "" {
		return false
	}
	if p.matchCase {
		return host == p.host || strings.HasSuffix(host, "."+p.host)
	}
	return strings.EqualFold(host, p.host) || len(host) > len(p.host) &&
		host[len(host)-len(p.host)-1] == '.' && strings.EqualFold(host[len(host)-len(p.host):], p.host)
}

func validABPScheme(scheme string) bool {
	for index, char := range []byte(scheme) {
		if char >= 'a' && char <= 'z' || index > 0 && (char >= '0' && char <= '9' || char == '+' || char == '.' || char == '-') {
			continue
		}
		return false
	}
	return true
}
