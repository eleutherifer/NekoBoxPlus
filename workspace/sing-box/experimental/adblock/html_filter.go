//go:build with_adblock

package adblock

import (
	"bytes"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/andybalholm/cascadia"
	"github.com/antchfx/htmlquery"
	"github.com/antchfx/xpath"
	"golang.org/x/net/html"
)

type htmlFilterRules struct {
	rules    []htmlFilterRule
	headers  []htmlFilterRule
	snippets []browserSnippetRule
}

func (r *htmlFilterRules) add(rule htmlFilterRule) {
	if rule.header != "" {
		r.headers = append(r.headers, rule)
		return
	}
	r.rules = append(r.rules, rule)
}

func (r *htmlFilterRules) merge(other htmlFilterRules) {
	r.rules = append(r.rules, other.rules...)
	r.headers = append(r.headers, other.headers...)
	r.snippets = append(r.snippets, other.snippets...)
}

func (r htmlFilterRules) empty() bool {
	return len(r.rules) == 0 && len(r.headers) == 0 && len(r.snippets) == 0
}

func (r *htmlFilterRules) addBrowserSnippet(rule browserSnippetRule) {
	r.snippets = append(r.snippets, rule)
}

type htmlFilterRule struct {
	exception  bool
	domains    []string
	notDomains []string
	selector   htmlSelector
	header     string
	raw        string
}

type htmlSelector struct {
	raw        string
	plain      cascadia.Selector
	procedural *htmlProceduralSelector
}

type htmlProceduralSelector struct {
	raw      string
	selector string
	plain    cascadia.Selector
	tasks    []htmlProceduralTask
}

type htmlProceduralTask interface {
	transpose(*html.Node) []*html.Node
}

func parseHTMLFilterRule(raw string) (htmlFilterRule, bool) {
	marker := "##^"
	exception := false
	index := strings.Index(raw, marker)
	if index < 0 {
		marker = "#@#^"
		index = strings.Index(raw, marker)
		exception = true
	}
	if index < 0 {
		return htmlFilterRule{}, false
	}
	prefix := raw[:index]
	selectorText := strings.TrimSpace(raw[index+len(marker):])
	if selectorText == "" {
		return htmlFilterRule{}, false
	}
	rule := htmlFilterRule{
		exception: exception,
		raw:       raw,
	}
	if strings.HasPrefix(selectorText, "responseheader(") {
		header, ok := parseResponseHeaderSelector(selectorText)
		if !ok {
			return htmlFilterRule{}, false
		}
		rule.header = header
	} else {
		selector, ok := parseHTMLSelector(selectorText)
		if !ok {
			return htmlFilterRule{}, false
		}
		rule.selector = selector
	}
	for _, domain := range strings.Split(prefix, ",") {
		domain = strings.TrimSpace(domain)
		if domain == "" || domain == "*" {
			continue
		}
		if cut, ok := strings.CutPrefix(domain, "~"); ok {
			rule.notDomains = append(rule.notDomains, strings.ToLower(cut))
		} else {
			rule.domains = append(rule.domains, strings.ToLower(domain))
		}
	}
	if !exception && len(rule.domains) == 0 {
		return htmlFilterRule{}, false
	}
	return rule, true
}

func parseResponseHeaderSelector(raw string) (string, bool) {
	if !strings.HasPrefix(raw, "responseheader(") || !strings.HasSuffix(raw, ")") {
		return "", false
	}
	header := strings.TrimSpace(raw[len("responseheader(") : len(raw)-1])
	if header != strings.ToLower(header) {
		return "", false
	}
	switch header {
	case "location", "refresh", "report-to", "set-cookie":
		return header, true
	default:
		return "", false
	}
}

func isHTMLFilterRule(raw string) bool {
	return strings.Contains(raw, "##^") || strings.Contains(raw, "#@#^")
}

func parseHTMLSelector(raw string) (htmlSelector, bool) {
	selector := htmlSelector{raw: raw}
	procedural, proceduralOK, proceduralUsed := parseHTMLProceduralSelector(raw, true)
	if proceduralUsed {
		if !proceduralOK {
			return htmlSelector{}, false
		}
		selector.procedural = &procedural
		return selector, true
	}
	plain, err := cascadia.Compile(raw)
	if err != nil {
		return htmlSelector{}, false
	}
	selector.plain = plain
	return selector, true
}

