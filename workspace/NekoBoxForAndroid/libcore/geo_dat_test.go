package libcore

import (
	"bytes"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	geosites "github.com/sagernet/sing-box/common/geosite"

	"github.com/golang/protobuf/proto"
)

func TestGeoIPDatCodesAndRules(t *testing.T) {
	path := writeGeoIPDat(t, &v2geoIPList{
		Entry: []*v2geoIP{
			{
				CountryCode: "US",
				CIDR: []*v2geoCIDR{
					{IP: []byte{1, 2, 3, 0}, Prefix: 24},
					{IP: net.ParseIP("2001:db8::").To16(), Prefix: 32},
				},
			},
			{
				CountryCode: "CN",
				CIDR:        []*v2geoCIDR{{IP: []byte{10, 0, 0, 0}, Prefix: 8}},
			},
		},
	})

	codes, err := ListGeoipCodes(path)
	if err != nil {
		t.Fatal(err)
	}
	if codes != "cn\nus" {
		t.Fatalf("unexpected codes: %q", codes)
	}

	var reader geoip
	if err := reader.Open(path); err != nil {
		t.Fatal(err)
	}
	rules, err := reader.Rules("us")
	if err != nil {
		t.Fatal(err)
	}
	got := rules[0].DefaultOptions.IPCIDR
	want := []string{"1.2.3.0/24", "2001:db8::/32"}
	if strings.Join(got, "\n") != strings.Join(want, "\n") {
		t.Fatalf("unexpected CIDRs: %#v", got)
	}
	if err := reader.Close(); err != nil {
		t.Fatal(err)
	}
	if reader.geoipReader != nil || reader.datEntries != nil || reader.cachedRules != nil {
		t.Fatal("expected GeoIP reader state to be released")
	}
}

func TestGeoIPDatMissingCode(t *testing.T) {
	path := writeGeoIPDat(t, &v2geoIPList{
		Entry: []*v2geoIP{{CountryCode: "US", CIDR: []*v2geoCIDR{{IP: []byte{1, 2, 3, 0}, Prefix: 24}}}},
	})

	var reader geoip
	if err := reader.Open(path); err != nil {
		t.Fatal(err)
	}
	if _, err := reader.Rules("cn"); err == nil {
		t.Fatal("expected missing code error")
	}
}

func TestGeoIPDatCacheInvalidatesWhenFileChanges(t *testing.T) {
	path := filepath.Join(t.TempDir(), "geoip.dat")
	writeGeoIPDatFile(t, path, &v2geoIPList{
		Entry: []*v2geoIP{{CountryCode: "US", CIDR: []*v2geoCIDR{{IP: []byte{1, 2, 3, 0}, Prefix: 24}}}},
	})

	entries, _, err := loadV2GeoIP(path)
	if err != nil {
		t.Fatal(err)
	}
	if entries["us"] == nil {
		t.Fatal("expected initial US entry")
	}

	writeGeoIPDatFile(t, path, &v2geoIPList{
		Entry: []*v2geoIP{{CountryCode: "CN", CIDR: []*v2geoCIDR{{IP: []byte{10, 0, 0, 0}, Prefix: 8}}}},
	})
	touchFuture(t, path)

	entries, codes, err := loadV2GeoIP(path)
	if err != nil {
		t.Fatal(err)
	}
	if entries["cn"] == nil || entries["us"] != nil {
		t.Fatalf("expected cache to reload changed file, codes=%q", strings.Join(codes, "\n"))
	}
}

func TestGeoIPDatLoadWithoutScopeDoesNotRetainCache(t *testing.T) {
	clearV2GeoCache()
	path := writeGeoIPDat(t, &v2geoIPList{
		Entry: []*v2geoIP{{CountryCode: "US", CIDR: []*v2geoCIDR{{IP: []byte{1, 2, 3, 0}, Prefix: 24}}}},
	})

	if _, _, err := loadV2GeoIP(path); err != nil {
		t.Fatal(err)
	}
	if v2geoIPCache.entries != nil {
		t.Fatal("expected uncached load outside cache scope")
	}
}

func TestGeoIPDatCacheScopeClearsParsedEntries(t *testing.T) {
	clearV2GeoCache()
	path := writeGeoIPDat(t, &v2geoIPList{
		Entry: []*v2geoIP{{CountryCode: "US", CIDR: []*v2geoCIDR{{IP: []byte{1, 2, 3, 0}, Prefix: 24}}}},
	})

	endScope := beginV2GeoCacheScope()
	if _, _, err := loadV2GeoIP(path); err != nil {
		endScope()
		t.Fatal(err)
	}
	if v2geoIPCache.entries == nil {
		endScope()
		t.Fatal("expected cached load inside cache scope")
	}
	if !endScope() {
		t.Fatal("expected cache cleanup after scope")
	}
	if v2geoIPCache.entries != nil {
		t.Fatal("expected cache entries to be cleared")
	}
}

