//go:build with_adblock

package adblock

import (
	"bytes"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/sagernet/sing-box/option"
)

type parsedFilter struct {
	AdblockFilterMetadata
	Rules     []string
	Companion companionRules
	HTML      htmlFilterRules
	Advanced  advancedRules
}

func parseFilterLines(content []byte) parsedFilter {
	return parseFilterLinesWithEnvironment(content, nil)
}

// parseFilterMetadataWithEnvironment extracts filter-list metadata and counts
// network rules without retaining the rules themselves. Metadata lookups run
// while the full adblock engine can be active, so building a second in-memory
// copy of a large EasyList-sized ruleset here creates an unnecessary memory
// spike.
func parseFilterMetadataWithEnvironment(content []byte, environment *option.AdblockEnvironment) parsedFilter {
	var result parsedFilter
	preprocessor := newFilterPreprocessor(environment)
	for line := range bytes.SplitSeq(content, []byte{'\n'}) {
		rule := strings.TrimSpace(string(line))
		if rule == "" {
			continue
		}
		if preprocessor.handleDirective(rule) {
			continue
		}
		if !preprocessor.active() {
			continue
		}
		if strings.HasPrefix(rule, "!") || strings.HasPrefix(rule, "#") && !isGlobalCosmeticFilterRule(rule) {
			result.parseFilterComment(rule[1:])
			continue
		}
		if _, ok := parseHTMLFilterRule(rule); ok || isHTMLFilterRule(rule) {
			continue
		}
		if _, ok := parseBrowserSnippetRule(rule); ok || isBrowserSnippetRule(rule) {
			continue
		}
		if _, ok := parseCompanionRule(rule); ok {
			continue
		}
		if _, ok := parseAdvancedRule(rule); ok {
			continue
		}
		result.RuleCount++
	}
	return result
}

func parseFilterLinesWithEnvironment(content []byte, environment *option.AdblockEnvironment) parsedFilter {
	var result parsedFilter
	preprocessor := newFilterPreprocessor(environment)
	for line := range bytes.SplitSeq(content, []byte{'\n'}) {
		rule := strings.TrimSpace(string(line))
		if rule == "" {
			continue
		}
		if preprocessor.handleDirective(rule) {
			continue
		}
		if !preprocessor.active() {
			continue
		}
		if htmlFilter, ok := parseHTMLFilterRule(rule); ok {
			result.HTML.add(htmlFilter)
			continue
		}
		if snippet, ok := parseBrowserSnippetRule(rule); ok {
			result.HTML.addBrowserSnippet(snippet)
			continue
		}
		if isBrowserSnippetRule(rule) {
			continue
		}
		if isHTMLFilterRule(rule) {
			continue
		}
		if strings.HasPrefix(rule, "!") || strings.HasPrefix(rule, "#") && !isGlobalCosmeticFilterRule(rule) {
			result.parseFilterComment(rule[1:])
			continue
		}
		if companion, ok := parseCompanionRule(rule); ok {
			result.Companion.add(companion)
			continue
		}
		if advanced, ok := parseAdvancedRule(rule); ok {
			result.Advanced.add(advanced)
			continue
		}
		result.Rules = append(result.Rules, rule)
	}
	return result
}

func isGlobalCosmeticFilterRule(rule string) bool {
	return strings.HasPrefix(rule, "##") ||
		strings.HasPrefix(rule, "#@#") ||
		strings.HasPrefix(rule, "#?#") ||
		strings.HasPrefix(rule, "#$#") ||
		strings.HasPrefix(rule, "#%#")
}

type filterPreprocessor struct {
	stack       []filterPreprocessorFrame
	environment filterPreprocessorEnvironment
}

type filterPreprocessorFrame struct {
	parentActive bool
	condition    bool
	inElse       bool
}

func newFilterPreprocessor(environment *option.AdblockEnvironment) filterPreprocessor {
	return filterPreprocessor{environment: newFilterPreprocessorEnvironment(environment)}
}

func (p *filterPreprocessor) handleDirective(line string) bool {
	directive, ok := strings.CutPrefix(line, "!#")
	if !ok {
		return false
	}
	directive = strings.TrimSpace(directive)
	name, expression, _ := strings.Cut(directive, " ")
	switch strings.ToLower(name) {
	case "if":
		parentActive := p.active()
		p.stack = append(p.stack, filterPreprocessorFrame{
			parentActive: parentActive,
			condition:    p.environment.evaluate(expression),
		})
	case "else":
		if len(p.stack) > 0 {
			p.stack[len(p.stack)-1].inElse = true
		}
	case "endif":
		if len(p.stack) > 0 {
			p.stack = p.stack[:len(p.stack)-1]
		}
	default:
		return false
	}
	return true
}

