package libcore

import (
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"strings"

	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type geosite struct {
	geositeReader *geosites.Reader
	datEntries    map[string]*v2geoSite
	codes         []string
}

func (g *geosite) Open(path string) error {
	geositeReader, codes, err := geosites.Open(path)
	if err == nil {
		g.geositeReader = geositeReader
		g.codes = codes
		return nil
	}
	datEntries, codes, datErr := loadV2GeoSite(path)
	if datErr != nil {
		return fmt.Errorf("open geosite as db: %w; open geosite as dat: %w", err, datErr)
	}
	g.datEntries = datEntries
	g.codes = codes
	return nil
}

func (g *geosite) Close() error {
	var err error
	if g.geositeReader != nil {
		closer, ok := g.geositeReader.Upstream().(io.Closer)
		if ok {
			err = closer.Close()
		}
	}
	g.geositeReader = nil
	g.datEntries = nil
	g.codes = nil
	return err
}

func (g *geosite) Rules(code string) ([]option.HeadlessRule, error) {
	if g.datEntries != nil {
		return g.datRules(code)
	}
	sourceSet, err := g.geositeReader.Read(code)
	if err != nil {
		return nil, fmt.Errorf("read geosite code %s: %w", code, err)
	}

	defaultRule := geosites.Compile(sourceSet)
	return wrapGeositeRule(option.DefaultHeadlessRule{
		Domain:        defaultRule.Domain,
		DomainSuffix:  defaultRule.DomainSuffix,
		DomainKeyword: defaultRule.DomainKeyword,
		DomainRegex:   defaultRule.DomainRegex,
	}), nil
}

func (g *geosite) datRules(code string) ([]option.HeadlessRule, error) {
	code = canonicalGeositeCode(code)
	entry := g.datEntries[code]
	if entry == nil {
		return nil, fmt.Errorf("read geosite code %s: code does not exist", code)
	}

	var headlessRule option.DefaultHeadlessRule
	for _, domain := range entry.Domain {
		if domain == nil || domain.Value == "" {
			continue
		}
		switch domain.Type {
		case v2geoDomainPlain:
			headlessRule.DomainKeyword = append(headlessRule.DomainKeyword, domain.Value)
		case v2geoDomainRegex:
			headlessRule.DomainRegex = append(headlessRule.DomainRegex, domain.Value)
		case v2geoDomainRootDomain:
			headlessRule.Domain = append(headlessRule.Domain, domain.Value)
			headlessRule.DomainSuffix = append(headlessRule.DomainSuffix, "."+domain.Value)
		case v2geoDomainFull:
			headlessRule.Domain = append(headlessRule.Domain, domain.Value)
		default:
			return nil, fmt.Errorf("unsupported geosite domain type %d for code %s", domain.Type, code)
		}
	}
	return wrapGeositeRule(headlessRule), nil
}

func wrapGeositeRule(rule option.DefaultHeadlessRule) []option.HeadlessRule {
	return []option.HeadlessRule{{
		Type:           C.RuleTypeDefault,
		DefaultOptions: rule,
	}}
}

func canonicalGeositeCode(code string) string {
	return strings.ToLower(strings.TrimSpace(code))
}

func loadGeositeRules(path string, codes []string) (rules map[string][]option.HeadlessRule, err error) {
	reader := new(geosite)
	if err = reader.Open(path); err != nil {
		return nil, err
	}
	defer func() {
		err = errors.Join(err, reader.Close())
	}()

	rules = make(map[string][]option.HeadlessRule, len(codes))
	for _, code := range codes {
		code = canonicalGeositeCode(code)
		if code == "" {
			continue
		}
		if _, loaded := rules[code]; loaded {
			continue
		}
		rules[code], err = reader.Rules(code)
		if err != nil {
			return nil, err
		}
	}
	return rules, nil
}

func init() {
	nekoutils.GetGeoSiteHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		rules, err := loadGeositeRules(filepath.Join(externalAssetsPath, geositeDat), []string{name})
		if err != nil {
			return nil, err
		}
		return rules[canonicalGeositeCode(name)], nil
	}
}
