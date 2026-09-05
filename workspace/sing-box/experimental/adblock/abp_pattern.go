//go:build with_adblock

package adblock

import (
	"strings"
	"unicode"
)

type abpPattern struct {
	regex *adblockRegexp
	fast  abpFastPattern
	host  abpHostPattern
}

func (p *abpPattern) MatchString(value string) bool {
	if p == nil {
		return false
	}
	if p.fast.valid {
		return p.fast.match(value)
	}
	if p.host.valid {
		return p.host.match(value)
	}
	return p.regex != nil && p.regex.MatchString(value)
}

type abpFastPattern struct {
	valid       bool
	matchCase   bool
	anchorFirst bool
	anchorEnd   bool
	parts       []string
}

func newABPFastPattern(pattern string, matchCase bool) (abpFastPattern, bool) {
	if strings.HasPrefix(pattern, "/") && strings.HasSuffix(pattern, "/") && len(pattern) > 1 {
		return abpFastPattern{}, false
	}
	if strings.HasPrefix(pattern, "||") || strings.ContainsRune(pattern, '^') {
		return abpFastPattern{}, false
	}
	if pattern == "" || pattern == "*" {
		return abpFastPattern{valid: true, matchCase: matchCase, parts: nil}, true
	}
	anchorStart := false
	if strings.HasPrefix(pattern, "|") {
		anchorStart = true
		pattern = pattern[1:]
	}
	anchorEnd := false
	if strings.HasSuffix(pattern, "|") {
		anchorEnd = true
		pattern = strings.TrimSuffix(pattern, "|")
	}
	parts := strings.Split(pattern, "*")
	for _, part := range parts {
		if part == "" {
			continue
		}
		for _, ch := range part {
			if unicode.IsControl(ch) {
				return abpFastPattern{}, false
			}
		}
	}
	if !matchCase {
		for index, part := range parts {
			parts[index] = strings.ToLower(part)
		}
	}
	anchorFirst := anchorStart && len(parts) > 0 && parts[0] != ""
	return abpFastPattern{valid: true, matchCase: matchCase, anchorFirst: anchorFirst, anchorEnd: anchorEnd, parts: parts}, true
}

func (p abpFastPattern) match(value string) bool {
	if len(p.parts) == 0 {
		return true
	}
	position := 0
	matchedPart := 0
	for index, part := range p.parts {
		if part == "" {
			if index == 0 {
				position = 0
			}
			continue
		}
		found := strings.Index(value[position:], part)
		if !p.matchCase {
			found = indexASCIIStringFold(value[position:], part)
		}
		if found < 0 {
			return false
		}
		if matchedPart == 0 && p.anchorFirst && found != 0 {
			return false
		}
		position += found + len(part)
		matchedPart++
	}
	return !p.anchorEnd || position == len(value)
}
