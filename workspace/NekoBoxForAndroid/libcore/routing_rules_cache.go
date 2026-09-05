package libcore

import (
	"bytes"
	"errors"
	"fmt"
	"log"
	"maps"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"

	"github.com/sagernet/bbolt"
	bbolterrors "github.com/sagernet/bbolt/errors"
	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

const (
	routingRulesCacheFileName    = "routing-rules-cache.db"
	routingRulesCompactFileName  = routingRulesCacheFileName + ".compact"
	routingRulesCompactTxMaxSize = 1 << 20
)

var (
	routingRulesCacheAccess sync.Mutex
	routingRulesVersionKey  = []byte("version")
	geoIPBucketName         = []byte("geoip")
	geositeBucketName       = []byte("geosite")
)

type routingRuleKind uint8

const (
	routingRuleGeoIP routingRuleKind = iota
	routingRuleGeosite
)

type routingRuleRequests map[routingRuleKind]map[string]struct{}

type routingRuleCacheLookup struct {
	version string
	rules   map[string][]option.HeadlessRule
	hit     bool
	prune   bool
}

type routingRuleCachePlan struct {
	version   string
	requested map[string]struct{}
	rules     map[string][]option.HeadlessRule
	encoded   map[string][]byte
	hit       bool
	prune     bool
}

// prepareRoutingRuleSets replaces referenced libcore pseudo rule-sets with
// inline SRS-decoded rules. The returned bool reports whether a source geo
// resource was opened, which lets the caller promptly release transient heap.
func prepareRoutingRuleSets(options *option.Options) (bool, error) {
	return prepareRoutingRuleSetsWithPaths(
		options,
		externalAssetsPath,
		filepath.Join(tempPath, routingRulesCacheFileName),
	)
}

func prepareRoutingRuleSetsWithPaths(options *option.Options, assetsPath string, cachePath string) (bool, error) {
	referencedTags := make(map[string]struct{})
	if options.Route != nil {
		collectRouteRuleSetReferences(options.Route.Rules, referencedTags)
	}
	if options.DNS != nil {
		collectDNSRuleSetReferences(options.DNS.Rules, referencedTags)
	}

	definitions := make(map[string]option.RuleSet)
	if options.Route != nil {
		definitions = make(map[string]option.RuleSet, len(options.Route.RuleSet))
		for _, definition := range options.Route.RuleSet {
			for _, tag := range definition.Tag {
				if _, exists := definitions[tag]; exists {
					return false, fmt.Errorf("duplicate rule-set tag: %s", tag)
				}
				definitions[tag] = definition
			}
		}
	}

	requests := routingRuleRequests{
		routingRuleGeoIP:   make(map[string]struct{}),
		routingRuleGeosite: make(map[string]struct{}),
	}
	for tag := range referencedTags {
		definition, loaded := definitions[tag]
		if !loaded {
			_, _, pseudo := parseRoutingRuleKey(tag)
			if !pseudo {
				continue
			}
			if options.Route == nil {
				options.Route = new(option.RouteOptions)
			}
			definition = option.RuleSet{
				Type:         C.RuleSetTypeLocal,
				Tag:          []string{tag},
				Format:       C.RuleSetFormatBinary,
				LocalOptions: option.LocalRuleSet{Path: tag},
			}
			options.Route.RuleSet = append(options.Route.RuleSet, definition)
			definitions[tag] = definition
		}
		if definition.Type != C.RuleSetTypeLocal {
			continue
		}
		path := strings.ReplaceAll(definition.LocalOptions.Path, C.RuleSetTagPlaceholder, tag)
		kind, key, loaded := parseRoutingRuleKey(path)
		if loaded {
			requests[kind][key] = struct{}{}
		}
	}
	if options.Route == nil {
		return false, nil
	}

	prepared, loadedFromResource, err := loadPreparedRoutingRules(requests, assetsPath, cachePath)
	if err != nil {
		return loadedFromResource, err
	}

	ruleSets := make([]option.RuleSet, 0, len(options.Route.RuleSet))
	for _, definition := range options.Route.RuleSet {
		if definition.Type != C.RuleSetTypeLocal {
			ruleSets = append(ruleSets, definition)
			continue
		}

		var retainedTags []string
		for _, tag := range definition.Tag {
			path := strings.ReplaceAll(definition.LocalOptions.Path, C.RuleSetTagPlaceholder, tag)
			kind, key, pseudo := parseRoutingRuleKey(path)
			if !pseudo {
				retainedTags = append(retainedTags, tag)
				continue
			}
			if _, referenced := referencedTags[tag]; !referenced {
				continue
			}
			rules, loaded := prepared[kind][key]
			if !loaded {
				return loadedFromResource, fmt.Errorf("routing rule %s was not prepared", key)
			}
			inlineDefinition := definition
			inlineDefinition.Type = C.RuleSetTypeInline
			inlineDefinition.Tag = []string{tag}
			inlineDefinition.Format = ""
			inlineDefinition.LocalOptions = option.LocalRuleSet{}
			inlineDefinition.InlineOptions = option.PlainRuleSet{Rules: rules}
			ruleSets = append(ruleSets, inlineDefinition)
		}
		if len(retainedTags) > 0 {
			definition.Tag = retainedTags
			ruleSets = append(ruleSets, definition)
		}
	}
	options.Route.RuleSet = ruleSets
	return loadedFromResource, nil
}

func collectRouteRuleSetReferences(rules []option.Rule, referenced map[string]struct{}) {
	for _, rule := range rules {
		switch rule.Type {
		case C.RuleTypeDefault:
			for _, tag := range rule.DefaultOptions.RuleSet {
				referenced[tag] = struct{}{}
			}
		case C.RuleTypeLogical:
			collectRouteRuleSetReferences(rule.LogicalOptions.Rules, referenced)
		}
	}
}

func collectDNSRuleSetReferences(rules []option.DNSRule, referenced map[string]struct{}) {
	for _, rule := range rules {
		switch rule.Type {
		case C.RuleTypeDefault:
			for _, tag := range rule.DefaultOptions.RuleSet {
				referenced[tag] = struct{}{}
			}
		case C.RuleTypeLogical:
			collectDNSRuleSetReferences(rule.LogicalOptions.Rules, referenced)
		}
	}
}

func parseRoutingRuleKey(path string) (routingRuleKind, string, bool) {
	if code, found := strings.CutPrefix(path, "geoip:"); found {
		code = canonicalGeoIPCode(code)
		return routingRuleGeoIP, "geoip:" + code, code != ""
	}
	if code, found := strings.CutPrefix(path, "geosite:"); found {
		code = canonicalGeositeCode(code)
		return routingRuleGeosite, "geosite:" + code, code != ""
	}
	return 0, "", false
}

func loadPreparedRoutingRules(
	requests routingRuleRequests,
	assetsPath string,
	cachePath string,
) (map[routingRuleKind]map[string][]option.HeadlessRule, bool, error) {
	routingRulesCacheAccess.Lock()
	defer routingRulesCacheAccess.Unlock()

	prepared := map[routingRuleKind]map[string][]option.HeadlessRule{
		routingRuleGeoIP:   make(map[string][]option.HeadlessRule),
		routingRuleGeosite: make(map[string][]option.HeadlessRule),
	}
	versions := map[routingRuleKind]string{
		routingRuleGeoIP:   readRoutingRuleVersion(assetsPath, geoipVersion),
		routingRuleGeosite: readRoutingRuleVersion(assetsPath, geositeVersion),
	}
	lookups := map[routingRuleKind]routingRuleCacheLookup{}
	cacheEnabled := isBgProcess
	if cacheEnabled {
		if err := os.MkdirAll(filepath.Dir(cachePath), 0o755); err != nil {
			return nil, false, err
		}
		_ = os.Remove(filepath.Join(tempPath, routingRulesCompactFileName))
		var err error
		lookups, err = readRoutingRulesCache(cachePath, versions, requests)
		if err != nil {
			if isCorruptRoutingRulesCacheError(err) {
				log.Println("routing rules cache is corrupted, rebuilding:", err)
				if removeErr := os.Remove(cachePath); removeErr != nil && !errors.Is(removeErr, os.ErrNotExist) {
					log.Println("remove corrupted routing rules cache:", removeErr)
				}
			} else {
				log.Println("read routing rules cache:", err)
			}
			lookups = map[routingRuleKind]routingRuleCacheLookup{}
		}
	}

	plans := make(map[routingRuleKind]routingRuleCachePlan, 2)
	loadedFromResource := false
	for _, kind := range []routingRuleKind{routingRuleGeoIP, routingRuleGeosite} {
		requested := requests[kind]
		lookup := lookups[kind]
		plan := routingRuleCachePlan{
			version:   versions[kind],
			requested: requested,
			rules:     make(map[string][]option.HeadlessRule, len(requested)),
			hit:       cacheEnabled && versions[kind] != "" && lookup.hit,
			prune:     lookup.prune,
		}
		if plan.hit {
			maps.Copy(plan.rules, lookup.rules)
			maps.Copy(prepared[kind], lookup.rules)
			plans[kind] = plan
			continue
		}

		if len(requested) == 0 {
			plans[kind] = plan
			continue
		}
		rules, err := loadRoutingRulesFromResource(kind, requested, assetsPath)
		if err != nil {
			return nil, loadedFromResource, err
		}
		loadedFromResource = true
		plan.encoded = make(map[string][]byte, len(rules))
		for key, sourceRules := range rules {
			encoded, compiledRules, err := encodeRoutingRules(sourceRules)
			if err != nil {
				return nil, loadedFromResource, fmt.Errorf("encode %s: %w", key, err)
			}
			plan.encoded[key] = encoded
			plan.rules[key] = compiledRules
			prepared[kind][key] = compiledRules
		}
		plans[kind] = plan
	}

	if cacheEnabled {
		if err := writeRoutingRulesCache(cachePath, plans); err != nil {
			log.Println("write routing rules cache:", err)
		}
	}
	return prepared, loadedFromResource, nil
}

func readRoutingRuleVersion(assetsPath string, name string) string {
	content, err := os.ReadFile(filepath.Join(assetsPath, name))
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(content))
}

