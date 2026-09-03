package libcore

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/sagernet/bbolt"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

func TestPrepareRoutingRuleSetsColdWarmAndPrune(t *testing.T) {
	cacheDir, assetsDir := configureRoutingRulesTest(t)
	writeRoutingRulesTestAssets(t, assetsDir, "1", "1")

	options := routingRulesTestOptions(true)
	loaded, err := prepareRoutingRuleSets(&options)
	if err != nil {
		t.Fatal(err)
	}
	if !loaded {
		t.Fatal("expected cold load from resource")
	}
	assertPreparedRoutingRules(t, options, 3)
	assertRoutingRulesCacheKeys(t, cacheDir, routingRuleGeoIP, []string{"geoip:cn", "geoip:us"})
	assertRoutingRulesCacheKeys(t, cacheDir, routingRuleGeosite, []string{"geosite:test"})

	if err := os.Remove(filepath.Join(assetsDir, geoipDat)); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(assetsDir, geositeDat)); err != nil {
		t.Fatal(err)
	}

	warmOptions := routingRulesTestOptions(false)
	loaded, err = prepareRoutingRuleSets(&warmOptions)
	if err != nil {
		t.Fatal(err)
	}
	if loaded {
		t.Fatal("expected warm cache hit without opening resources")
	}
	assertPreparedRoutingRules(t, warmOptions, 2)
	assertRoutingRulesCacheKeys(t, cacheDir, routingRuleGeoIP, []string{"geoip:us"})
	if _, err := os.Stat(filepath.Join(cacheDir, routingRulesCompactFileName)); !os.IsNotExist(err) {
		t.Fatalf("compact temporary file was not removed: %v", err)
	}
}

func TestPrepareRoutingRuleSetsInvalidatesResourcesIndependently(t *testing.T) {
	cacheDir, assetsDir := configureRoutingRulesTest(t)
	writeRoutingRulesTestAssets(t, assetsDir, "1", "1")

	options := routingRulesTestOptions(false)
	if _, err := prepareRoutingRuleSets(&options); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(assetsDir, geositeDat)); err != nil {
		t.Fatal(err)
	}
	writeGeoIPDatFile(t, filepath.Join(assetsDir, geoipDat), &v2geoIPList{
		Entry: []*v2geoIP{{
			CountryCode: "US",
			CIDR:        []*v2geoCIDR{{IP: []byte{8, 8, 8, 0}, Prefix: 24}},
		}},
	})
	if err := os.WriteFile(filepath.Join(assetsDir, geoipVersion), []byte("2"), 0o600); err != nil {
		t.Fatal(err)
	}

	updatedOptions := routingRulesTestOptions(false)
	loaded, err := prepareRoutingRuleSets(&updatedOptions)
	if err != nil {
		t.Fatal(err)
	}
	if !loaded {
		t.Fatal("expected changed GeoIP version to reload its resource")
	}
	assertPreparedRoutingRules(t, updatedOptions, 2)
	assertRoutingRulesCacheVersion(t, cacheDir, routingRuleGeoIP, "2")
	assertRoutingRulesCacheVersion(t, cacheDir, routingRuleGeosite, "1")
}

func TestPrepareRoutingRuleSetsRebuildsCorruptDatabase(t *testing.T) {
	cacheDir, assetsDir := configureRoutingRulesTest(t)
	writeRoutingRulesTestAssets(t, assetsDir, "1", "1")
	cachePath := filepath.Join(cacheDir, routingRulesCacheFileName)
	if err := os.WriteFile(cachePath, []byte("not a bolt database"), 0o600); err != nil {
		t.Fatal(err)
	}

	options := routingRulesTestOptions(false)
	loaded, err := prepareRoutingRuleSets(&options)
	if err != nil {
		t.Fatal(err)
	}
	if !loaded {
		t.Fatal("expected corrupt cache to be rebuilt from resources")
	}
	assertRoutingRulesCacheKeys(t, cacheDir, routingRuleGeoIP, []string{"geoip:us"})
}