func parseHTMLProceduralSelector(raw string, allowTextComma bool) (htmlProceduralSelector, bool, bool) {
	var selectorBuilder strings.Builder
	var tasks []htmlProceduralTask
	used := false
	for index := 0; index < len(raw); {
		if raw[index] != ':' {
			selectorBuilder.WriteByte(raw[index])
			index++
			continue
		}
		name, _, argStart, argEnd, operatorEnd, ok := readHTMLProceduralOperator(raw, index, allowTextComma)
		if !ok {
			selectorBuilder.WriteByte(raw[index])
			index++
			continue
		}
		used = true
		arg := raw[argStart:argEnd]
		task, ok := compileHTMLProceduralTask(name, arg)
		if !ok {
			return htmlProceduralSelector{}, false, true
		}
		tasks = append(tasks, task)
		index = operatorEnd + 1
	}
	if !used {
		return htmlProceduralSelector{}, false, false
	}
	base := strings.TrimSpace(selectorBuilder.String())
	var plain cascadia.Selector
	if base != "" {
		if !startsWithRelativeCombinator(base) {
			compiled, err := cascadia.Compile(base)
			if err != nil {
				return htmlProceduralSelector{}, false, true
			}
			plain = compiled
		}
	}
	return htmlProceduralSelector{
		raw:      raw,
		selector: base,
		plain:    plain,
		tasks:    tasks,
	}, true, true
}

func readHTMLProceduralOperator(raw string, index int, allowTextComma bool) (name string, nameStart int, argStart int, argEnd int, operatorEnd int, ok bool) {
	if index >= len(raw) || raw[index] != ':' {
		return "", 0, 0, 0, 0, false
	}
	nameStart = index + 1
	if strings.HasPrefix(raw[nameStart:], "-abp-contains(") {
		name = "has-text"
		argStart = nameStart + len("-abp-contains(")
	} else if strings.HasPrefix(raw[nameStart:], "contains(") {
		name = "has-text"
		argStart = nameStart + len("contains(")
	} else if strings.HasPrefix(raw[nameStart:], "-abp-has(") {
		name = "has"
		argStart = nameStart + len("-abp-has(")
	} else if strings.HasPrefix(raw[nameStart:], "nth-ancestor(") {
		name = "upward"
		argStart = nameStart + len("nth-ancestor(")
	} else {
		for _, candidate := range []string{"has-text", "min-text-length", "if-not", "upward", "xpath", "has", "not", "if"} {
			prefix := candidate + "("
			if strings.HasPrefix(raw[nameStart:], prefix) {
				name = candidate
				argStart = nameStart + len(prefix)
				break
			}
		}
	}
	if name == "" {
		return "", 0, 0, 0, 0, false
	}
	argEnd, operatorEnd = matchingHTMLParen(raw, argStart-1, name == "has-text" && allowTextComma)
	if argEnd < 0 {
		return "", 0, 0, 0, 0, true
	}
	return name, nameStart, argStart, argEnd, operatorEnd, true
}

func matchingHTMLParen(raw string, open int, allowComma bool) (int, int) {
	depth := 0
	quote := byte(0)
	escaped := false
	inRegex := false
	comma := -1
	for index := open; index < len(raw); index++ {
		ch := raw[index]
		if escaped {
			escaped = false
			continue
		}
		if ch == '\\' {
			escaped = true
			continue
		}
		if quote != 0 {
			if ch == quote {
				quote = 0
			}
			continue
		}
		if inRegex {
			if ch == '/' {
				inRegex = false
			}
			continue
		}
		switch ch {
		case '\'', '"':
			quote = ch
		case '/':
			if index > open && raw[index-1] == '(' {
				inRegex = true
			}
		case '(':
			depth++
		case ')':
			depth--
			if depth == 0 {
				if comma >= 0 {
					return comma, index
				}
				return index, index
			}
		case ',':
			if allowComma && depth == 1 && comma < 0 {
				comma = index
			}
		}
	}
	return -1, -1
}