func readRoutingRulesCache(path string, versions map[routingRuleKind]string, requests routingRuleRequests) (map[routingRuleKind]routingRuleCacheLookup, error) {
	database, err := bbolt.Open(path, 0o600, &bbolt.Options{ReadOnly: true})
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return map[routingRuleKind]routingRuleCacheLookup{}, nil
		}
		return nil, err
	}
	lookups := make(map[routingRuleKind]routingRuleCacheLookup, 2)
	err = database.View(func(transaction *bbolt.Tx) error {
		for _, kind := range []routingRuleKind{routingRuleGeoIP, routingRuleGeosite} {
			lookup := routingRuleCacheLookup{
				version: versions[kind],
				rules:   make(map[string][]option.HeadlessRule, len(requests[kind])),
			}
			bucket := transaction.Bucket(routingRuleBucketName(kind))
			if bucket == nil || lookup.version == "" || string(bucket.Get(routingRulesVersionKey)) != lookup.version {
				lookups[kind] = lookup
				continue
			}

			lookup.hit = true
			for key := range requests[kind] {
				value := bucket.Get([]byte(key))
				if value == nil {
					lookup.hit = false
					break
				}
				rules, err := decodeRoutingRules(value)
				if err != nil {
					lookup.hit = false
					break
				}
				lookup.rules[key] = rules
			}
			if !lookup.hit {
				clear(lookup.rules)
			}
			if err := bucket.ForEach(func(key, _ []byte) error {
				if bytes.Equal(key, routingRulesVersionKey) {
					return nil
				}
				if _, requested := requests[kind][string(key)]; !requested {
					lookup.prune = true
				}
				return nil
			}); err != nil {
				return err
			}
			lookups[kind] = lookup
		}
		return nil
	})
	return lookups, errors.Join(err, database.Close())
}

