//go:build with_adblock

package adblockrust

/*
#cgo android,arm LDFLAGS: -L${SRCDIR}/../bridge/target/armv7-linux-androideabi/release -lsing_box_adblock_bridge -ldl -lm
#cgo android,arm64 LDFLAGS: -L${SRCDIR}/../bridge/target/aarch64-linux-android/release -lsing_box_adblock_bridge -ldl -lm
#cgo android,386 LDFLAGS: -L${SRCDIR}/../bridge/target/i686-linux-android/release -lsing_box_adblock_bridge -ldl -lm
#cgo android,amd64 LDFLAGS: -L${SRCDIR}/../bridge/target/x86_64-linux-android/release -lsing_box_adblock_bridge -ldl -lm
#cgo !android LDFLAGS: -L${SRCDIR}/../bridge/target/release -lsing_box_adblock_bridge -ldl -lm
#include <stdint.h>
#include <stdlib.h>

typedef struct {
	uint8_t matched;
	char *error;
} sing_box_adblock_check_result;

typedef struct {
	uint8_t matched;
	uint8_t important;
	char *redirect;
	char *rewritten_url;
	char *exception;
	char *filter;
	char *error;
} sing_box_adblock_detailed_check_result;

typedef struct {
	char *value;
	char *error;
} sing_box_adblock_string_result;

typedef struct {
	char **values;
	uintptr_t len;
} sing_box_adblock_string_array;

typedef struct {
	sing_box_adblock_string_array array;
	char *error;
} sing_box_adblock_string_array_result;

typedef struct {
	sing_box_adblock_string_array hide_selectors;
	sing_box_adblock_string_array procedural_actions;
	sing_box_adblock_string_array exceptions;
	char *injected_script;
	uint8_t generichide;
	char *error;
} sing_box_adblock_cosmetic_resources_result;

typedef struct {
	const char **rules;
	uintptr_t rules_len;
	const char *format;
	uint8_t permissions;
} sing_box_adblock_rule_set;

typedef uint8_t sing_box_adblock_request_method;

uintptr_t sing_box_adblock_engine_new(const sing_box_adblock_rule_set *rule_sets, uintptr_t rule_sets_len, const char *adblock_resources, char **error);
sing_box_adblock_check_result sing_box_adblock_engine_check(uintptr_t engine, const char *url, const char *source_url, const char *request_type, sing_box_adblock_request_method method);
sing_box_adblock_detailed_check_result sing_box_adblock_engine_check_detailed(uintptr_t engine, const char *url, const char *source_url, const char *request_type, sing_box_adblock_request_method method);
sing_box_adblock_detailed_check_result sing_box_adblock_engine_check_detailed_no_filter(uintptr_t engine, const char *url, const char *source_url, const char *request_type, sing_box_adblock_request_method method);
sing_box_adblock_check_result sing_box_adblock_engine_check_exception(uintptr_t engine, const char *url, const char *source_url, const char *request_type, sing_box_adblock_request_method method);
sing_box_adblock_string_result sing_box_adblock_engine_csp_directives(uintptr_t engine, const char *url, const char *source_url, const char *request_type, sing_box_adblock_request_method method);
sing_box_adblock_cosmetic_resources_result sing_box_adblock_engine_url_cosmetic_resources(uintptr_t engine, const char *url);
sing_box_adblock_string_array_result sing_box_adblock_engine_hidden_class_id_selectors(uintptr_t engine, const char **classes, uintptr_t classes_len, const char **ids, uintptr_t ids_len, const char **exceptions, uintptr_t exceptions_len);
sing_box_adblock_check_result sing_box_adblock_engine_use_tags(uintptr_t engine, const char **tags, uintptr_t tags_len);
sing_box_adblock_check_result sing_box_adblock_engine_enable_tags(uintptr_t engine, const char **tags, uintptr_t tags_len);
sing_box_adblock_check_result sing_box_adblock_engine_disable_tags(uintptr_t engine, const char **tags, uintptr_t tags_len);
sing_box_adblock_check_result sing_box_adblock_engine_tag_exists(uintptr_t engine, const char *tag);
void sing_box_adblock_engine_free(uintptr_t engine);
void sing_box_adblock_string_free(char *value);
void sing_box_adblock_string_array_free(sing_box_adblock_string_array array);
void sing_box_adblock_cosmetic_resources_free(sing_box_adblock_cosmetic_resources_result result);
*/
import "C"

import (
	"sync"
	"unsafe"

	E "github.com/sagernet/sing/common/exceptions"
)

type cgoEngine struct {
	handle C.uintptr_t
}

