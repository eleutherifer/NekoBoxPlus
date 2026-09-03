//go:build with_adblock

package adblock

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"html"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/goccy/go-json"

	"github.com/sagernet/sing-box/common/adblock/adblockrust"
)

type browserFilterState struct {
	cosmetic adblockrust.CosmeticResources
	nonce    string
}

type browserFilterPayload struct {
	injection    []byte
	styleSource  string
	scriptSource string
}

func (s *Service) loadBrowserFilterState(engine adblockrust.Engine, requestURL string) (browserFilterState, error) {
	cosmetic, err := engine.URLCosmeticResources(requestURL)
	if err != nil {
		return browserFilterState{}, err
	}
	cosmetic.ProceduralActions = append(cosmetic.ProceduralActions, s.readyHTMLFilters().browserProceduralActions(requestURL)...)
	return browserFilterState{
		cosmetic: cosmetic,
	}, nil
}

func (s *Service) injectBrowserFilters(engine adblockrust.Engine, requestURL string, content []byte) ([]byte, bool, string, string, error) {
	state, err := s.loadBrowserFilterState(engine, requestURL)
	if err != nil {
		return nil, false, "", "", err
	}
	return s.injectBrowserFiltersWithState(engine, state, content)
}

func (s *Service) injectBrowserFiltersWithState(engine adblockrust.Engine, state browserFilterState, content []byte) ([]byte, bool, string, string, error) {
	var lower []byte
	if !state.cosmetic.GenericHide {
		// Non-generic cosmetic filtering needs case-insensitive attribute
		// discovery before querying generic selectors. Generic-hide responses
		// can skip this full-document copy entirely.
		lower = bytes.ToLower(content)
	}
	if err := state.addDocumentSelectors(engine, content, lower); err != nil {
		return nil, false, "", "", err
	}
	dynamicScript, err := s.dynamicCosmeticScript(state)
	if err != nil {
		return nil, false, "", "", err
	}
	payload, changed, err := state.payload(dynamicScript)
	if err != nil {
		return nil, false, "", "", err
	}
	if !changed {
		return content, false, "", "", nil
	}
	rewritten := injectIntoHTML(content, payload.injection)
	if bytes.Equal(rewritten, content) {
		return content, false, "", "", nil
	}
	rewritten = patchMetaCSP(rewritten, payload.styleSource, payload.scriptSource)
	return rewritten, true, payload.styleSource, payload.scriptSource, nil
}

func (s *Service) streamBrowserFilters(engine adblockrust.Engine, requestURL string, body io.ReadCloser) (io.ReadCloser, string, string, bool, bool, error) {
	state, err := s.loadBrowserFilterState(engine, requestURL)
	if err != nil {
		return nil, "", "", false, false, err
	}
	return s.streamBrowserFiltersWithState(state, body)
}

func (s *Service) streamBrowserFiltersWithState(state browserFilterState, body io.ReadCloser) (io.ReadCloser, string, string, bool, bool, error) {
	if !state.cosmetic.GenericHide {
		return nil, "", "", false, false, nil
	}
	payload, changed, err := state.payload("")
	if err != nil {
		return nil, "", "", false, false, err
	}
	if !changed {
		return nil, "", "", false, true, nil
	}
	return newStreamingHTMLFilterReadCloser(body, payload.injection, payload.styleSource, payload.scriptSource), payload.styleSource, payload.scriptSource, true, true, nil
}

func (state *browserFilterState) addDocumentSelectors(engine adblockrust.Engine, content []byte, lower []byte) error {
	if !state.cosmetic.GenericHide {
		classNames, ids := documentClassIDs(content, lower)
		if len(classNames) == 0 && len(ids) == 0 {
			return nil
		}
		genericSelectors, err := engine.HiddenClassIDSelectors(classNames, ids, state.cosmetic.Exceptions)
		if err == nil {
			state.cosmetic.HideSelectors = append(state.cosmetic.HideSelectors, genericSelectors...)
		}
	}
	return nil
}