func loadRoutingRulesFromResource(
	kind routingRuleKind,
	requested map[string]struct{},
	assetsPath string,
) (map[string][]option.HeadlessRule, error) {
	keys := slices.Sorted(maps.Keys(requested))
	codes := make([]string, 0, len(keys))
	for _, key := range keys {
		_, code, _ := strings.Cut(key, ":")
		codes = append(codes, code)
	}

	var (
		loaded map[string][]option.HeadlessRule
		err    error
	)
	switch kind {
	case routingRuleGeoIP:
		loaded, err = loadGeoIPRules(filepath.Join(assetsPath, geoipDat), codes)
	case routingRuleGeosite:
		loaded, err = loadGeositeRules(filepath.Join(assetsPath, geositeDat), codes)
	default:
		return nil, fmt.Errorf("unknown routing rule kind: %d", kind)
	}
	if err != nil {
		return nil, err
	}

	rules := make(map[string][]option.HeadlessRule, len(loaded))
	for code, value := range loaded {
		rules[routingRulePrefix(kind)+code] = value
	}
	return rules, nil
}

func encodeRoutingRules(rules []option.HeadlessRule) ([]byte, []option.HeadlessRule, error) {
	var buffer bytes.Buffer
	err := srs.Write(&buffer, option.PlainRuleSet{Rules: rules}, C.RuleSetVersionCurrent)
	if err != nil {
		return nil, nil, err
	}
	encoded := bytes.Clone(buffer.Bytes())
	compiled, err := decodeRoutingRules(encoded)
	return encoded, compiled, err
}