// cStringInternCache holds process-lifetime *C.char copies of the small,
// fixed set of request-type strings ("document", "script", ...). They are
// reused across every engine call and never freed, which removes one
// malloc/free pair per Check/CheckDetailed/CheckException/CSPDirectives call
// on the request hot path.
var cStringInternCache sync.Map // map[string]*C.char

// internCString returns a shared, never-freed *C.char for s. Intended for the
// low-cardinality requestType argument; for high-cardinality strings (URLs)
// use cStringPair instead so memory does not grow unbounded.
func internCString(s string) *C.char {
	if v, ok := cStringInternCache.Load(s); ok {
		return v.(*C.char)
	}
	cs := C.CString(s)
	actual, loaded := cStringInternCache.LoadOrStore(s, cs)
	if loaded {
		// another goroutine won the race; drop our duplicate
		C.free(unsafe.Pointer(cs))
		return actual.(*C.char)
	}
	return cs
}

// cStringPair allocates a single C buffer holding two NUL-terminated strings
// (a\0b\0) and returns pointers to each plus the buffer to free. It collapses
// two C.CString calls into one C.malloc, and crucially allocates nothing on
// the Go heap (no closure), so it does not add GC pressure on the request hot
// path. The returned pointers are valid empty C strings ("\0") when the
// corresponding argument is empty, matching C.CString semantics, so the bridge
// never observes a NULL pointer. The caller must C.free(buf) exactly once.
func cStringPair(a, b string) (ca, cb *C.char, buf unsafe.Pointer) {
	size := len(a) + 1 + len(b) + 1
	buf = C.malloc(C.size_t(size))
	// C.malloc does not fail for these small sizes; reaching NULL would mean
	// the process is out of memory, where cgo would abort regardless.
	slice := unsafe.Slice((*byte)(buf), size)
	copy(slice[:len(a)], a)
	slice[len(a)] = 0
	off := len(a) + 1
	copy(slice[off:], b)
	slice[off+len(b)] = 0
	ca = (*C.char)(buf)
	cb = (*C.char)(unsafe.Add(buf, uintptr(off)))
	return ca, cb, buf
}

func newEngine(ruleSets []RuleSet, adblockResources string) (Engine, error) {
	cRules := make([]*C.char, 0)
	cFormats := make([]*C.char, 0, len(ruleSets))
	cRulePointers := make([]unsafe.Pointer, 0, len(ruleSets))
	cRuleSetCount := 0
	var cRuleSets unsafe.Pointer
	if len(ruleSets) > 0 {
		cRuleSets = C.malloc(C.size_t(len(ruleSets)) * C.size_t(unsafe.Sizeof(C.sing_box_adblock_rule_set{})))
	}
	for _, ruleSet := range ruleSets {
		rules := make([]*C.char, 0, len(ruleSet.Rules))
		for _, rule := range ruleSet.Rules {
			if rule == "" {
				continue
			}
			cRule := C.CString(rule)
			cRules = append(cRules, cRule)
			rules = append(rules, cRule)
		}
		if len(rules) == 0 {
			continue
		}
		cRulePointer := C.malloc(C.size_t(len(rules)) * C.size_t(unsafe.Sizeof((*C.char)(nil))))
		cRulePointers = append(cRulePointers, cRulePointer)
		cRulePointerSlice := unsafe.Slice((**C.char)(cRulePointer), len(rules))
		copy(cRulePointerSlice, rules)
		format := ruleSet.Format
		if format == "" {
			format = RuleFormatStandard
		}
		cFormat := C.CString(string(format))
		cFormats = append(cFormats, cFormat)
		cRuleSetSlice := unsafe.Slice((*C.sing_box_adblock_rule_set)(cRuleSets), len(ruleSets))
		cRuleSetSlice[cRuleSetCount] = C.sing_box_adblock_rule_set{
			rules:       (**C.char)(cRulePointer),
			rules_len:   C.uintptr_t(len(rules)),
			format:      cFormat,
			permissions: C.uint8_t(ruleSet.Permissions),
		}
		cRuleSetCount++
	}
	defer func() {
		if cRuleSets != nil {
			C.free(cRuleSets)
		}
		for _, rules := range cRulePointers {
			C.free(rules)
		}
		for _, rule := range cRules {
			C.free(unsafe.Pointer(rule))
		}
		for _, format := range cFormats {
			C.free(unsafe.Pointer(format))
		}
	}()
	var errText *C.char
	var ruleSetsPtr *C.sing_box_adblock_rule_set
	if cRuleSetCount > 0 {
		ruleSetsPtr = (*C.sing_box_adblock_rule_set)(cRuleSets)
	}
	cAdblockResources := C.CString(adblockResources)
	defer C.free(unsafe.Pointer(cAdblockResources))
	handle := C.sing_box_adblock_engine_new(ruleSetsPtr, C.uintptr_t(cRuleSetCount), cAdblockResources, &errText)
	if errText != nil {
		defer C.sing_box_adblock_string_free(errText)
		return nil, E.New(C.GoString(errText))
	}
	if handle == 0 {
		return nil, E.New("create adblock engine: null handle")
	}
	return &cgoEngine{handle: handle}, nil
}

