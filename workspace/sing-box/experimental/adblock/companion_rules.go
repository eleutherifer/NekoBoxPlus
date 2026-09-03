//go:build with_adblock

package adblock

import (
	"bytes"
	"net/http"
	"net/url"
	"slices"
	"strings"
)

type companionRuleKind uint8

const (
	companionRuleReplace companionRuleKind = iota + 1
	companionRulePermissions
	companionRuleHeader
	companionRuleCookie
)

type companionRules struct {
	replace     []companionRule
	permissions []companionRule
	headers     []companionRule
	cookies     []companionRule
}

func (r *companionRules) add(rule companionRule) {
	switch rule.kind {
	case companionRuleReplace:
		r.replace = append(r.replace, rule)
	case companionRulePermissions:
		r.permissions = append(r.permissions, rule)
	case companionRuleHeader:
		r.headers = append(r.headers, rule)
	case companionRuleCookie:
		r.cookies = append(r.cookies, rule)
	}
}

func (r *companionRules) merge(other companionRules) {
	r.replace = append(r.replace, other.replace...)
	r.permissions = append(r.permissions, other.permissions...)
	r.headers = append(r.headers, other.headers...)
	r.cookies = append(r.cookies, other.cookies...)
}

func (r companionRules) empty() bool {
	return len(r.replace) == 0 && len(r.permissions) == 0 && len(r.headers) == 0 && len(r.cookies) == 0
}

type companionRule struct {
	kind       companionRuleKind
	exception  bool
	important  bool
	pattern    string
	regex      *abpPattern
	matchCase  bool
	types      map[string]bool
	domains    []string
	notDomains []string
	thirdParty *bool
	value      string
	replace    companionReplace
	header     companionHeader
	cookie     companionCookie
	raw        string
}

type companionReplace struct {
	pattern     *adblockRegexp
	replacement string
}

type companionHeader struct {
	name       string
	valueRegex *adblockRegexp
}

type companionCookie struct {
	name   string
	domain string
	path   string
}

func parseCompanionRule(raw string) (companionRule, bool) {
	exception := strings.HasPrefix(raw, "@@")
	line := strings.TrimPrefix(raw, "@@")
	pattern, optionsText, found := strings.Cut(line, "$")
	if !found {
		return companionRule{}, false
	}
	options := splitRuleOptions(optionsText)
	var rule companionRule
	rule.raw = raw
	rule.exception = exception
	rule.pattern = pattern
	rule.types = make(map[string]bool)
	for _, option := range options {
		name, value, _ := strings.Cut(option, "=")
		negated := strings.HasPrefix(name, "~")
		name = strings.TrimPrefix(name, "~")
		switch strings.ToLower(name) {
		case "replace":
			if negated || value == "" {
				return companionRule{}, false
			}
			parsed, ok := parseReplaceOption(value)
			if !ok {
				return companionRule{}, false
			}
			rule.kind = companionRuleReplace
			rule.replace = parsed
		case "permissions":
			if negated || value == "" {
				return companionRule{}, false
			}
			rule.kind = companionRulePermissions
			rule.value = value
		case "header":
			if negated || value == "" {
				return companionRule{}, false
			}
			parsed, ok := parseHeaderOption(value)
			if !ok {
				return companionRule{}, false
			}
			rule.kind = companionRuleHeader
			rule.header = parsed
		case "cookie":
			if negated || value == "" {
				return companionRule{}, false
			}
			rule.kind = companionRuleCookie
			rule.cookie = parseCookieOption(value)
		case "domain", "from":
			for _, domain := range strings.Split(value, "|") {
				if domain == "" || strings.HasPrefix(domain, "/") && strings.HasSuffix(domain, "/") {
					continue
				}
				if cut, ok := strings.CutPrefix(domain, "~"); ok {
					rule.notDomains = append(rule.notDomains, strings.ToLower(cut))
				} else {
					rule.domains = append(rule.domains, strings.ToLower(domain))
				}
			}
		case "third-party", "3p":
			value := !negated
			rule.thirdParty = &value
		case "first-party", "1p":
			value := negated
			rule.thirdParty = &value
		case "match-case":
			rule.matchCase = !negated
		case "important":
			rule.important = !negated
		default:
			if !applyRequestTypeOption(rule.types, name, negated) {
				return companionRule{}, false
			}
		}
	}
	if rule.kind == 0 {
		return companionRule{}, false
	}
	rule.regex = compileABPPattern(rule.pattern, rule.matchCase)
	if rule.regex == nil {
		return companionRule{}, false
	}
	return rule, true
}