func TestPrepareRoutingRuleSetsRebuildsCorruptEntry(t *testing.T) {
	cacheDir, assetsDir := configureRoutingRulesTest(t)
	writeRoutingRulesTestAssets(t, assetsDir, "1", "1")
	options := routingRulesTestOptions(false)
	if _, err := prepareRoutingRuleSets(&options); err != nil {
		t.Fatal(err)
	}

	cachePath := filepath.Join(cacheDir, routingRulesCacheFileName)
	database, err := bbolt.Open(cachePath, 0o600, nil)
	if err != nil {
		t.Fatal(err)
	}
	err = database.Update(func(transaction *bbolt.Tx) error {
		return transaction.Bucket(geoIPBucketName).Put([]byte("geoip:us"), []byte("invalid srs"))
	})
	if closeErr := database.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(assetsDir, geositeDat)); err != nil {
		t.Fatal(err)
	}

	repairedOptions := routingRulesTestOptions(false)
	loaded, err := prepareRoutingRuleSets(&repairedOptions)
	if err != nil {
		t.Fatal(err)
	}
	if !loaded {
		t.Fatal("expected corrupt GeoIP entry to reload its resource")
	}
	assertPreparedRoutingRules(t, repairedOptions, 2)
}

func TestPrepareRoutingRuleSetsMissingVersionBypassesCache(t *testing.T) {
	_, assetsDir := configureRoutingRulesTest(t)
	writeRoutingRulesTestAssets(t, assetsDir, "1", "1")
	options := routingRulesTestOptions(false)
	if _, err := prepareRoutingRuleSets(&options); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(assetsDir, geoipVersion)); err != nil {
		t.Fatal(err)
	}

	uncachedOptions := routingRulesTestOptions(false)
	loaded, err := prepareRoutingRuleSets(&uncachedOptions)
	if err != nil {
		t.Fatal(err)
	}
	if !loaded {
		t.Fatal("expected missing version file to bypass GeoIP cache")
	}
}

func TestPrepareRoutingRuleSetsRejectsDuplicateTags(t *testing.T) {
	_, assetsDir := configureRoutingRulesTest(t)
	writeRoutingRulesTestAssets(t, assetsDir, "1", "1")
	options := routingRulesTestOptions(false)
	options.Route.RuleSet = append(options.Route.RuleSet, options.Route.RuleSet[0])
	if _, err := prepareRoutingRuleSets(&options); err == nil {
		t.Fatal("expected duplicate rule-set tag error")
	}
}

func TestPrepareRoutingRuleSetsExpandsMultiTagDefinitions(t *testing.T) {
	cacheDir, assetsDir := configureRoutingRulesTest(t)
	writeRoutingRulesTestAssets(t, assetsDir, "1", "1")
	options := option.Options{
		Route: &option.RouteOptions{
			Rules: []option.Rule{{
				Type: C.RuleTypeDefault,
				DefaultOptions: option.DefaultRule{RawDefaultRule: option.RawDefaultRule{
					RuleSet: []string{"us", "cn"},
				}},
			}},
			RuleSet: []option.RuleSet{{
				Type:         C.RuleSetTypeLocal,
				Tag:          []string{"us", "cn", "unused"},
				Format:       C.RuleSetFormatBinary,
				LocalOptions: option.LocalRuleSet{Path: "geoip:{tag}"},
			}},
		},
	}

	loaded, err := prepareRoutingRuleSets(&options)
	if err != nil {
		t.Fatal(err)
	}
	if !loaded {
		t.Fatal("expected multi-tag definitions to load geo resources")
	}
	assertPreparedRoutingRules(t, options, 2)
	if options.Route.RuleSet[0].Tag[0] != "us" || options.Route.RuleSet[1].Tag[0] != "cn" {
		t.Fatalf("unexpected expanded tags: %q, %q", options.Route.RuleSet[0].Tag, options.Route.RuleSet[1].Tag)
	}
	assertRoutingRulesCacheKeys(t, cacheDir, routingRuleGeoIP, []string{"geoip:cn", "geoip:us"})
}

func TestPrepareRoutingRuleSetsSynthesizesDNSOnlyDefinitions(t *testing.T) {
	cacheDir, assetsDir := configureRoutingRulesTest(t)
	writeRoutingRulesTestAssets(t, assetsDir, "1", "1")
	options := option.Options{
		DNS: &option.DNSOptions{RawDNSOptions: option.RawDNSOptions{
			Rules: []option.DNSRule{{
				Type: C.RuleTypeDefault,
				DefaultOptions: option.DefaultDNSRule{RawDefaultDNSRule: option.RawDefaultDNSRule{
					RuleSet: []string{"geoip:US", "geosite:test"},
				}},
			}},
		}},
	}

	loaded, err := prepareRoutingRuleSets(&options)
	if err != nil {
		t.Fatal(err)
	}
	if !loaded {
		t.Fatal("expected DNS-only definitions to load geo resources")
	}
	if options.Route == nil {
		t.Fatal("expected route options to be synthesized")
	}
	if len(options.Route.RuleSet) != 2 {
		t.Fatalf("unexpected synthesized rule-set count: %d", len(options.Route.RuleSet))
	}
	for _, ruleSet := range options.Route.RuleSet {
		if ruleSet.Type != C.RuleSetTypeInline {
			t.Fatalf("rule-set %s was not converted to inline", ruleSet.Tag)
		}
	}
	assertRoutingRulesCacheKeys(t, cacheDir, routingRuleGeoIP, []string{"geoip:us"})
	assertRoutingRulesCacheKeys(t, cacheDir, routingRuleGeosite, []string{"geosite:test"})
}