func TestGeositeDatCodesAndRules(t *testing.T) {
	path := writeGeositeDat(t, &v2geoSiteList{
		Entry: []*v2geoSite{
			{
				CountryCode: "TEST",
				Domain: []*v2geoDomain{
					{Type: v2geoDomainPlain, Value: "keyword"},
					{Type: v2geoDomainRegex, Value: `^api\.`},
					{Type: v2geoDomainRootDomain, Value: "example.com"},
					{Type: v2geoDomainFull, Value: "full.example"},
				},
			},
			{
				CountryCode: "CN",
				Domain:      []*v2geoDomain{{Type: v2geoDomainFull, Value: "cn.example"}},
			},
		},
	})

	codes, err := ListGeositeCodes(path)
	if err != nil {
		t.Fatal(err)
	}
	if codes != "cn\ntest" {
		t.Fatalf("unexpected codes: %q", codes)
	}

	var reader geosite
	if err := reader.Open(path); err != nil {
		t.Fatal(err)
	}
	rules, err := reader.Rules("test")
	if err != nil {
		t.Fatal(err)
	}
	options := rules[0].DefaultOptions
	assertStrings(t, options.DomainKeyword, []string{"keyword"})
	assertStrings(t, options.DomainRegex, []string{`^api\.`})
	assertStrings(t, options.Domain, []string{"example.com", "full.example"})
	assertStrings(t, options.DomainSuffix, []string{".example.com"})
	if err := reader.Close(); err != nil {
		t.Fatal(err)
	}
	if reader.geositeReader != nil || reader.datEntries != nil {
		t.Fatal("expected geosite reader state to be released")
	}
}

func TestGeositeDatMissingCode(t *testing.T) {
	path := writeGeositeDat(t, &v2geoSiteList{
		Entry: []*v2geoSite{{CountryCode: "TEST", Domain: []*v2geoDomain{{Type: v2geoDomainFull, Value: "full.example"}}}},
	})

	var reader geosite
	if err := reader.Open(path); err != nil {
		t.Fatal(err)
	}
	if _, err := reader.Rules("missing"); err == nil {
		t.Fatal("expected missing code error")
	}
}

func TestGeositeDatCacheInvalidatesWhenFileChanges(t *testing.T) {
	path := filepath.Join(t.TempDir(), "geosite.dat")
	writeGeositeDatFile(t, path, &v2geoSiteList{
		Entry: []*v2geoSite{{CountryCode: "TEST", Domain: []*v2geoDomain{{Type: v2geoDomainFull, Value: "full.example"}}}},
	})

	entries, _, err := loadV2GeoSite(path)
	if err != nil {
		t.Fatal(err)
	}
	if entries["test"] == nil {
		t.Fatal("expected initial TEST entry")
	}

	writeGeositeDatFile(t, path, &v2geoSiteList{
		Entry: []*v2geoSite{{CountryCode: "CN", Domain: []*v2geoDomain{{Type: v2geoDomainFull, Value: "cn.example"}}}},
	})
	touchFuture(t, path)

	entries, codes, err := loadV2GeoSite(path)
	if err != nil {
		t.Fatal(err)
	}
	if entries["cn"] == nil || entries["test"] != nil {
		t.Fatalf("expected cache to reload changed file, codes=%q", strings.Join(codes, "\n"))
	}
}

func TestGeositeDbStillWorks(t *testing.T) {
	path := filepath.Join(t.TempDir(), "geosite.db")
	var buffer bytes.Buffer
	if err := geosites.Write(&buffer, map[string][]geosites.Item{
		"test": {{Type: geosites.RuleTypeDomain, Value: "example.com"}},
	}); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, buffer.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}

	var reader geosite
	if err := reader.Open(path); err != nil {
		t.Fatal(err)
	}
	rules, err := reader.Rules("test")
	if err != nil {
		t.Fatal(err)
	}
	assertStrings(t, rules[0].DefaultOptions.Domain, []string{"example.com"})
	if err := reader.Close(); err != nil {
		t.Fatal(err)
	}
	if reader.geositeReader != nil || reader.datEntries != nil {
		t.Fatal("expected geosite DB reader state to be released")
	}
}

func writeGeoIPDat(t *testing.T, list *v2geoIPList) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "geoip.dat")
	writeGeoIPDatFile(t, path, list)
	return path
}

func writeGeoIPDatFile(t *testing.T, path string, list *v2geoIPList) {
	t.Helper()
	data, err := proto.Marshal(list)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
}

func writeGeositeDat(t *testing.T, list *v2geoSiteList) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "geosite.dat")
	writeGeositeDatFile(t, path, list)
	return path
}

func writeGeositeDatFile(t *testing.T, path string, list *v2geoSiteList) {
	t.Helper()
	data, err := proto.Marshal(list)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
}

func touchFuture(t *testing.T, path string) {
	t.Helper()
	ts := time.Now().Add(time.Second)
	if err := os.Chtimes(path, ts, ts); err != nil {
		t.Fatal(err)
	}
}

func assertStrings(t *testing.T, got []string, want []string) {
	t.Helper()
	if strings.Join(got, "\n") != strings.Join(want, "\n") {
		t.Fatalf("unexpected strings: got %#v, want %#v", got, want)
	}
}
