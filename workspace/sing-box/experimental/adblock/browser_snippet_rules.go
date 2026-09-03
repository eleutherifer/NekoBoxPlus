//go:build with_adblock

package adblock

import (
	"net/url"
	"slices"
	"strings"

	"github.com/goccy/go-json"
)

type browserSnippetRule struct {
	exception  bool
	domains    []string
	notDomains []string
	xpaths     []string
}

func parseBrowserSnippetRule(raw string) (browserSnippetRule, bool) {
	marker := "#$#"
	exception := false
	index := strings.Index(raw, marker)
	if index < 0 {
		marker = "#@$#"
		index = strings.Index(raw, marker)
		exception = true
	}
	if index < 0 {
		return browserSnippetRule{}, false
	}
	rule := browserSnippetRule{exception: exception}
	for domain := range strings.SplitSeq(raw[:index], ",") {
		domain = strings.TrimSpace(domain)
		if domain == "" || domain == "*" {
			continue
		}
		if domain, excluded := strings.CutPrefix(domain, "~"); excluded {
			rule.notDomains = append(rule.notDomains, strings.ToLower(domain))
		} else {
			rule.domains = append(rule.domains, strings.ToLower(domain))
		}
	}
	if !exception && len(rule.domains) == 0 {
		return browserSnippetRule{}, false
	}
	for command := range splitBrowserSnippetCommands(raw[index+len(marker):]) {
		name, arguments, _ := strings.Cut(strings.TrimSpace(command), " ")
		if name != "hide-if-matches-xpath" {
			continue
		}
		xpath, ok := parseBrowserSnippetStringArgument(arguments)
		if ok && xpath != "" {
			rule.xpaths = append(rule.xpaths, xpath)
		}
	}
	return rule, len(rule.xpaths) > 0
}

func isBrowserSnippetRule(raw string) bool {
	return strings.Contains(raw, "#$#") || strings.Contains(raw, "#@$#")
}

func splitBrowserSnippetCommands(raw string) func(func(string) bool) {
	return func(yield func(string) bool) {
		start := 0
		var quote byte
		escaped := false
		for index := range len(raw) {
			current := raw[index]
			if escaped {
				escaped = false
				continue
			}
			if current == '\\' && quote != 0 {
				escaped = true
				continue
			}
			if quote != 0 {
				if current == quote {
					quote = 0
				}
				continue
			}
			if current == '\'' || current == '"' {
				quote = current
				continue
			}
			if current == ';' {
				if !yield(raw[start:index]) {
					return
				}
				start = index + 1
			}
		}
		if start < len(raw) {
			yield(raw[start:])
		}
	}
}

func parseBrowserSnippetStringArgument(raw string) (string, bool) {
	raw = strings.TrimSpace(raw)
	if len(raw) < 2 || raw[0] != '\'' && raw[0] != '"' {
		return "", false
	}
	quote := raw[0]
	var result strings.Builder
	escaped := false
	for index := 1; index < len(raw); index++ {
		current := raw[index]
		if escaped {
			result.WriteByte(current)
			escaped = false
			continue
		}
		if current == '\\' {
			escaped = true
			continue
		}
		if current != quote {
			result.WriteByte(current)
			continue
		}
		if strings.TrimSpace(raw[index+1:]) != "" {
			return "", false
		}
		return result.String(), true
	}
	return "", false
}

func (r htmlFilterRules) browserProceduralActions(rawURL string) []string {
	parsedURL, err := url.Parse(rawURL)
	if err != nil {
		return nil
	}
	host := parsedURL.Hostname()
	var exceptions []string
	for _, rule := range r.snippets {
		if rule.exception && rule.matches(host) {
			exceptions = append(exceptions, rule.xpaths...)
		}
	}
	var result []string
	for _, rule := range r.snippets {
		if rule.exception || !rule.matches(host) {
			continue
		}
		for _, xpath := range rule.xpaths {
			if slices.Contains(exceptions, xpath) {
				continue
			}
			payload := struct {
				Selector []struct {
					Type string `json:"type"`
					Arg  string `json:"arg"`
				} `json:"selector"`
				Action struct {
					Type string `json:"type"`
				} `json:"action"`
			}{}
			payload.Selector = append(payload.Selector, struct {
				Type string `json:"type"`
				Arg  string `json:"arg"`
			}{Type: "xpath", Arg: xpath})
			// Eyeo snippets keep a node hidden after its XPath stops matching and
			// restore the hiding style if the page mutates it.
			payload.Action.Type = "hide-sticky"
			encoded, err := json.Marshal(payload)
			if err == nil {
				result = append(result, string(encoded))
			}
		}
	}
	return result
}

func (r browserSnippetRule) matches(host string) bool {
	if slices.ContainsFunc(r.notDomains, func(domain string) bool { return domainMatches(host, domain) }) {
		return false
	}
	return len(r.domains) == 0 || slices.ContainsFunc(r.domains, func(domain string) bool { return domainMatches(host, domain) })
}