func compileHTMLProceduralTask(name string, arg string) (htmlProceduralTask, bool) {
	arg = strings.TrimSpace(arg)
	switch name {
	case "has", "if":
		selector, ok := compileHTMLProceduralSubselector(arg)
		return htmlProceduralIfTask{selector: selector, target: true}, ok
	case "not", "if-not":
		selector, ok := compileHTMLProceduralSubselector(arg)
		return htmlProceduralIfTask{selector: selector, target: false}, ok
	case "has-text":
		needle, ok := parseHTMLTextMatcher(arg)
		return htmlProceduralTextTask{needle: needle}, ok
	case "min-text-length":
		minLength, err := strconv.Atoi(arg)
		if err != nil || minLength < 0 {
			return nil, false
		}
		return htmlProceduralMinTextLengthTask{min: minLength}, true
	case "upward":
		if count, err := strconv.Atoi(arg); err == nil {
			return htmlProceduralUpwardTask{count: count}, count > 0
		}
		plain, err := cascadia.Compile(arg)
		if err != nil {
			return nil, false
		}
		return htmlProceduralUpwardTask{selector: plain}, true
	case "xpath":
		expr, err := xpath.Compile(arg)
		if err != nil {
			return nil, false
		}
		return htmlProceduralXPathTask{expr: expr}, true
	default:
		return nil, false
	}
}

func compileHTMLProceduralSubselector(raw string) (htmlProceduralSelector, bool) {
	selector, ok := compileHTMLProceduralSelector(raw)
	return selector, ok
}

func compileHTMLProceduralSelector(raw string) (htmlProceduralSelector, bool) {
	if selector, ok, used := parseHTMLProceduralSelector(raw, false); used {
		return selector, ok
	}
	var plain cascadia.Selector
	if !startsWithRelativeCombinator(raw) {
		compiled, err := cascadia.Compile(raw)
		if err != nil {
			return htmlProceduralSelector{}, false
		}
		plain = compiled
	}
	return htmlProceduralSelector{raw: raw, selector: raw, plain: plain}, true
}

func parseHTMLTextMatcher(raw string) (*regexp.Regexp, bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, false
	}
	if strings.HasPrefix(raw, "/") {
		pattern, flags, ok := cutHTMLRegexLiteral(raw)
		if ok {
			if strings.Contains(flags, "i") {
				pattern = "(?i:" + pattern + ")"
			}
			re, err := regexp.Compile(pattern)
			return re, err == nil
		}
	}
	re, err := regexp.Compile("(?i:" + regexp.QuoteMeta(strings.Trim(raw, `"'`)) + ")")
	return re, err == nil
}

func cutHTMLRegexLiteral(raw string) (string, string, bool) {
	escaped := false
	for index := 1; index < len(raw); index++ {
		ch := raw[index]
		if escaped {
			escaped = false
			continue
		}
		if ch == '\\' {
			escaped = true
			continue
		}
		if ch == '/' {
			return raw[1:index], raw[index+1:], true
		}
	}
	return "", "", false
}

func queryHTMLSelector(root *html.Node, selector cascadia.Selector, raw string) []*html.Node {
	raw = strings.TrimSpace(raw)
	if root.Type == html.ElementNode && startsWithRelativeCombinator(raw) {
		return queryHTMLRelativeSelector(root, raw)
	}
	if selector == nil {
		if root.Type == html.ElementNode {
			return []*html.Node{root}
		}
		var result []*html.Node
		walkHTML(root, func(node *html.Node) {
			if node.Type == html.ElementNode {
				result = append(result, node)
			}
		})
		return result
	}
	return cascadia.QueryAll(root, selector)
}

func queryHTMLRelativeSelector(root *html.Node, raw string) []*html.Node {
	parent := root.Parent
	if parent == nil {
		return nil
	}
	position := htmlElementIndex(root)
	if position == 0 {
		return nil
	}
	selectorText := ":nth-child(" + strconv.Itoa(position) + ")" + raw
	selector, err := cascadia.Compile(selectorText)
	if err != nil {
		return nil
	}
	return cascadia.QueryAll(parent, selector)
}

func startsWithRelativeCombinator(raw string) bool {
	raw = strings.TrimLeftFunc(raw, unicode.IsSpace)
	if raw == "" {
		return false
	}
	ch, _ := utf8.DecodeRuneInString(raw)
	return ch == '>' || ch == '+' || ch == '~'
}

func htmlElementIndex(node *html.Node) int {
	index := 1
	for sibling := node.PrevSibling; sibling != nil; sibling = sibling.PrevSibling {
		if sibling.Type == html.ElementNode {
			index++
		}
	}
	return index
}

