//go:build with_adblock

package adblock

import (
	"encoding/base64"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"

	"github.com/sagernet/sing-box/common/adblock/adblockrust"
)

type advancedRuleKind uint8

const (
	advancedRuleBlock advancedRuleKind = iota + 1
	advancedRuleRemoveParam
	advancedRuleURITransform
	advancedRuleURLSkip
	advancedRuleKindMax = advancedRuleURLSkip
)

type advancedRules struct {
	rules          []advancedRule
	blockRules     []advancedRule
	mutationRules  []advancedRule
	exceptionRules [advancedRuleKindMax + 1][]advancedRule
}

func (r *advancedRules) add(rule advancedRule) {
	r.rules = append(r.rules, rule)
	if rule.exception {
		r.exceptionRules[rule.kind] = append(r.exceptionRules[rule.kind], rule)
		return
	}
	switch rule.kind {
	case advancedRuleBlock:
		r.blockRules = append(r.blockRules, rule)
	case advancedRuleRemoveParam, advancedRuleURITransform, advancedRuleURLSkip:
		r.mutationRules = append(r.mutationRules, rule)
	}
}

func (r *advancedRules) merge(other advancedRules) {
	r.rules = append(r.rules, other.rules...)
	r.blockRules = append(r.blockRules, other.blockRules...)
	r.mutationRules = append(r.mutationRules, other.mutationRules...)
	for kind := range other.exceptionRules {
		r.exceptionRules[kind] = append(r.exceptionRules[kind], other.exceptionRules[kind]...)
	}
}

func (r advancedRules) empty() bool {
	return len(r.rules) == 0
}

type advancedRule struct {
	kind             advancedRuleKind
	exception        bool
	important        bool
	pattern          string
	regex            *abpPattern
	anchorHost       string
	matchCase        bool
	types            map[string]bool
	methods          map[string]bool
	to               []domainMatcher
	notTo            []domainMatcher
	from             []domainMatcher
	notFrom          []domainMatcher
	thirdParty       *bool
	strictParty      *bool
	removeParam      removeParamMatcher
	uriTransform     *adblockRegexp
	uriReplacement   string
	urlSkipSteps     []urlSkipStep
	urlSkipOnBlocked bool
	raw              string
}

type domainMatcher struct {
	text  string
	regex *adblockRegexp
}

type removeParamMatcher struct {
	all     bool
	name    string
	pattern *adblockRegexp
}

type urlSkipStep struct {
	kind    string
	value   string
	regex   *adblockRegexp
	index   int
	blocked bool
}