func (e *cgoEngine) Check(url string, sourceURL string, requestType string, method RequestMethod) (bool, error) {
	cURL, cSourceURL, buf := cStringPair(url, sourceURL)
	defer C.free(buf)
	// requestType comes from a small fixed set; reuse the interned *C.char.
	cRequestType := internCString(requestType)
	result := C.sing_box_adblock_engine_check(e.handle, cURL, cSourceURL, cRequestType, C.sing_box_adblock_request_method(method))
	if result.error != nil {
		defer C.sing_box_adblock_string_free(result.error)
		return false, E.New(C.GoString(result.error))
	}
	return result.matched != 0, nil
}

func (e *cgoEngine) CheckDetailed(url string, sourceURL string, requestType string, method RequestMethod) (CheckResult, error) {
	cURL, cSourceURL, buf := cStringPair(url, sourceURL)
	defer C.free(buf)
	cRequestType := internCString(requestType)
	result := C.sing_box_adblock_engine_check_detailed(e.handle, cURL, cSourceURL, cRequestType, C.sing_box_adblock_request_method(method))
	return detailedCheckResult(result)
}

func (e *cgoEngine) CheckDetailedNoFilter(url string, sourceURL string, requestType string, method RequestMethod) (CheckResult, error) {
	cURL, cSourceURL, buf := cStringPair(url, sourceURL)
	defer C.free(buf)
	cRequestType := internCString(requestType)
	result := C.sing_box_adblock_engine_check_detailed_no_filter(e.handle, cURL, cSourceURL, cRequestType, C.sing_box_adblock_request_method(method))
	return detailedCheckResult(result)
}

func detailedCheckResult(result C.sing_box_adblock_detailed_check_result) (CheckResult, error) {
	defer freeDetailedCheckResult(result)
	if result.error != nil {
		return CheckResult{}, E.New(C.GoString(result.error))
	}
	return CheckResult{
		Matched:      result.matched != 0,
		Important:    result.important != 0,
		Redirect:     cStringValue(result.redirect),
		RewrittenURL: cStringValue(result.rewritten_url),
		Exception:    cStringValue(result.exception),
		Filter:       cStringValue(result.filter),
	}, nil
}

func (e *cgoEngine) CheckException(url string, sourceURL string, requestType string, method RequestMethod) (bool, error) {
	cURL, cSourceURL, buf := cStringPair(url, sourceURL)
	defer C.free(buf)
	cRequestType := internCString(requestType)
	result := C.sing_box_adblock_engine_check_exception(e.handle, cURL, cSourceURL, cRequestType, C.sing_box_adblock_request_method(method))
	if result.error != nil {
		defer C.sing_box_adblock_string_free(result.error)
		return false, E.New(C.GoString(result.error))
	}
	return result.matched != 0, nil
}

func (e *cgoEngine) CSPDirectives(url string, sourceURL string, requestType string, method RequestMethod) (string, error) {
	cURL, cSourceURL, buf := cStringPair(url, sourceURL)
	defer C.free(buf)
	cRequestType := internCString(requestType)
	return stringResult(C.sing_box_adblock_engine_csp_directives(e.handle, cURL, cSourceURL, cRequestType, C.sing_box_adblock_request_method(method)))
}

func (e *cgoEngine) URLCosmeticResources(url string) (CosmeticResources, error) {
	cURL := C.CString(url)
	defer C.free(unsafe.Pointer(cURL))
	result := C.sing_box_adblock_engine_url_cosmetic_resources(e.handle, cURL)
	defer C.sing_box_adblock_cosmetic_resources_free(result)
	if result.error != nil {
		return CosmeticResources{}, E.New(C.GoString(result.error))
	}
	return CosmeticResources{
		HideSelectors:     stringArrayValues(result.hide_selectors),
		ProceduralActions: stringArrayValues(result.procedural_actions),
		Exceptions:        stringArrayValues(result.exceptions),
		InjectedScript:    cStringValue(result.injected_script),
		GenericHide:       result.generichide != 0,
	}, nil
}