var companionRequestTypes = map[string]bool{
	"script": true, "image": true, "stylesheet": true, "font": true,
	"media": true, "xmlhttprequest": true, "xhr": true, "document": true,
	"subdocument": true, "ping": true, "websocket": true, "other": true,
	// Common ABP/uBlock request-type aliases.
	"doc": true, "frame": true, "css": true, "js": true, "img": true,
	"obj": true, "object": true,
}

// adblockRequestTypeAlias canonicalizes an ABP/uBlock request-type alias to the
// content-type string used by the matching engine.
func adblockRequestTypeAlias(name string) string {
	switch strings.ToLower(name) {
	case "xhr":
		return "xmlhttprequest"
	case "doc":
		return "document"
	case "frame":
		return "subdocument"
	case "css":
		return "stylesheet"
	case "js":
		return "script"
	case "img":
		return "image"
	case "obj", "object":
		return "object"
	}
	return name
}

// applyRequestTypeOption records a request-type option into the given type map.
// It accepts the standard content types, their ABP/uBlock aliases (doc, frame,
// css, js, img, ...) and the catch-all "$all". It returns true when name is a
// recognized request-type option, and false for unsupported options so the
// caller can drop the rule instead of silently dropping the option.
//
// Dropping the option would broaden the rule's scope: for example,
// "*$strict3p,ipaddress=0.0.0.0" is meant to block only third-party requests
// that resolve to 0.0.0.0; if the unsupported "ipaddress=" constraint is simply
// ignored, the rule degrades into blocking every third-party request.
func applyRequestTypeOption(types map[string]bool, name string, negated bool) bool {
	lname := strings.ToLower(name)
	if lname == "all" {
		return true
	}
	if !companionRequestTypes[lname] {
		return false
	}
	types[adblockRequestTypeAlias(lname)] = !negated
	return true
}

func splitRuleOptions(options string) []string {
	var result []string
	var builder strings.Builder
	escaped := false
	inRegex := false
	for _, ch := range options {
		if escaped {
			builder.WriteRune(ch)
			escaped = false
			continue
		}
		if ch == '\\' {
			builder.WriteRune(ch)
			escaped = true
			continue
		}
		if ch == '/' {
			inRegex = !inRegex
			builder.WriteRune(ch)
			continue
		}
		if ch == ',' && !inRegex {
			result = append(result, builder.String())
			builder.Reset()
			continue
		}
		builder.WriteRune(ch)
	}
	if builder.Len() > 0 {
		result = append(result, builder.String())
	}
	return result
}

func parseReplaceOption(value string) (companionReplace, bool) {
	if !strings.HasPrefix(value, "/") {
		return companionReplace{}, false
	}
	pattern, rest, ok := cutEscapedSlash(value[1:])
	if !ok || !strings.HasPrefix(rest, "/") {
		return companionReplace{}, false
	}
	replacement, _, ok := cutEscapedSlash(rest[1:])
	if !ok {
		replacement = rest[1:]
	}
	regex, err := compileAdblockRegexp(pattern)
	if err != nil {
		return companionReplace{}, false
	}
	return companionReplace{pattern: regex, replacement: replacement}, true
}

func cutEscapedSlash(value string) (string, string, bool) {
	escaped := false
	for index, ch := range value {
		if escaped {
			escaped = false
			continue
		}
		if ch == '\\' {
			escaped = true
			continue
		}
		if ch == '/' {
			return value[:index], value[index:], true
		}
	}
	return value, "", false
}

func parseHeaderOption(value string) (companionHeader, bool) {
	name, pattern, _ := strings.Cut(value, ":")
	name = http.CanonicalHeaderKey(strings.TrimSpace(name))
	if name == "" {
		return companionHeader{}, false
	}
	var regex *adblockRegexp
	if pattern != "" {
		pattern = strings.TrimSpace(pattern)
		if strings.HasPrefix(pattern, "/") && strings.HasSuffix(pattern, "/") && len(pattern) > 1 {
			compiled, err := compileAdblockRegexp(pattern[1 : len(pattern)-1])
			if err != nil {
				return companionHeader{}, false
			}
			regex = compiled
		} else {
			regex = mustCompileAdblockRegexp(quoteAdblockRegexpMeta(pattern))
		}
	}
	return companionHeader{name: name, valueRegex: regex}, true
}