func TestPrepareRoutingRuleSetsWithPathsUsesIsolatedCache(t *testing.T) {
	defaultCacheDir, _ := configureRoutingRulesTest(t)
	assetsDir := t.TempDir()
	cacheDir := t.TempDir()
	writeRoutingRulesTestAssets(t, assetsDir, "subscription-geoip", "subscription-geosite")

	options := routingRulesTestOptions(false)
	cachePath := filepath.Join(cacheDir, routingRulesCacheFileName)
	loaded, err := prepareRoutingRuleSetsWithPaths(&options, assetsDir, cachePath)
	if err != nil {
		t.Fatal(err)
	}
	if !loaded {
		t.Fatal("expected isolated resources to be loaded")
	}
	if _, err := os.Stat(cachePath); err != nil {
		t.Fatalf("isolated cache was not created: %v", err)
	}
	if _, err := os.Stat(filepath.Join(defaultCacheDir, routingRulesCacheFileName)); !os.IsNotExist(err) {
		t.Fatalf("default cache was unexpectedly touched: %v", err)
	}
}

func configureRoutingRulesTest(t *testing.T) (string, string) {
	t.Helper()
	oldTempPath := tempPath
	oldExternalAssetsPath := externalAssetsPath
	oldIsBgProcess := isBgProcess
	cacheDir := t.TempDir()
	assetsDir := t.TempDir()
	tempPath = cacheDir
	externalAssetsPath = assetsDir + string(os.PathSeparator)
	isBgProcess = true
	t.Cleanup(func() {
		tempPath = oldTempPath
		externalAssetsPath = oldExternalAssetsPath
		isBgProcess = oldIsBgProcess
	})
	return cacheDir, assetsDir
}

func writeRoutingRulesTestAssets(t *testing.T, assetsDir, geoIPAssetVersion, geositeAssetVersion string) {
	t.Helper()
	writeGeoIPDatFile(t, filepath.Join(assetsDir, geoipDat), &v2geoIPList{
		Entry: []*v2geoIP{
			{CountryCode: "US", CIDR: []*v2geoCIDR{{IP: []byte{1, 2, 3, 0}, Prefix: 24}}},
			{CountryCode: "CN", CIDR: []*v2geoCIDR{{IP: []byte{10, 0, 0, 0}, Prefix: 8}}},
		},
	})
	writeGeositeDatFile(t, filepath.Join(assetsDir, geositeDat), &v2geoSiteList{
		Entry: []*v2geoSite{{
			CountryCode: "TEST",
			Domain:      []*v2geoDomain{{Type: v2geoDomainRootDomain, Value: "example.com"}},
		}},
	})
	if err := os.WriteFile(filepath.Join(assetsDir, geoipVersion), []byte(geoIPAssetVersion), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(assetsDir, geositeVersion), []byte(geositeAssetVersion), 0o600); err != nil {
		t.Fatal(err)
	}
}