type htmlProceduralIfTask struct {
	selector htmlProceduralSelector
	target   bool
}

func (t htmlProceduralIfTask) transpose(node *html.Node) []*html.Node {
	if t.selector.test(node) == t.target {
		return []*html.Node{node}
	}
	return nil
}

type htmlProceduralTextTask struct {
	needle *regexp.Regexp
}

func (t htmlProceduralTextTask) transpose(node *html.Node) []*html.Node {
	if t.needle.MatchString(htmlNodeText(node)) {
		return []*html.Node{node}
	}
	return nil
}

type htmlProceduralMinTextLengthTask struct {
	min int
}

func (t htmlProceduralMinTextLengthTask) transpose(node *html.Node) []*html.Node {
	if len(htmlNodeText(node)) >= t.min {
		return []*html.Node{node}
	}
	return nil
}

type htmlProceduralUpwardTask struct {
	count    int
	selector cascadia.Selector
}

func (t htmlProceduralUpwardTask) transpose(node *html.Node) []*html.Node {
	if t.selector != nil {
		for parent := htmlElementParent(node); parent != nil; parent = htmlElementParent(parent) {
			if t.selector(parent) {
				return []*html.Node{parent}
			}
		}
		return nil
	}
	for range t.count {
		node = htmlElementParent(node)
		if node == nil {
			return nil
		}
	}
	return []*html.Node{node}
}

type htmlProceduralXPathTask struct {
	expr *xpath.Expr
}

func (t htmlProceduralXPathTask) transpose(node *html.Node) []*html.Node {
	nodes := htmlquery.QuerySelectorAll(node, t.expr)
	return slices.DeleteFunc(nodes, func(node *html.Node) bool {
		return node.Type != html.ElementNode
	})
}

func htmlElementParent(node *html.Node) *html.Node {
	for parent := node.Parent; parent != nil; parent = parent.Parent {
		if parent.Type == html.ElementNode {
			return parent
		}
	}
	return nil
}

func (s htmlProceduralSelector) exec(root *html.Node) []*html.Node {
	nodes := queryHTMLSelector(root, s.plain, s.selector)
	for _, task := range s.tasks {
		if len(nodes) == 0 {
			return nil
		}
		var transposed []*html.Node
		for _, node := range nodes {
			transposed = append(transposed, task.transpose(node)...)
		}
		nodes = transposed
	}
	return nodes
}

func (s htmlProceduralSelector) test(root *html.Node) bool {
	nodes := queryHTMLSelector(root, s.plain, s.selector)
	for _, node := range nodes {
		output := []*html.Node{node}
		for _, task := range s.tasks {
			if len(output) == 0 {
				break
			}
			var transposed []*html.Node
			for _, candidate := range output {
				transposed = append(transposed, task.transpose(candidate)...)
			}
			output = transposed
		}
		if len(output) > 0 {
			return true
		}
	}
	return false
}

func splitHTMLSelector(raw string) []string {
	var tokens []string
	var builder strings.Builder
	bracketDepth := 0
	parenDepth := 0
	quote := rune(0)
	escaped := false
	for _, ch := range raw {
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
		if quote != 0 {
			builder.WriteRune(ch)
			if ch == quote {
				quote = 0
			}
			continue
		}
		switch ch {
		case '\'', '"':
			quote = ch
			builder.WriteRune(ch)
		case '[':
			bracketDepth++
			builder.WriteRune(ch)
		case ']':
			bracketDepth--
			builder.WriteRune(ch)
		case '(':
			parenDepth++
			builder.WriteRune(ch)
		case ')':
			parenDepth--
			builder.WriteRune(ch)
		case '>':
			if bracketDepth == 0 && parenDepth == 0 {
				if token := strings.TrimSpace(builder.String()); token != "" {
					tokens = append(tokens, token)
					builder.Reset()
				}
				tokens = append(tokens, ">")
			} else {
				builder.WriteRune(ch)
			}
		case ' ', '\t', '\n', '\r':
			if bracketDepth == 0 && parenDepth == 0 {
				if token := strings.TrimSpace(builder.String()); token != "" {
					tokens = append(tokens, token)
					builder.Reset()
				}
			} else {
				builder.WriteRune(ch)
			}
		default:
			builder.WriteRune(ch)
		}
	}
	if token := strings.TrimSpace(builder.String()); token != "" {
		tokens = append(tokens, token)
	}
	return tokens
}