func (p *filterPreprocessor) active() bool {
	for _, frame := range p.stack {
		if !frame.parentActive {
			return false
		}
		active := frame.condition
		if frame.inElse {
			active = !active
		}
		if !active {
			return false
		}
	}
	return true
}

func evaluateFilterPreprocessorExpression(expression string) bool {
	return defaultFilterPreprocessorEnvironment().evaluate(expression)
}

type filterPreprocessorEnvironment map[string]bool

func defaultFilterPreprocessorEnvironment() filterPreprocessorEnvironment {
	return filterPreprocessorEnvironment{
		"env_firefox":              true,
		"ext_ublock":               true,
		"ext_ubo":                  true,
		"ext_devbuild":             true,
		"false_positive":           true,
		"cap_html_filtering":       true,
		"cap_ipaddress":            true,
		"cap_user_stylesheet":      true,
		"adguard_ext_firefox":      true,
		"env_chromium":             false,
		"env_legacy":               false,
		"env_mobile":               false,
		"env_mv3":                  false,
		"env_safari":               false,
		"env_edge":                 false,
		"env_opera":                false,
		"ext_abp":                  false,
		"ext_ubol":                 false,
		"false":                    false,
		"adguard":                  false,
		"adguard_app_android":      false,
		"adguard_app_cli":          false,
		"adguard_app_ios":          false,
		"adguard_app_mac":          false,
		"adguard_app_windows":      false,
		"adguard_ext_android_cb":   false,
		"adguard_ext_chromium":     false,
		"adguard_ext_chromium_mv3": false,
		"adguard_ext_edge":         false,
		"adguard_ext_opera":        false,
		"adguard_ext_safari":       false,
	}
}

func newFilterPreprocessorEnvironment(environment *option.AdblockEnvironment) filterPreprocessorEnvironment {
	values := defaultFilterPreprocessorEnvironment()
	if environment == nil {
		return values
	}
	values["env_chromium"] = environment.Chromium
	values["env_firefox"] = environment.Firefox
	values["env_legacy"] = environment.Legacy
	values["env_mobile"] = environment.Mobile
	values["env_mv3"] = environment.MV3
	values["env_safari"] = environment.Safari
	values["env_edge"] = environment.Edge
	values["env_opera"] = environment.Opera
	values["ext_abp"] = environment.ABP
	values["ext_ubo"] = environment.UBO
	values["ext_ublock"] = environment.UBlock || environment.UBO
	values["ext_ubol"] = environment.UBOL
	values["ext_devbuild"] = environment.DevBuild
	values["cap_html_filtering"] = environment.HTMLFiltering
	values["cap_ipaddress"] = environment.IPAddress
	values["cap_user_stylesheet"] = environment.UserStylesheet
	values["adguard"] = environment.AdGuard
	values["adguard_ext_firefox"] = environment.Firefox
	values["adguard_ext_chromium"] = environment.Chromium
	values["adguard_ext_chromium_mv3"] = environment.MV3
	values["adguard_ext_edge"] = environment.Edge
	values["adguard_ext_opera"] = environment.Opera || environment.Chromium
	values["adguard_ext_safari"] = environment.Safari
	return values
}

func (e filterPreprocessorEnvironment) evaluate(expression string) bool {
	parser := filterPreprocessorExpressionParser{input: expression, environment: e}
	return parser.parseExpression()
}

func filterPreprocessorCapability(name string) bool {
	return defaultFilterPreprocessorEnvironment()[strings.ToLower(strings.TrimSpace(name))]
}

type filterPreprocessorExpressionParser struct {
	input       string
	offset      int
	environment filterPreprocessorEnvironment
}

func (p *filterPreprocessorExpressionParser) parseExpression() bool {
	return p.parseOr()
}

func (p *filterPreprocessorExpressionParser) parseOr() bool {
	value := p.parseAnd()
	for {
		p.skipSpaces()
		if !strings.HasPrefix(p.input[p.offset:], "||") {
			return value
		}
		p.offset += 2
		value = value || p.parseAnd()
	}
}