func parseAdvancedRule(raw string) (advancedRule, bool) {
	exception := strings.HasPrefix(raw, "@@")
	line := strings.TrimPrefix(raw, "@@")
	pattern, optionsText, found := strings.Cut(line, "$")
	if !found {
		return advancedRule{}, false
	}
	options := splitRuleOptions(optionsText)
	rule := advancedRule{
		raw:       raw,
		exception: exception,
		pattern:   pattern,
		types:     make(map[string]bool),
		methods:   make(map[string]bool),
	}
	advanced := false
	for _, option := range options {
		name, value, _ := strings.Cut(option, "=")
		name = strings.TrimSpace(name)
		negated := strings.HasPrefix(name, "~")
		name = strings.TrimPrefix(name, "~")
		switch strings.ToLower(name) {
		case "_":
			continue
		case "removeparam", "queryprune":
			if negated {
				return advancedRule{}, false
			}
			rule.kind = advancedRuleRemoveParam
			rule.removeParam = parseRemoveParamMatcher(value)
			advanced = true
		case "uritransform", "urltransform":
			if negated || value == "" {
				return advancedRule{}, false
			}
			pattern, replacement, ok := parseSubstitutionOption(value)
			if !ok {
				return advancedRule{}, false
			}
			rule.kind = advancedRuleURITransform
			rule.uriTransform = pattern
			rule.uriReplacement = replacement
			advanced = true
		case "urlskip":
			if negated || value == "" {
				return advancedRule{}, false
			}
			steps, blocked, ok := parseURLSkipSteps(value)
			if !ok {
				return advancedRule{}, false
			}
			rule.kind = advancedRuleURLSkip
			rule.urlSkipSteps = steps
			rule.urlSkipOnBlocked = blocked
			advanced = true
		case "method":
			parseMethodOption(&rule, value)
			advanced = true
		case "to":
			parseDomainMatcherOption(value, &rule.to, &rule.notTo)
			advanced = true
		case "domain", "from":
			parseDomainMatcherOption(value, &rule.from, &rule.notFrom)
		case "third-party", "3p":
			value := !negated
			rule.thirdParty = &value
		case "first-party", "1p":
			value := negated
			rule.thirdParty = &value
		case "strict1p":
			value := negated
			rule.strictParty = &value
			advanced = true
		case "strict3p":
			value := !negated
			rule.strictParty = &value
			advanced = true
		case "match-case":
			rule.matchCase = !negated
		case "important":
			rule.important = !negated
		default:
			if !applyRequestTypeOption(rule.types, name, negated) {
				return advancedRule{}, false
			}
		}
	}
	if !advanced {
		return advancedRule{}, false
	}
	if rule.kind == 0 {
		rule.kind = advancedRuleBlock
	}
	rule.regex = compileABPPattern(rule.pattern, rule.matchCase)
	if rule.regex == nil {
		return advancedRule{}, false
	}
	rule.anchorHost = parseABPAnchorHost(rule.pattern)
	return rule, true
}

func parseABPAnchorHost(pattern string) string {
	pattern, ok := strings.CutPrefix(pattern, "||")
	if !ok {
		return ""
	}
	host, _, ok := strings.Cut(pattern, "^")
	if !ok || host == "" {
		return ""
	}
	if strings.ContainsAny(host, "*/?#[]:@") {
		return ""
	}
	return strings.ToLower(strings.TrimSuffix(strings.TrimPrefix(host, "."), "."))
}

func parseRemoveParamMatcher(value string) removeParamMatcher {
	value = strings.TrimSpace(value)
	if value == "" {
		return removeParamMatcher{all: true}
	}
	if strings.HasPrefix(value, "/") {
		pattern, flags, ok := cutHTMLRegexLiteral(value)
		if ok {
			if strings.Contains(flags, "i") {
				pattern = "(?i:" + pattern + ")"
			}
			if regex, err := compileAdblockRegexp(pattern); err == nil {
				return removeParamMatcher{pattern: regex}
			}
		}
	}
	return removeParamMatcher{name: value}
}

func parseSubstitutionOption(value string) (*adblockRegexp, string, bool) {
	if !strings.HasPrefix(value, "/") {
		return nil, "", false
	}
	pattern, rest, ok := cutEscapedSlash(value[1:])
	if !ok || !strings.HasPrefix(rest, "/") {
		return nil, "", false
	}
	replacement, _, ok := cutEscapedSlash(rest[1:])
	if !ok {
		replacement = rest[1:]
	}
	regex, err := compileAdblockRegexp(pattern)
	return regex, replacement, err == nil
}