func parseCookieOption(value string) companionCookie {
	parts := strings.Split(value, ";")
	cookie := companionCookie{name: strings.TrimSpace(parts[0])}
	for _, part := range parts[1:] {
		key, val, _ := strings.Cut(strings.TrimSpace(part), "=")
		switch strings.ToLower(key) {
		case "domain":
			cookie.domain = strings.ToLower(strings.TrimPrefix(val, "."))
		case "path":
			cookie.path = val
		}
	}
	return cookie
}

func compileABPPattern(pattern string, matchCase bool) *abpPattern {
	if host, ok := newABPHostPattern(pattern, matchCase); ok {
		return &abpPattern{host: host}
	}
	if fast, ok := newABPFastPattern(pattern, matchCase); ok {
		return &abpPattern{fast: fast}
	}
	if strings.HasPrefix(pattern, "/") && strings.HasSuffix(pattern, "/") && len(pattern) > 1 {
		regex, _ := compileAdblockRegexp(pattern[1 : len(pattern)-1])
		if regex == nil {
			return nil
		}
		return &abpPattern{regex: regex}
	}
	var builder strings.Builder
	if !matchCase {
		builder.WriteString("(?i)")
	}
	if strings.HasPrefix(pattern, "||") {
		builder.WriteString(`^[a-z][a-z0-9+.-]*://([^/?#]*\.)?`)
		pattern = pattern[2:]
	} else if strings.HasPrefix(pattern, "|") {
		builder.WriteString("^")
		pattern = pattern[1:]
	}
	anchoredEnd := strings.HasSuffix(pattern, "|")
	pattern = strings.TrimSuffix(pattern, "|")
	for _, ch := range pattern {
		switch ch {
		case '*':
			builder.WriteString(".*")
		case '^':
			builder.WriteString(`(?:[^\w\d_.%-]|$)`)
		default:
			builder.WriteString(quoteAdblockRegexpMeta(string(ch)))
		}
	}
	if anchoredEnd {
		builder.WriteString("$")
	}
	regex, _ := compileAdblockRegexp(builder.String())
	if regex == nil {
		return nil
	}
	return &abpPattern{regex: regex}
}

func (r companionRules) applyHeaders(requestContext *adblockRequestContext, response *http.Response) {
	if r.empty() {
		return
	}
	req := companionRequestFromContext(requestContext)
	permissionsException := companionHasException(r.permissions, req)
	for _, rule := range r.permissions {
		if rule.exception || permissionsException || !rule.matches(req) {
			continue
		}
		if req.requestType == "document" || req.requestType == "subdocument" {
			response.Header.Add("Permissions-Policy", rule.value)
		}
	}
	headersException := companionHasException(r.headers, req)
	for _, rule := range r.headers {
		if rule.exception || headersException || !rule.matches(req) {
			continue
		}
		values := response.Header.Values(rule.header.name)
		if len(values) == 0 {
			continue
		}
		if rule.header.valueRegex == nil || slices.ContainsFunc(values, rule.header.valueRegex.MatchString) {
			response.Header.Del(rule.header.name)
		}
	}
	if len(r.cookies) > 0 && !companionHasException(r.cookies, req) {
		filterSetCookieHeaders(response, r.cookies, req)
	}
}

func (r companionRules) applyReplace(s *Service, requestContext *adblockRequestContext, response *http.Response) (bool, error) {
	if len(r.replace) == 0 || response.Body == nil {
		return false, nil
	}
	req := companionRequestFromContext(requestContext)
	if companionHasException(r.replace, req) {
		return false, nil
	}
	var replacements []companionReplace
	for _, rule := range r.replace {
		if !rule.exception && rule.matches(req) {
			replacements = append(replacements, rule.replace)
		}
	}
	if len(replacements) == 0 {
		return false, nil
	}
	limit := s.options.Filtering.ReplaceMaxBodyValue()
	body, ok, err := s.readBoundedRewritableBody(requestContext.request.Method, response, limit, false)
	if err != nil {
		return false, err
	}
	if !ok {
		return false, nil
	}
	rewritten := body.content
	for _, replacement := range replacements {
		rewritten = replacement.pattern.ReplaceAll(rewritten, []byte(replacement.replacement))
	}
	if bytes.Equal(rewritten, body.content) {
		body.restore(response)
		return false, nil
	}
	body.replace(response, rewritten)
	return true, nil
}