func (state browserFilterState) payload(dynamicScript string) (browserFilterPayload, bool, error) {
	styleContent := cosmeticStyle(state.cosmetic.HideSelectors)
	styleContent += proceduralStyle(state.cosmetic.ProceduralActions)
	scriptContent, err := cosmeticScript(state.cosmetic)
	if err != nil {
		return browserFilterPayload{}, false, err
	}
	if dynamicScript != "" {
		if styleContent == "" {
			styleContent = "/* dynamic cosmetic filters */\n"
		}
		if scriptContent == "" {
			scriptContent = singBoxAdblockRunner + "\n"
		}
		scriptContent += dynamicScript
	}
	if scriptContent != "" {
		styleContent += ".__" + runBlockID() + "-hide{display:none!important;}\n"
	}
	if styleContent == "" && scriptContent == "" {
		return browserFilterPayload{}, false, nil
	}
	nonce := state.nonce
	if nonce == "" {
		var err error
		nonce, err = randomCSPNonce()
		if err != nil {
			return browserFilterPayload{}, false, err
		}
	}
	styleSource := ""
	if styleContent != "" {
		styleSource = cspNonce(nonce)
	}
	scriptSource := ""
	if scriptContent != "" {
		scriptSource = cspNonce(nonce)
	}
	return browserFilterPayload{
		injection:    []byte(buildBrowserInjection(styleContent, scriptContent, nonce)),
		styleSource:  styleSource,
		scriptSource: scriptSource,
	}, true, nil
}

func (s *Service) dynamicCosmeticScript(state browserFilterState) (string, error) {
	if state.cosmetic.GenericHide {
		return "", nil
	}
	token, err := s.newCosmeticSession(state.cosmetic.Exceptions)
	if err != nil {
		return "", err
	}
	payload := struct {
		Endpoint string `json:"endpoint"`
		Token    string `json:"token"`
	}{
		Endpoint: cosmeticSelectorEndpoint,
		Token:    token,
	}
	payloadJSON, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	return "self.__" + runBlockHash() + "Dynamic(" + string(payloadJSON) + ");\n", nil
}

func cosmeticStyle(selectors []string) string {
	selectors = compactStrings(selectors)
	if len(selectors) == 0 {
		return ""
	}
	var builder strings.Builder
	size := 0
	for _, selector := range selectors {
		size += len(selector) + len("{display:none!important;}\n")
	}
	builder.Grow(size)
	for _, selector := range selectors {
		builder.WriteString(selector)
		builder.WriteString("{display:none!important;}\n")
	}
	return builder.String()
}

func proceduralStyle(actions []string) string {
	var builder strings.Builder
	builder.Grow(len(actions) * (len(".__") + len(runBlockID()) + len("-style-") + 4 + len("{}\n")))
	for index, action := range actions {
		var filter struct {
			Action *struct {
				Type string `json:"type"`
				Arg  string `json:"arg"`
			} `json:"action"`
		}
		if json.Unmarshal([]byte(action), &filter) != nil || filter.Action == nil || filter.Action.Type != "style" || filter.Action.Arg == "" {
			continue
		}
		builder.WriteString(".__" + runBlockID() + "-style-")
		builder.WriteString(strconv.Itoa(index))
		builder.WriteString("{")
		builder.WriteString(filter.Action.Arg)
		builder.WriteString("}\n")
	}
	return builder.String()
}