func decodeRoutingRules(content []byte) ([]option.HeadlessRule, error) {
	compat, err := srs.Read(bytes.NewReader(content), false)
	if err != nil {
		return nil, err
	}
	plain, err := compat.Upgrade()
	if err != nil {
		return nil, err
	}
	return plain.Rules, nil
}

func writeRoutingRulesCache(path string, plans map[routingRuleKind]routingRuleCachePlan) error {
	existing := true
	if _, err := os.Stat(path); err != nil {
		existing = false
	}
	database, err := bbolt.Open(path, 0o600, nil)
	if err != nil {
		return err
	}
	changed := false
	shouldCompact := false
	err = database.Update(func(transaction *bbolt.Tx) error {
		for _, kind := range []routingRuleKind{routingRuleGeoIP, routingRuleGeosite} {
			plan := plans[kind]
			if plan.version == "" {
				continue
			}
			if !plan.hit {
				if transaction.Bucket(routingRuleBucketName(kind)) != nil {
					if err := transaction.DeleteBucket(routingRuleBucketName(kind)); err != nil {
						return err
					}
					shouldCompact = true
				}
				bucket, err := transaction.CreateBucket(routingRuleBucketName(kind))
				if err != nil {
					return err
				}
				if err := bucket.Put(routingRulesVersionKey, []byte(plan.version)); err != nil {
					return err
				}
				for _, key := range slices.Sorted(maps.Keys(plan.encoded)) {
					if err := bucket.Put([]byte(key), plan.encoded[key]); err != nil {
						return err
					}
				}
				changed = true
				continue
			}
			if !plan.prune {
				continue
			}
			bucket := transaction.Bucket(routingRuleBucketName(kind))
			if bucket == nil {
				continue
			}
			var unused [][]byte
			if err := bucket.ForEach(func(key, _ []byte) error {
				if bytes.Equal(key, routingRulesVersionKey) {
					return nil
				}
				if _, requested := plan.requested[string(key)]; !requested {
					unused = append(unused, bytes.Clone(key))
				}
				return nil
			}); err != nil {
				return err
			}
			for _, key := range unused {
				if err := bucket.Delete(key); err != nil {
					return err
				}
			}
			if len(unused) > 0 {
				changed = true
				shouldCompact = true
			}
		}
		return nil
	})
	closeErr := database.Close()
	if err != nil {
		return errors.Join(err, closeErr)
	}
	if closeErr != nil {
		return closeErr
	}
	if changed && shouldCompact && existing {
		return compactRoutingRulesCache(path)
	}
	return nil
}

func compactRoutingRulesCache(path string) error {
	temporaryPath := filepath.Join(filepath.Dir(path), routingRulesCompactFileName)
	_ = os.Remove(temporaryPath)
	source, err := bbolt.Open(path, 0o600, &bbolt.Options{ReadOnly: true})
	if err != nil {
		return err
	}
	destination, err := bbolt.Open(temporaryPath, 0o600, nil)
	if err != nil {
		return errors.Join(err, source.Close())
	}
	err = bbolt.Compact(destination, source, routingRulesCompactTxMaxSize)
	err = errors.Join(err, destination.Close(), source.Close())
	if err != nil {
		_ = os.Remove(temporaryPath)
		return err
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		_ = os.Remove(temporaryPath)
		return err
	}
	directory, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	return errors.Join(directory.Sync(), directory.Close())
}

func routingRuleBucketName(kind routingRuleKind) []byte {
	if kind == routingRuleGeoIP {
		return geoIPBucketName
	}
	return geositeBucketName
}

func routingRulePrefix(kind routingRuleKind) string {
	if kind == routingRuleGeoIP {
		return "geoip:"
	}
	return "geosite:"
}

func isCorruptRoutingRulesCacheError(err error) bool {
	return errors.Is(err, bbolterrors.ErrInvalid) ||
		errors.Is(err, bbolterrors.ErrInvalidMapping) ||
		errors.Is(err, bbolterrors.ErrVersionMismatch) ||
		errors.Is(err, bbolterrors.ErrChecksum)
}