func (e *cgoEngine) HiddenClassIDSelectors(classes []string, ids []string, exceptions []string) ([]string, error) {
	cClasses, freeClasses := newCStringArray(classes)
	defer freeClasses()
	cIDs, freeIDs := newCStringArray(ids)
	defer freeIDs()
	cExceptions, freeExceptions := newCStringArray(exceptions)
	defer freeExceptions()
	result := C.sing_box_adblock_engine_hidden_class_id_selectors(
		e.handle,
		cClasses, C.uintptr_t(len(classes)),
		cIDs, C.uintptr_t(len(ids)),
		cExceptions, C.uintptr_t(len(exceptions)),
	)
	defer C.sing_box_adblock_string_array_free(result.array)
	if result.error != nil {
		defer C.sing_box_adblock_string_free(result.error)
		return nil, E.New(C.GoString(result.error))
	}
	return stringArrayValues(result.array), nil
}

func (e *cgoEngine) UseTags(tags []string) error {
	cTags, free := newCStringArray(tags)
	defer free()
	result := C.sing_box_adblock_engine_use_tags(e.handle, cTags, C.uintptr_t(len(tags)))
	return checkResultError(result)
}

func (e *cgoEngine) EnableTags(tags []string) error {
	cTags, free := newCStringArray(tags)
	defer free()
	result := C.sing_box_adblock_engine_enable_tags(e.handle, cTags, C.uintptr_t(len(tags)))
	return checkResultError(result)
}

func (e *cgoEngine) DisableTags(tags []string) error {
	cTags, free := newCStringArray(tags)
	defer free()
	result := C.sing_box_adblock_engine_disable_tags(e.handle, cTags, C.uintptr_t(len(tags)))
	return checkResultError(result)
}

func (e *cgoEngine) TagExists(tag string) (bool, error) {
	cTag := C.CString(tag)
	defer C.free(unsafe.Pointer(cTag))
	result := C.sing_box_adblock_engine_tag_exists(e.handle, cTag)
	if result.error != nil {
		defer C.sing_box_adblock_string_free(result.error)
		return false, E.New(C.GoString(result.error))
	}
	return result.matched != 0, nil
}

func checkResultError(result C.sing_box_adblock_check_result) error {
	if result.error != nil {
		defer C.sing_box_adblock_string_free(result.error)
		return E.New(C.GoString(result.error))
	}
	return nil
}

func stringResult(result C.sing_box_adblock_string_result) (string, error) {
	if result.error != nil {
		defer C.sing_box_adblock_string_free(result.error)
		if result.value != nil {
			defer C.sing_box_adblock_string_free(result.value)
		}
		return "", E.New(C.GoString(result.error))
	}
	if result.value == nil {
		return "", E.New("adblock bridge returned null string")
	}
	defer C.sing_box_adblock_string_free(result.value)
	return C.GoString(result.value), nil
}

func cStringValue(value *C.char) string {
	if value == nil {
		return ""
	}
	return C.GoString(value)
}

func stringArrayValues(array C.sing_box_adblock_string_array) []string {
	if array.values == nil || array.len == 0 {
		return nil
	}
	values := unsafe.Slice((**C.char)(unsafe.Pointer(array.values)), int(array.len))
	result := make([]string, 0, len(values))
	for _, value := range values {
		if value != nil {
			result = append(result, C.GoString(value))
		}
	}
	return result
}

func newCStringArray(values []string) (**C.char, func()) {
	if len(values) == 0 {
		return nil, func() {}
	}
	cValues := make([]*C.char, 0, len(values))
	for _, value := range values {
		cValues = append(cValues, C.CString(value))
	}
	cArray := C.malloc(C.size_t(len(cValues)) * C.size_t(unsafe.Sizeof((*C.char)(nil))))
	cArraySlice := unsafe.Slice((**C.char)(cArray), len(cValues))
	copy(cArraySlice, cValues)
	return (**C.char)(cArray), func() {
		C.free(cArray)
		for _, value := range cValues {
			C.free(unsafe.Pointer(value))
		}
	}
}

func freeDetailedCheckResult(result C.sing_box_adblock_detailed_check_result) {
	if result.redirect != nil {
		C.sing_box_adblock_string_free(result.redirect)
	}
	if result.rewritten_url != nil {
		C.sing_box_adblock_string_free(result.rewritten_url)
	}
	if result.exception != nil {
		C.sing_box_adblock_string_free(result.exception)
	}
	if result.filter != nil {
		C.sing_box_adblock_string_free(result.filter)
	}
	if result.error != nil {
		C.sing_box_adblock_string_free(result.error)
	}
}

func (e *cgoEngine) Close() error {
	if e.handle != 0 {
		C.sing_box_adblock_engine_free(e.handle)
		e.handle = 0
	}
	return nil
}
