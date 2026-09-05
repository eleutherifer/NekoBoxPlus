package libcore

import (
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/oschwald/maxminddb-golang"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type countryReaderCache struct {
	mu      sync.Mutex
	reader  *geoip
	path    string
	size    int64
	modTime int64
}

var globalCountryReader countryReaderCache

type geoip struct {
	geoipReader     *maxminddb.Reader
	datEntries      map[string]*v2geoIP
	countryPrefixes map[netip.Prefix]string
	codes           []string
	cachedRules     map[string]*headlessRuleEntry
}

type headlessRuleEntry struct {
	items []option.HeadlessRule
	err   error
}

func (g *geoip) Open(path string) error {
	geoipReader, err := maxminddb.Open(path)
	if err == nil {
		g.geoipReader = geoipReader
		g.cachedRules = make(map[string]*headlessRuleEntry)
		return nil
	}
	datEntries, codes, datErr := loadV2GeoIP(path)
	if datErr != nil {
		return fmt.Errorf("open geoip as db: %w; open geoip as dat: %w", err, datErr)
	}
	g.datEntries = datEntries
	g.countryPrefixes = make(map[netip.Prefix]string)
	for countryCode, entry := range datEntries {
		countryCode = validCountryCode(countryCode)
		if countryCode == "" {
			continue
		}
		for _, cidr := range entry.CIDR {
			prefixValue, prefixErr := cidrString(cidr)
			if prefixErr != nil {
				continue
			}
			prefix, prefixErr := netip.ParsePrefix(prefixValue)
			if prefixErr == nil {
				g.countryPrefixes[prefix.Masked()] = countryCode
			}
		}
	}
	g.codes = codes
	g.cachedRules = make(map[string]*headlessRuleEntry)
	return nil
}

func (g *geoip) Close() error {
	var err error
	if g.geoipReader != nil {
		err = g.geoipReader.Close()
	}
	g.geoipReader = nil
	g.datEntries = nil
	g.countryPrefixes = nil
	g.codes = nil
	g.cachedRules = nil
	return err
}

func (g *geoip) Rules(countryCode string) ([]option.HeadlessRule, error) {
	countryCode = canonicalGeoIPCode(countryCode)
	if cached := g.cachedRules[countryCode]; cached != nil {
		return cached.items, cached.err
	}
	rules, err := g.RulesForCountries([]string{countryCode})
	if err != nil {
		return nil, err
	}
	return rules[countryCode], nil
}

func (g *geoip) Country(address netip.Addr) (string, error) {
	address = address.Unmap()
	if !address.IsValid() || !address.IsGlobalUnicast() || address.IsPrivate() {
		return "", nil
	}
	if g.geoipReader != nil {
		var countryCode string
		if err := g.geoipReader.Lookup(net.IP(address.AsSlice()), &countryCode); err != nil {
			return "", fmt.Errorf("lookup geoip country: %w", err)
		}
		return validCountryCode(countryCode), nil
	}
	for bits := address.BitLen(); bits >= 0; bits-- {
		if countryCode := g.countryPrefixes[netip.PrefixFrom(address, bits).Masked()]; countryCode != "" {
			return countryCode, nil
		}
	}
	return "", nil
}

func validCountryCode(countryCode string) string {
	countryCode = strings.ToUpper(strings.TrimSpace(countryCode))
	if len(countryCode) != 2 || countryCode[0] < 'A' || countryCode[0] > 'Z' || countryCode[1] < 'A' || countryCode[1] > 'Z' {
		return ""
	}
	return countryCode
}

// CountryCodeForIP returns an ISO alpha-2 country code from the bundled GeoIP asset.
func CountryCodeForIP(ip string) (string, error) {
	address, err := netip.ParseAddr(strings.Trim(strings.TrimSpace(ip), "[]"))
	if err != nil {
		return "", fmt.Errorf("parse IP address: %w", err)
	}
	path := filepath.Join(externalAssetsPath, geoipDat)
	stat, err := os.Stat(path)
	if err != nil {
		return "", fmt.Errorf("stat geoip database: %w", err)
	}

	globalCountryReader.mu.Lock()
	defer globalCountryReader.mu.Unlock()
	if globalCountryReader.reader == nil || globalCountryReader.path != path ||
		globalCountryReader.size != stat.Size() || globalCountryReader.modTime != stat.ModTime().UnixNano() {
		reader := new(geoip)
		if err = reader.Open(path); err != nil {
			return "", err
		}
		if globalCountryReader.reader != nil {
			_ = globalCountryReader.reader.Close()
		}
		globalCountryReader.reader = reader
		globalCountryReader.path = path
		globalCountryReader.size = stat.Size()
		globalCountryReader.modTime = stat.ModTime().UnixNano()
	}
	return globalCountryReader.reader.Country(address)
}