func parseURLSkipSteps(value string) ([]urlSkipStep, bool, bool) {
	var steps []urlSkipStep
	blocked := false
	for token := range strings.FieldsSeq(value) {
		switch {
		case strings.HasPrefix(token, "?") && len(token) > 1:
			steps = append(steps, urlSkipStep{kind: "query", value: token[1:]})
		case strings.HasPrefix(token, "&") && len(token) > 1:
			index, err := strconv.Atoi(token[1:])
			if err != nil || index <= 0 {
				return nil, false, false
			}
			steps = append(steps, urlSkipStep{kind: "query-index", index: index})
		case token == "#":
			steps = append(steps, urlSkipStep{kind: "hash"})
		case token == "+https":
			steps = append(steps, urlSkipStep{kind: "https"})
		case token == "-base64":
			steps = append(steps, urlSkipStep{kind: "base64"})
		case token == "-safebase64":
			steps = append(steps, urlSkipStep{kind: "safebase64"})
		case token == "-uricomponent":
			steps = append(steps, urlSkipStep{kind: "uricomponent"})
		case token == "-blocked":
			blocked = true
		case strings.HasPrefix(token, "/"):
			pattern, flags, ok := cutHTMLRegexLiteral(token)
			if !ok {
				return nil, false, false
			}
			if strings.Contains(flags, "i") {
				pattern = "(?i:" + pattern + ")"
			}
			regex, err := compileAdblockRegexp(pattern)
			if err != nil {
				return nil, false, false
			}
			steps = append(steps, urlSkipStep{kind: "regex", regex: regex})
		default:
			return nil, false, false
		}
	}
	return steps, blocked, len(steps) > 0
}

func parseMethodOption(rule *advancedRule, value string) {
	for method := range strings.SplitSeq(value, "|") {
		method = strings.ToLower(strings.TrimSpace(method))
		if method == "" {
			continue
		}
		enabled := true
		if cut, ok := strings.CutPrefix(method, "~"); ok {
			method = cut
			enabled = false
		}
		rule.methods[method] = enabled
	}
}

func parseDomainMatcherOption(value string, include *[]domainMatcher, exclude *[]domainMatcher) {
	for item := range strings.SplitSeq(value, "|") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		target := include
		if cut, ok := strings.CutPrefix(item, "~"); ok {
			item = cut
			target = exclude
		}
		*target = append(*target, parseDomainMatcher(item))
	}
}

func parseDomainMatcher(value string) domainMatcher {
	value = strings.ToLower(strings.TrimSpace(value))
	if strings.HasPrefix(value, "/") {
		pattern, flags, ok := cutHTMLRegexLiteral(value)
		if ok {
			if strings.Contains(flags, "i") {
				pattern = "(?i:" + pattern + ")"
			}
			if regex, err := compileAdblockRegexp(pattern); err == nil {
				return domainMatcher{regex: regex}
			}
		}
	}
	return domainMatcher{text: strings.TrimSuffix(strings.TrimPrefix(value, "."), ".")}
}

func (r advancedRules) applyCheck(ctx *adblockRequestContext, result adblockrust.CheckResult) adblockrust.CheckResult {
	if r.empty() {
		return result
	}
	req := advancedRequestFromContext(ctx)
	exceptions := r.newExceptionCache(req)
	if exceptions.has(advancedRuleBlock) && !result.Important {
		result.Matched = false
		result.Exception = "NetworkFilter"
		result.Redirect = ""
		result.RewrittenURL = ""
		return result
	}
	for _, rule := range r.blockRules {
		if !rule.matches(req) {
			continue
		}
		if result.Exception != "" && !rule.important {
			continue
		}
		result.Matched = true
		result.Important = rule.important
		result.Exception = ""
		result.Filter = rule.raw
	}
	return result
}

func (r advancedRules) mutateRequest(ctx *adblockRequestContext, blocked bool) (bool, bool) {
	if r.empty() {
		return false, false
	}
	req := advancedRequestFromContext(ctx)
	exceptions := r.newExceptionCache(req)
	changed := false
	redirected := false
	for _, rule := range r.mutationRules {
		if rule.kind == advancedRuleURLSkip && blocked && !rule.urlSkipOnBlocked {
			continue
		}
		if !rule.matches(req) || exceptions.has(rule.kind) {
			continue
		}
		switch rule.kind {
		case advancedRuleRemoveParam:
			if removeURLParams(ctx.requestURL, rule.removeParam) {
				ctx.setRequestURL(ctx.requestURL)
				changed = true
				req = advancedRequestFromContext(ctx)
				exceptions = r.newExceptionCache(req)
			}
		case advancedRuleURITransform:
			if transformURI(ctx.requestURL, rule.uriTransform, rule.uriReplacement) {
				ctx.setRequestURL(ctx.requestURL)
				changed = true
				req = advancedRequestFromContext(ctx)
				exceptions = r.newExceptionCache(req)
			}
		case advancedRuleURLSkip:
			target := applyURLSkip(ctx.requestURL, rule.urlSkipSteps)
			if target == nil {
				continue
			}
			if ctx.requestType == "document" || ctx.requestType == "subdocument" {
				ctx.writer.Header().Set("Location", target.String())
				ctx.writer.WriteHeader(http.StatusFound)
				redirected = true
				continue
			}
			ctx.setRequestURL(target)
			changed = true
			req = advancedRequestFromContext(ctx)
			exceptions = r.newExceptionCache(req)
		}
	}
	return changed, redirected
}