func cosmeticScript(cosmetic adblockrust.CosmeticResources) (string, error) {
	procedural := make([]json.RawMessage, 0, len(cosmetic.ProceduralActions))
	for _, action := range cosmetic.ProceduralActions {
		if strings.TrimSpace(action) == "" {
			continue
		}
		procedural = append(procedural, json.RawMessage(action))
	}
	proceduralJSON, err := json.Marshal(procedural)
	if err != nil {
		return "", err
	}
	payload := struct {
		Procedural json.RawMessage `json:"procedural"`
	}{
		Procedural: proceduralJSON,
	}
	payloadJSON, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	var builder strings.Builder
	builder.Grow(len(singBoxAdblockRunner) + len(cosmetic.InjectedScript) + len(payloadJSON) + 96)
	if cosmetic.InjectedScript != "" {
		secret, err := webAccessibleResourceSecret()
		if err != nil {
			return "", err
		}
		endpointJSON, err := json.Marshal(webAccessibleResourceEndpoint)
		if err != nil {
			return "", err
		}
		secretJSON, err := json.Marshal(secret)
		if err != nil {
			return "", err
		}
		builder.WriteString("(function(){\n")
		builder.WriteString("const scriptletGlobals={warOrigin:self.location.origin+")
		builder.Write(endpointJSON)
		builder.WriteString(",warSecret:")
		builder.Write(secretJSON)
		builder.WriteString(",logLevel:0,canDebug:false};\n")
		builder.WriteString(cosmetic.InjectedScript)
		builder.WriteString("\n})();\n")
	}
	if len(procedural) > 0 {
		builder.WriteString("self.__" + runBlockHash() + "Run(")
		builder.Write(payloadJSON)
		builder.WriteString(");\n")
	}
	if builder.Len() == 0 {
		return "", nil
	}
	return singBoxAdblockRunner + "\n" + builder.String(), nil
}

func buildBrowserInjection(styleContent string, scriptContent string, nonce string) string {
	var builder strings.Builder
	builder.Grow(len(styleContent) + len(scriptContent) + len(nonce)*2 + 96)
	if styleContent != "" {
		builder.WriteString(`<style data-` + runBlockID() + ` nonce="`)
		builder.WriteString(nonce)
		builder.WriteString(`">`)
		builder.WriteString(styleContent)
		builder.WriteString("</style>")
	}
	if scriptContent != "" {
		builder.WriteString(`<script data-` + runBlockID() + ` nonce="`)
		builder.WriteString(nonce)
		builder.WriteString(`">`)
		builder.WriteString(scriptContent)
		builder.WriteString("</script>")
	}
	return builder.String()
}

func injectIntoHTML(content []byte, injection []byte) []byte {
	index, waitForMore := streamingHTMLInjectionIndex(content)
	if !waitForMore && index >= 0 {
		output := make([]byte, 0, len(content)+len(injection))
		output = append(output, content[:index]...)
		output = append(output, injection...)
		output = append(output, content[index:]...)
		return output
	}
	output := make([]byte, 0, len(content)+len(injection))
	output = append(output, injection...)
	output = append(output, content...)
	return output
}

// documentClassIDs extracts the unique class names and element ids referenced
// in content. The attribute scan works over the already-lowercased buffer
// (provided by the caller, which lowercases once per document).
func documentClassIDs(content []byte, lower []byte) ([]string, []string) {
	classes := make(map[string]bool)
	ids := make(map[string]bool)
	for _, attr := range []string{"class", "id"} {
		offset := 0
		for {
			index := bytes.Index(lower[offset:], []byte(attr+"="))
			if index < 0 {
				break
			}
			index += offset + len(attr) + 1
			if index >= len(content) {
				break
			}
			quote := content[index]
			if quote != '"' && quote != '\'' {
				offset = index
				continue
			}
			valueStart := index + 1
			valueEnd := bytes.IndexByte(content[valueStart:], quote)
			if valueEnd < 0 {
				break
			}
			value := string(content[valueStart : valueStart+valueEnd])
			if attr == "class" {
				for _, class := range strings.Fields(value) {
					classes[class] = true
				}
			} else if value != "" {
				ids[value] = true
			}
			offset = valueStart + valueEnd + 1
		}
	}
	return setKeys(classes), setKeys(ids)
}

func setKeys(values map[string]bool) []string {
	result := make([]string, 0, len(values))
	for value := range values {
		result = append(result, value)
	}
	return result
}

func compactStrings(values []string) []string {
	seen := make(map[string]bool, len(values))
	result := make([]string, 0, len(values))
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" || seen[value] {
			continue
		}
		seen[value] = true
		result = append(result, value)
	}
	return result
}