func routingRulesTestOptions(includeChina bool) option.Options {
	geoReferences := []string{"geo-us"}
	ruleSets := []option.RuleSet{
		{
			Type:         C.RuleSetTypeLocal,
			Tag:          []string{"geo-us"},
			Format:       C.RuleSetFormatBinary,
			LocalOptions: option.LocalRuleSet{Path: "geoip:US"},
		},
		{
			Type:         C.RuleSetTypeLocal,
			Tag:          []string{"site-test"},
			Format:       C.RuleSetFormatBinary,
			LocalOptions: option.LocalRuleSet{Path: "geosite:test"},
		},
		{
			Type:         C.RuleSetTypeLocal,
			Tag:          []string{"unused-site"},
			Format:       C.RuleSetFormatBinary,
			LocalOptions: option.LocalRuleSet{Path: "geosite:unused"},
		},
	}
	if includeChina {
		geoReferences = append(geoReferences, "geo-cn")
		ruleSets = append(ruleSets, option.RuleSet{
			Type:         C.RuleSetTypeLocal,
			Tag:          []string{"geo-cn"},
			Format:       C.RuleSetFormatBinary,
			LocalOptions: option.LocalRuleSet{Path: "geoip:cn"},
		})
	}
	return option.Options{
		DNS: &option.DNSOptions{RawDNSOptions: option.RawDNSOptions{
			Rules: []option.DNSRule{{
				Type: C.RuleTypeLogical,
				LogicalOptions: option.LogicalDNSRule{RawLogicalDNSRule: option.RawLogicalDNSRule{
					Rules: []option.DNSRule{{
						Type: C.RuleTypeDefault,
						DefaultOptions: option.DefaultDNSRule{RawDefaultDNSRule: option.RawDefaultDNSRule{
							RuleSet: []string{"site-test"},
						}},
					}},
				}},
			}},
		}},
		Route: &option.RouteOptions{
			Rules: []option.Rule{{
				Type: C.RuleTypeLogical,
				LogicalOptions: option.LogicalRule{RawLogicalRule: option.RawLogicalRule{
					Rules: []option.Rule{{
						Type: C.RuleTypeDefault,
						DefaultOptions: option.DefaultRule{RawDefaultRule: option.RawDefaultRule{
							RuleSet: geoReferences,
						}},
					}},
				}},
			}},
			RuleSet: ruleSets,
		},
	}
}

func assertPreparedRoutingRules(t *testing.T, options option.Options, expected int) {
	t.Helper()
	if len(options.Route.RuleSet) != expected {
		t.Fatalf("unexpected prepared rule-set count: got %d, want %d", len(options.Route.RuleSet), expected)
	}
	for _, ruleSet := range options.Route.RuleSet {
		if ruleSet.Type != C.RuleSetTypeInline {
			t.Fatalf("rule-set %s was not converted to inline", ruleSet.Tag)
		}
		if len(ruleSet.InlineOptions.Rules) != 1 {
			t.Fatalf("rule-set %s has unexpected rules: %#v", ruleSet.Tag, ruleSet.InlineOptions.Rules)
		}
		prepared := ruleSet.InlineOptions.Rules[0].DefaultOptions
		if len(ruleSet.Tag) != 1 {
			t.Fatalf("prepared rule-set has multiple tags: %q", ruleSet.Tag)
		}
		if ruleSet.Tag[0] == "site-test" {
			if prepared.DomainMatcher == nil {
				t.Fatal("expected compiled geosite domain matcher")
			}
		} else if prepared.IPSet == nil {
			t.Fatalf("expected compiled GeoIP set for %s", ruleSet.Tag)
		}
	}
}

func assertRoutingRulesCacheKeys(t *testing.T, cacheDir string, kind routingRuleKind, expected []string) {
	t.Helper()
	database, err := bbolt.Open(filepath.Join(cacheDir, routingRulesCacheFileName), 0o600, &bbolt.Options{ReadOnly: true})
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	var keys []string
	if err := database.View(func(transaction *bbolt.Tx) error {
		bucket := transaction.Bucket(routingRuleBucketName(kind))
		if bucket == nil {
			t.Fatalf("missing cache bucket %q", routingRuleBucketName(kind))
		}
		return bucket.ForEach(func(key, _ []byte) error {
			if string(key) != string(routingRulesVersionKey) {
				keys = append(keys, string(key))
			}
			return nil
		})
	}); err != nil {
		t.Fatal(err)
	}
	if len(keys) != len(expected) {
		t.Fatalf("unexpected cache keys: got %q, want %q", keys, expected)
	}
	for index := range keys {
		if keys[index] != expected[index] {
			t.Fatalf("unexpected cache keys: got %q, want %q", keys, expected)
		}
	}
}

func assertRoutingRulesCacheVersion(t *testing.T, cacheDir string, kind routingRuleKind, expected string) {
	t.Helper()
	database, err := bbolt.Open(filepath.Join(cacheDir, routingRulesCacheFileName), 0o600, &bbolt.Options{ReadOnly: true})
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	if err := database.View(func(transaction *bbolt.Tx) error {
		bucket := transaction.Bucket(routingRuleBucketName(kind))
		if bucket == nil {
			t.Fatalf("missing cache bucket %q", routingRuleBucketName(kind))
		}
		if version := string(bucket.Get(routingRulesVersionKey)); version != expected {
			t.Fatalf("unexpected cache version: got %q, want %q", version, expected)
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}