type advancedExceptionCache struct {
	rules advancedRules
	req   advancedRequest
	set   [advancedRuleKindMax + 1]bool
	value [advancedRuleKindMax + 1]bool
}

func (r advancedRules) newExceptionCache(req advancedRequest) advancedExceptionCache {
	return advancedExceptionCache{rules: r, req: req}
}

func (c *advancedExceptionCache) has(kind advancedRuleKind) bool {
	if kind <= 0 || kind > advancedRuleKindMax {
		return false
	}
	if c.set[kind] {
		return c.value[kind]
	}
	c.set[kind] = true
	for _, rule := range c.rules.exceptionRules[kind] {
		if rule.matches(c.req) {
			c.value[kind] = true
			return true
		}
	}
	return false
}

type advancedRequest struct {
	requestURL  string
	sourceURL   string
	requestType string
	method      string
	thirdParty  bool
	strictParty bool
	requestHost string
	sourceHost  string
}

func advancedRequestFromContext(ctx *adblockRequestContext) advancedRequest {
	sourceURL, _ := ctx.sourceURLValueString()
	requestURL := ctx.requestURLValue()
	requestHost := parsedHostname(requestURL)
	sourceHost := parsedHostname(sourceURL)
	return advancedRequest{
		requestURL:  requestURL,
		sourceURL:   sourceURL,
		requestType: ctx.requestType,
		method:      strings.ToLower(ctx.request.Method),
		thirdParty:  companionThirdParty(requestURL, sourceURL),
		strictParty: sourceHost == "" || !strings.EqualFold(requestHost, sourceHost),
		requestHost: requestHost,
		sourceHost:  sourceHost,
	}
}

func parsedHostname(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	return strings.ToLower(strings.TrimSuffix(parsed.Hostname(), "."))
}

func (r advancedRule) matches(req advancedRequest) bool {
	if len(r.types) > 0 {
		enabled, ok := r.types[req.requestType]
		if ok && !enabled {
			return false
		}
		if !ok && !hasPositiveType(r.types) {
			return false
		}
	}
	if len(r.methods) > 0 {
		enabled, ok := r.methods[req.method]
		if ok && !enabled {
			return false
		}
		if !ok && !hasPositiveBool(r.methods) {
			return false
		}
	}
	if r.thirdParty != nil && *r.thirdParty != req.thirdParty {
		return false
	}
	if r.strictParty != nil && *r.strictParty != req.strictParty {
		return false
	}
	if domainMatchersMatch(r.notFrom, req.sourceHost) || domainMatchersMatch(r.notTo, req.requestHost) {
		return false
	}
	if len(r.from) > 0 && !domainMatchersMatch(r.from, req.sourceHost) {
		return false
	}
	if len(r.to) > 0 && !domainMatchersMatch(r.to, req.requestHost) {
		return false
	}
	if r.anchorHost != "" && !domainMatches(req.requestHost, r.anchorHost) {
		return false
	}
	if r.regex == nil || !r.regex.MatchString(req.requestURL) {
		return false
	}
	return true
}

func hasPositiveBool(values map[string]bool) bool {
	for _, enabled := range values {
		if enabled {
			return true
		}
	}
	return false
}