func patchCSPHeaders(header http.Header, styleSource string, scriptSource string) {
	patchCSPHeader(header, "Content-Security-Policy", styleSource, scriptSource)
	patchCSPHeader(header, "Content-Security-Policy-Report-Only", styleSource, scriptSource)
}

func patchCSPHeader(header http.Header, name string, styleSource string, scriptSource string) {
	values := header.Values(name)
	if len(values) == 0 {
		return
	}
	for index, value := range values {
		values[index] = patchCSPPolicy(value, styleSource, scriptSource)
	}
	header.Del(name)
	for _, value := range values {
		header.Add(name, value)
	}
}

func patchMetaCSP(content []byte, styleSource string, scriptSource string) []byte {
	// Scan the document with ASCII case-insensitive matching so we never have
	// to materialize a lowercased copy of the whole body, and only allocate
	// around the CSP content value when a matching tag needs patching.
	searchFrom := 0
	copyFrom := 0

	var output []byte

	for searchFrom < len(content) {
		relIndex := indexASCIIFold(content[searchFrom:], streamingMetaToken)
		if relIndex < 0 {
			break
		}

		index := searchFrom + relIndex

		relEnd := bytes.IndexByte(content[index:], streamingGtByte)
		if relEnd < 0 {
			break
		}

		end := index + relEnd + 1

		tagBytes := content[index:end]

		if containsASCIIFold(tagBytes, streamingHTTPEquivToken) &&
			containsASCIIFold(tagBytes, streamingCSPToken) {

			patched, changed := patchMetaCSPTagBytes(tagBytes, styleSource, scriptSource)
			if changed {
				if output == nil {
					output = make([]byte, 0, len(content)+len(patched)-len(tagBytes))
				}

				output = append(output, content[copyFrom:index]...)
				output = append(output, patched...)
				copyFrom = end
			}
		}

		searchFrom = end
	}

	if output == nil {
		return content
	}

	output = append(output, content[copyFrom:]...)
	return output
}

func patchMetaCSPTagBytes(tag []byte, styleSource string, scriptSource string) ([]byte, bool) {
	index := indexASCIIFold(tag, streamingContentToken)
	if index < 0 {
		return nil, false
	}
	valueStart := index + len(streamingContentToken)
	if valueStart >= len(tag) {
		return nil, false
	}
	quote := tag[valueStart]
	if quote != '"' && quote != '\'' {
		return nil, false
	}
	valueEnd := bytes.IndexByte(tag[valueStart+1:], quote)
	if valueEnd < 0 {
		return nil, false
	}
	valueEnd += valueStart + 1
	value := html.UnescapeString(string(tag[valueStart+1 : valueEnd]))
	patched := html.EscapeString(patchCSPPolicy(value, styleSource, scriptSource))
	output := make([]byte, 0, len(tag)+len(patched)-len(tag[valueStart+1:valueEnd]))
	output = append(output, tag[:valueStart+1]...)
	output = append(output, patched...)
	output = append(output, tag[valueEnd:]...)
	return output, true
}

func writePatchedMetaCSPTag(writer io.Writer, tag []byte, styleSource string, scriptSource string) error {
	index := indexASCIIFold(tag, streamingContentToken)
	if index < 0 {
		_, err := writer.Write(tag)
		return err
	}
	valueStart := index + len(streamingContentToken)
	if valueStart >= len(tag) {
		_, err := writer.Write(tag)
		return err
	}
	quote := tag[valueStart]
	if quote != '"' && quote != '\'' {
		_, err := writer.Write(tag)
		return err
	}
	valueEnd := bytes.IndexByte(tag[valueStart+1:], quote)
	if valueEnd < 0 {
		_, err := writer.Write(tag)
		return err
	}
	valueEnd += valueStart + 1
	value := html.UnescapeString(string(tag[valueStart+1 : valueEnd]))
	patched := html.EscapeString(patchCSPPolicy(value, styleSource, scriptSource))
	if _, err := writer.Write(tag[:valueStart+1]); err != nil {
		return err
	}
	if _, err := io.WriteString(writer, patched); err != nil {
		return err
	}
	_, err := writer.Write(tag[valueEnd:])
	return err
}