func (r htmlFilterRules) apply(s *Service, requestContext *adblockRequestContext, response *http.Response) (bool, error) {
	if len(r.rules) == 0 || response.Body == nil {
		return false, nil
	}
	req := companionRequestFromContext(requestContext)
	selectors := r.selectors(req)
	if len(selectors) == 0 {
		return false, nil
	}
	limit := s.options.Filtering.ReplaceMaxBodyValue()
	body, ok, err := s.readBoundedRewritableBody(requestContext.request.Method, response, limit, true)
	if err != nil {
		return false, err
	}
	if !ok {
		return false, nil
	}
	rewritten, changed, err := applyHTMLSelectors(body.content, selectors)
	if err != nil {
		body.restore(response)
		return false, err
	}
	if !changed {
		body.restore(response)
		return false, nil
	}
	body.replace(response, rewritten)
	return true, nil
}

func (r htmlFilterRules) applyHeaders(requestContext *adblockRequestContext, response *http.Response) {
	if len(r.headers) == 0 || (requestContext.requestType != "document" && requestContext.requestType != "subdocument") {
		return
	}
	req := companionRequestFromContext(requestContext)
	for _, rule := range r.headers {
		if rule.exception || !rule.matches(req) || r.hasHeaderException(rule.header, req) {
			continue
		}
		response.Header.Del(rule.header)
	}
}

func (r htmlFilterRules) selectors(req companionRequest) []htmlSelector {
	var selectors []htmlSelector
	for _, rule := range r.rules {
		if rule.exception || !rule.matches(req) || r.hasException(rule.selector.raw, req) {
			continue
		}
		selectors = append(selectors, rule.selector)
	}
	return selectors
}

func (r htmlFilterRules) hasException(selector string, req companionRequest) bool {
	return slices.ContainsFunc(r.rules, func(rule htmlFilterRule) bool {
		return rule.exception && rule.selector.raw == selector && rule.matches(req)
	})
}

func (r htmlFilterRules) hasHeaderException(header string, req companionRequest) bool {
	return slices.ContainsFunc(r.headers, func(rule htmlFilterRule) bool {
		return rule.exception && rule.header == header && rule.matches(req)
	})
}

func (r htmlFilterRule) matches(req companionRequest) bool {
	requestParsed, err := url.Parse(req.requestURL)
	if err != nil {
		return false
	}
	host := strings.ToLower(requestParsed.Hostname())
	for _, domain := range r.notDomains {
		if domainMatches(host, domain) {
			return false
		}
	}
	if len(r.domains) == 0 {
		return r.exception
	}
	return slices.ContainsFunc(r.domains, func(domain string) bool {
		return domainMatches(host, domain)
	})
}

func applyHTMLSelectors(content []byte, selectors []htmlSelector) ([]byte, bool, error) {
	doc, err := html.Parse(bytes.NewReader(content))
	if err != nil {
		return nil, false, err
	}
	targets := make(map[*html.Node]bool)
	for _, selector := range selectors {
		for _, target := range selector.exec(doc) {
			if target.Type == html.ElementNode {
				targets[target] = true
			}
		}
	}
	if len(targets) == 0 {
		return content, false, nil
	}
	for target := range targets {
		if target.Parent != nil {
			target.Parent.RemoveChild(target)
		}
	}
	var output bytes.Buffer
	if err = html.Render(&output, doc); err != nil {
		return nil, false, err
	}
	return output.Bytes(), true, nil
}

func walkHTML(node *html.Node, visit func(*html.Node)) {
	visit(node)
	for child := node.FirstChild; child != nil; {
		next := child.NextSibling
		walkHTML(child, visit)
		child = next
	}
}

func (s htmlSelector) exec(root *html.Node) []*html.Node {
	if s.procedural != nil {
		return s.procedural.exec(root)
	}
	return cascadia.QueryAll(root, s.plain)
}

func htmlNodeText(node *html.Node) string {
	var builder strings.Builder
	walkHTML(node, func(child *html.Node) {
		if child.Type == html.TextNode {
			builder.WriteString(child.Data)
		}
	})
	return builder.String()
}