type companionRequest struct {
	requestURL  string
	sourceURL   string
	requestType string
	thirdParty  bool
}

func companionRequestFromContext(ctx *adblockRequestContext) companionRequest {
	sourceURL, _ := ctx.sourceURLValueString()
	requestURL := ctx.requestURLValue()
	return companionRequest{
		requestURL:  requestURL,
		sourceURL:   sourceURL,
		requestType: ctx.requestType,
		thirdParty:  companionThirdParty(requestURL, sourceURL),
	}
}

func companionThirdParty(requestURL string, sourceURL string) bool {
	if sourceURL == "" {
		return true
	}
	requestParsed, err := url.Parse(requestURL)
	if err != nil {
		return true
	}
	sourceParsed, err := url.Parse(sourceURL)
	if err != nil {
		return true
	}
	return !sameRegistrableDomain(requestParsed.Hostname(), sourceParsed.Hostname())
}

func (r companionRule) matches(req companionRequest) bool {
	if len(r.types) > 0 {
		enabled, ok := r.types[req.requestType]
		if ok && !enabled {
			return false
		}
		if !ok && !hasPositiveType(r.types) {
			return false
		}
	}
	if r.thirdParty != nil && *r.thirdParty != req.thirdParty {
		return false
	}
	sourceHost := ""
	if req.sourceURL != "" {
		if parsed, err := url.Parse(req.sourceURL); err == nil {
			sourceHost = strings.ToLower(parsed.Hostname())
		}
	}
	for _, domain := range r.notDomains {
		if domainMatches(sourceHost, domain) {
			return false
		}
	}
	if len(r.domains) > 0 && !slices.ContainsFunc(r.domains, func(domain string) bool {
		return domainMatches(sourceHost, domain)
	}) {
		return false
	}
	if r.regex == nil || !r.regex.MatchString(req.requestURL) {
		return false
	}
	return true
}

func hasPositiveType(types map[string]bool) bool {
	for _, enabled := range types {
		if enabled {
			return true
		}
	}
	return false
}

func companionHasException(rules []companionRule, req companionRequest) bool {
	for _, rule := range rules {
		if rule.exception && rule.matches(req) {
			return true
		}
	}
	return false
}

func domainMatches(host string, domain string) bool {
	host = strings.TrimSuffix(strings.ToLower(host), ".")
	domain = strings.TrimSuffix(strings.TrimPrefix(strings.ToLower(domain), "."), ".")
	return host == domain || strings.HasSuffix(host, "."+domain)
}

func filterSetCookieHeaders(response *http.Response, rules []companionRule, req companionRequest) {
	values := response.Header.Values("Set-Cookie")
	if len(values) == 0 {
		return
	}
	response.Header.Del("Set-Cookie")
	for _, value := range values {
		if shouldRemoveSetCookie(value, rules, req) {
			continue
		}
		response.Header.Add("Set-Cookie", value)
	}
}

func shouldRemoveSetCookie(value string, rules []companionRule, req companionRequest) bool {
	cookieName, attrs := parseSetCookieHeader(value)
	for _, rule := range rules {
		if rule.exception || !rule.matches(req) || rule.cookie.name != cookieName {
			continue
		}
		if rule.cookie.domain != "" && !domainMatches(attrs["domain"], rule.cookie.domain) {
			continue
		}
		if rule.cookie.path != "" && attrs["path"] != rule.cookie.path {
			continue
		}
		return true
	}
	return false
}

func parseSetCookieHeader(value string) (string, map[string]string) {
	parts := strings.Split(value, ";")
	name, _, _ := strings.Cut(strings.TrimSpace(parts[0]), "=")
	attrs := make(map[string]string)
	for _, part := range parts[1:] {
		key, val, _ := strings.Cut(strings.TrimSpace(part), "=")
		attrs[strings.ToLower(key)] = strings.ToLower(val)
	}
	return name, attrs
}