func domainMatchersMatch(matchers []domainMatcher, host string) bool {
	return slices.ContainsFunc(matchers, func(matcher domainMatcher) bool {
		return matcher.matches(host)
	})
}

func (m domainMatcher) matches(host string) bool {
	host = strings.TrimSuffix(strings.ToLower(host), ".")
	if host == "" {
		return false
	}
	if m.regex != nil {
		return m.regex.MatchString(host)
	}
	if strings.HasSuffix(m.text, ".*") {
		entity := strings.TrimSuffix(m.text, ".*")
		registrable := registrableDomainFallback(host)
		label, _, ok := strings.Cut(registrable, ".")
		return ok && label == entity
	}
	return domainMatches(host, m.text)
}

func removeURLParams(u *url.URL, matcher removeParamMatcher) bool {
	if u == nil || u.RawQuery == "" {
		return false
	}
	values, err := url.ParseQuery(u.RawQuery)
	if err != nil {
		return false
	}
	changed := false
	for name, vals := range values {
		if matcher.matches(name, vals) {
			delete(values, name)
			changed = true
		}
	}
	if changed {
		u.RawQuery = values.Encode()
	}
	return changed
}

func (m removeParamMatcher) matches(name string, values []string) bool {
	if m.all {
		return true
	}
	if m.name != "" {
		return name == m.name
	}
	if m.pattern == nil {
		return false
	}
	if len(values) == 0 {
		return m.pattern.MatchString(name + "=")
	}
	return slices.ContainsFunc(values, func(value string) bool {
		return m.pattern.MatchString(name + "=" + value)
	})
}

func transformURI(u *url.URL, pattern *adblockRegexp, replacement string) bool {
	if u == nil || pattern == nil {
		return false
	}
	uri := u.EscapedPath()
	if uri == "" {
		uri = "/"
	}
	if u.RawQuery != "" {
		uri += "?" + u.RawQuery
	}
	if u.Fragment != "" {
		uri += "#" + u.EscapedFragment()
	}
	rewritten := pattern.ReplaceAllString(uri, replacement)
	if rewritten == uri {
		return false
	}
	parsed, err := url.Parse(rewritten)
	if err != nil {
		return false
	}
	u.Path = parsed.Path
	u.RawPath = parsed.RawPath
	u.RawQuery = parsed.RawQuery
	u.Fragment = parsed.Fragment
	u.RawFragment = parsed.RawFragment
	return true
}

func applyURLSkip(u *url.URL, steps []urlSkipStep) *url.URL {
	if u == nil {
		return nil
	}
	current := u.String()
	for _, step := range steps {
		switch step.kind {
		case "query":
			current = u.Query().Get(step.value)
		case "query-index":
			pairs := strings.Split(u.RawQuery, "&")
			if step.index > len(pairs) {
				return nil
			}
			name, _, _ := strings.Cut(pairs[step.index-1], "=")
			current, _ = url.QueryUnescape(name)
		case "hash":
			current = u.Fragment
		case "regex":
			matches := step.regex.FindStringSubmatch(current)
			if len(matches) < 2 {
				return nil
			}
			current = matches[1]
		case "https":
			if strings.HasPrefix(current, "http://") {
				current = "https://" + strings.TrimPrefix(current, "http://")
			} else if !strings.Contains(current, "://") {
				current = "https://" + current
			}
		case "base64":
			decoded, err := base64.StdEncoding.DecodeString(current)
			if err != nil {
				return nil
			}
			current = string(decoded)
		case "safebase64":
			decoded, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(current, "="))
			if err != nil {
				return nil
			}
			current = string(decoded)
		case "uricomponent":
			decoded, err := url.QueryUnescape(current)
			if err != nil {
				return nil
			}
			current = decoded
		}
	}
	target, err := url.Parse(current)
	if err != nil || target.Scheme == "" || target.Host == "" {
		return nil
	}
	return target
}