func (g *geoip) RulesForCountries(countryCodes []string) (map[string][]option.HeadlessRule, error) {
	requested := make(map[string]struct{}, len(countryCodes))
	result := make(map[string][]option.HeadlessRule, len(countryCodes))
	for _, countryCode := range countryCodes {
		countryCode = canonicalGeoIPCode(countryCode)
		if countryCode == "" {
			continue
		}
		if cached := g.cachedRules[countryCode]; cached != nil {
			if cached.err != nil {
				return nil, cached.err
			}
			result[countryCode] = cached.items
			continue
		}
		requested[countryCode] = struct{}{}
	}
	if len(requested) == 0 {
		return result, nil
	}

	if g.datEntries != nil {
		for countryCode := range requested {
			rules, err := g.datRules(countryCode)
			if err != nil {
				g.cachedRules[countryCode] = &headlessRuleEntry{err: err}
				return nil, err
			}
			result[countryCode] = rules
		}
		return result, nil
	}

	countryNetworks := make(map[string][]*net.IPNet, len(requested))
	networks := g.geoipReader.Networks(maxminddb.SkipAliasedNetworks)
	for networks.Next() {
		var nextCountryCode string
		ipNet, err := networks.Network(&nextCountryCode)
		if err != nil {
			return nil, fmt.Errorf("get geoip network: %w", err)
		}
		nextCountryCode = canonicalGeoIPCode(nextCountryCode)
		if _, needed := requested[nextCountryCode]; needed {
			countryNetworks[nextCountryCode] = append(countryNetworks[nextCountryCode], ipNet)
		}
	}
	if err := networks.Err(); err != nil {
		return nil, fmt.Errorf("iterate geoip networks: %w", err)
	}

	for countryCode := range requested {
		ipNets := countryNetworks[countryCode]
		if len(ipNets) == 0 {
			err := fmt.Errorf("no networks found for country code: %s", countryCode)
			g.cachedRules[countryCode] = &headlessRuleEntry{err: err}
			return nil, err
		}
		headlessRule := option.DefaultHeadlessRule{
			IPCIDR: make([]string, 0, len(ipNets)),
		}
		for _, cidr := range ipNets {
			headlessRule.IPCIDR = append(headlessRule.IPCIDR, cidr.String())
		}
		rules := wrapGeoIPRule(headlessRule)
		g.cachedRules[countryCode] = &headlessRuleEntry{items: rules}
		result[countryCode] = rules
	}
	return result, nil
}

func (g *geoip) datRules(countryCode string) ([]option.HeadlessRule, error) {
	entry := g.datEntries[countryCode]
	if entry == nil || len(entry.CIDR) == 0 {
		return nil, fmt.Errorf("no networks found for country code: %s", countryCode)
	}

	headlessRule := option.DefaultHeadlessRule{
		IPCIDR: make([]string, 0, len(entry.CIDR)),
	}
	for _, cidr := range entry.CIDR {
		cidrValue, err := cidrString(cidr)
		if err != nil {
			return nil, err
		}
		headlessRule.IPCIDR = append(headlessRule.IPCIDR, cidrValue)
	}
	rules := wrapGeoIPRule(headlessRule)
	g.cachedRules[countryCode] = &headlessRuleEntry{items: rules}
	return rules, nil
}

func wrapGeoIPRule(rule option.DefaultHeadlessRule) []option.HeadlessRule {
	return []option.HeadlessRule{{
		Type:           C.RuleTypeDefault,
		DefaultOptions: rule,
	}}
}

func canonicalGeoIPCode(countryCode string) string {
	return strings.ToLower(strings.TrimSpace(countryCode))
}

func loadGeoIPRules(path string, countryCodes []string) (rules map[string][]option.HeadlessRule, err error) {
	reader := new(geoip)
	if err = reader.Open(path); err != nil {
		return nil, err
	}
	defer func() {
		err = errors.Join(err, reader.Close())
	}()
	return reader.RulesForCountries(countryCodes)
}

func init() {
	nekoutils.GetGeoIPHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		rules, err := loadGeoIPRules(filepath.Join(externalAssetsPath, geoipDat), []string{name})
		if err != nil {
			return nil, err
		}
		return rules[canonicalGeoIPCode(name)], nil
	}
}