func patchCSPPolicy(policy string, styleSource string, scriptSource string) string {
	directives := splitCSPDirectives(policy)
	changed := false
	if styleSource != "" {
		var directiveChanged bool
		directives, directiveChanged = patchCSPDirective(directives, "style-src-elem", "style-src", styleSource)
		changed = directiveChanged || changed
	}
	if scriptSource != "" {
		var directiveChanged bool
		directives, directiveChanged = patchCSPDirective(directives, "script-src-elem", "script-src", scriptSource)
		changed = directiveChanged || changed
	}
	if !changed {
		return policy
	}
	return joinCSPDirectives(directives)
}

func patchCSPDirective(directives [][]string, elemDirective string, baseDirective string, hash string) ([][]string, bool) {
	if patchDirectiveSource(directives, elemDirective, hash) {
		return directives, true
	}
	if patchDirectiveSource(directives, baseDirective, hash) {
		return directives, true
	}
	fallback := directiveSources(directives, "default-src")
	if len(fallback) == 0 {
		return directives, false
	}
	if sourceListAllowsInlineWithoutNonce(fallback) && isCSPNonceOrHashSource(hash) {
		return directives, false
	}
	directives = append(directives, append([]string{baseDirective}, append(fallback, hash)...))
	return directives, true
}

func splitCSPDirectives(policy string) [][]string {
	var directives [][]string
	for directive := range strings.SplitSeq(policy, ";") {
		fields := strings.Fields(directive)
		if len(fields) > 0 {
			directives = append(directives, fields)
		}
	}
	return directives
}

func patchDirectiveSource(directives [][]string, name string, source string) bool {
	matched := false
	for index, directive := range directives {
		if len(directive) == 0 || !strings.EqualFold(directive[0], name) {
			continue
		}
		matched = true
		hasSource := false
		for _, existing := range directive[1:] {
			if existing == source {
				hasSource = true
				break
			}
		}
		if hasSource {
			continue
		}
		if sourceListAllowsInlineWithoutNonce(directive[1:]) && isCSPNonceOrHashSource(source) {
			continue
		}
		directives[index] = append(directive, source)
	}
	return matched
}

func directiveSources(directives [][]string, name string) []string {
	for _, directive := range directives {
		if len(directive) > 0 && strings.EqualFold(directive[0], name) {
			return directive[1:]
		}
	}
	return nil
}

func sourceListAllowsInlineWithoutNonce(sources []string) bool {
	hasUnsafeInline := false
	for _, source := range sources {
		if source == "'unsafe-inline'" {
			hasUnsafeInline = true
		} else if isCSPNonceOrHashSource(source) {
			return false
		}
	}
	return hasUnsafeInline
}

func isCSPNonceOrHashSource(source string) bool {
	return strings.HasPrefix(source, "'nonce-") ||
		strings.HasPrefix(source, "'sha256-") ||
		strings.HasPrefix(source, "'sha384-") ||
		strings.HasPrefix(source, "'sha512-")
}

func joinCSPDirectives(directives [][]string) string {
	size := max(0, len(directives)-1) * len("; ")
	for _, directive := range directives {
		size += max(0, len(directive)-1)
		for _, field := range directive {
			size += len(field)
		}
	}
	var builder strings.Builder
	builder.Grow(size)
	for index, directive := range directives {
		if index > 0 {
			builder.WriteString("; ")
		}
		for fieldIndex, field := range directive {
			if fieldIndex > 0 {
				builder.WriteByte(' ')
			}
			builder.WriteString(field)
		}
	}
	return builder.String()
}

func cspHash(content string) string {
	sum := sha256.Sum256([]byte(content))
	return "'sha256-" + base64.StdEncoding.EncodeToString(sum[:]) + "'"
}

func randomCSPNonce() (string, error) {
	var nonce [18]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return "", err
	}
	return base64.RawStdEncoding.EncodeToString(nonce[:]), nil
}

func cspNonce(nonce string) string {
	return "'nonce-" + nonce + "'"
}