func (p *filterPreprocessorExpressionParser) parseAnd() bool {
	value := p.parseUnary()
	for {
		p.skipSpaces()
		if !strings.HasPrefix(p.input[p.offset:], "&&") {
			return value
		}
		p.offset += 2
		value = value && p.parseUnary()
	}
}

func (p *filterPreprocessorExpressionParser) parseUnary() bool {
	p.skipSpaces()
	if p.offset < len(p.input) && p.input[p.offset] == '!' {
		p.offset++
		return !p.parseUnary()
	}
	return p.parsePrimary()
}

func (p *filterPreprocessorExpressionParser) parsePrimary() bool {
	p.skipSpaces()
	if p.offset >= len(p.input) {
		return false
	}
	if p.input[p.offset] == '(' {
		p.offset++
		value := p.parseExpression()
		p.skipSpaces()
		if p.offset < len(p.input) && p.input[p.offset] == ')' {
			p.offset++
		}
		return value
	}
	start := p.offset
	for p.offset < len(p.input) {
		ch := p.input[p.offset]
		if ch == '!' || ch == '&' || ch == '|' || ch == '(' || ch == ')' || ch == ' ' || ch == '\t' {
			break
		}
		p.offset++
	}
	if start == p.offset {
		p.offset++
		return false
	}
	return p.environment[strings.ToLower(strings.TrimSpace(p.input[start:p.offset]))]
}

func (p *filterPreprocessorExpressionParser) skipSpaces() {
	for p.offset < len(p.input) && (p.input[p.offset] == ' ' || p.input[p.offset] == '\t') {
		p.offset++
	}
}

func (f *parsedFilter) parseFilterComment(line string) {
	line = strings.TrimLeft(line, " ")
	switch {
	case parseFilterCommentValue(line, "Title", &f.Title):
	case parseFilterCommentValue(line, "Description", &f.Description):
	case parseFilterCommentValue(line, "Last Modified", &f.LastModified):
	case parseFilterCommentValue(line, "Expires", &f.Expires):
		f.ExpiresInterval = parseFilterExpires(f.Expires)
	case parseFilterCommentValue(line, "License", &f.License):
	case parseFilterCommentValue(line, "Homepage", &f.Homepage):
	case parseFilterCommentValue(line, "Forums", &f.Forums):
	}
}

func parseFilterCommentValue(line string, identifier string, value *string) bool {
	if len(line) < len(identifier)+2 || !strings.EqualFold(line[:len(identifier)], identifier) || line[len(identifier):len(identifier)+2] != ": " {
		return false
	}
	*value = line[len(identifier)+2:]
	return true
}

var filterExpiresUnit = map[string]time.Duration{
	"s":       time.Second,
	"sec":     time.Second,
	"secs":    time.Second,
	"second":  time.Second,
	"seconds": time.Second,
	"m":       time.Minute,
	"min":     time.Minute,
	"mins":    time.Minute,
	"minute":  time.Minute,
	"minutes": time.Minute,
	"h":       time.Hour,
	"hr":      time.Hour,
	"hrs":     time.Hour,
	"hour":    time.Hour,
	"hours":   time.Hour,
	"d":       24 * time.Hour,
	"day":     24 * time.Hour,
	"days":    24 * time.Hour,
	"w":       7 * 24 * time.Hour,
	"week":    7 * 24 * time.Hour,
	"weeks":   7 * 24 * time.Hour,
	"month":   30 * 24 * time.Hour,
	"months":  30 * 24 * time.Hour,
	"year":    365 * 24 * time.Hour,
	"years":   365 * 24 * time.Hour,
}

var filterExpiresPattern = regexp.MustCompile(`^([0-9]+(?:\.[0-9]+)?|\.[0-9]+)\s*([A-Za-z]+)$`)

func parseFilterExpires(value string) time.Duration {
	matches := filterExpiresPattern.FindStringSubmatch(strings.TrimSpace(value))
	if matches == nil {
		return 0
	}
	amount, err := strconv.ParseFloat(matches[1], 64)
	if err != nil || amount <= 0 {
		return 0
	}
	unit, loaded := filterExpiresUnit[strings.ToLower(matches[2])]
	if !loaded {
		return 0
	}
	ttl := time.Duration(amount * float64(unit))
	if ttl <= 0 {
		return 0
	}

	return ttl
}
