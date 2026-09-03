package libcore

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"path/filepath"
	"slices"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	R "github.com/sagernet/sing-box/route/rule"
)

var errRuleSetMatchCancelled = errors.New("rule set match cancelled")

type RuleSetMatchResult struct {
	entries []string
}

func (r *RuleSetMatchResult) Count() int {
	if r == nil {
		return 0
	}
	return len(r.entries)
}

func (r *RuleSetMatchResult) Get(index int) string {
	if r == nil || index < 0 || index >= len(r.entries) {
		return ""
	}
	return r.entries[index]
}

type RuleSetMatchSession struct {
	ctx       context.Context
	cancel    context.CancelCauseFunc
	closeOnce sync.Once
	runOnce   sync.Once
	run       func(context.Context, string) ([]string, error)
	result    *RuleSetMatchResult
	err       error
}

func NewRuleSetMatchSession() *RuleSetMatchSession {
	ctx, cancel := context.WithCancelCause(context.Background())
	session := &RuleSetMatchSession{
		ctx:    ctx,
		cancel: cancel,
	}
	session.run = func(ctx context.Context, keyword string) ([]string, error) {
		return matchGeoFiles(
			ctx,
			keyword,
			filepath.Join(externalAssetsPath, geositeDat),
			filepath.Join(externalAssetsPath, geoipDat),
		)
	}
	return session
}

func (s *RuleSetMatchSession) Run(keyword string) (*RuleSetMatchResult, error) {
	if s == nil {
		return nil, errors.New("nil rule set match session")
	}
	s.runOnce.Do(func() {
		entries, err := s.run(s.ctx, keyword)
		s.result = &RuleSetMatchResult{entries: entries}
		s.err = err
	})
	if s.result == nil {
		s.result = &RuleSetMatchResult{}
	}
	return s.result, s.err
}

func (s *RuleSetMatchSession) Close() {
	if s == nil {
		return
	}
	s.closeOnce.Do(func() {
		s.cancel(errRuleSetMatchCancelled)
	})
}

func matchGeoFiles(
	ctx context.Context,
	keyword string,
	geositePath string,
	geoIPPath string,
) ([]string, error) {
	keyword = strings.TrimSpace(keyword)
	if keyword == "" {
		return nil, errors.New("destination address is empty")
	}
	if address, err := netip.ParseAddr(keyword); err == nil {
		return matchGeoIP(ctx, address.Unmap(), geoIPPath)
	}
	return matchGeosite(ctx, strings.TrimSuffix(strings.ToLower(keyword), "."), geositePath)
}

func matchGeosite(ctx context.Context, domain string, path string) (result []string, err error) {
	reader := new(geosite)
	if err = reader.Open(path); err != nil {
		return nil, err
	}
	defer func() {
		err = errors.Join(err, reader.Close())
	}()

	codes := slices.Sorted(slices.Values(reader.codes))
	metadata := adapter.InboundContext{Domain: domain}
	for _, code := range codes {
		if err := context.Cause(ctx); err != nil {
			return nil, err
		}
		options, err := reader.Rules(code)
		if err != nil {
			return nil, fmt.Errorf("read geosite:%s: %w", code, err)
		}
		for _, ruleOptions := range options {
			if err := context.Cause(ctx); err != nil {
				return nil, err
			}
			rule, err := R.NewHeadlessRule(ctx, ruleOptions)
			if err != nil {
				return nil, fmt.Errorf("compile geosite:%s: %w", code, err)
			}
			if rule.Match(&metadata) {
				result = append(result, "geosite:"+canonicalGeositeCode(code))
				break
			}
		}
	}
	return result, nil
}

func matchGeoIP(ctx context.Context, address netip.Addr, path string) (result []string, err error) {
	reader := new(geoip)
	if err = reader.Open(path); err != nil {
		return nil, err
	}
	defer func() {
		err = errors.Join(err, reader.Close())
	}()

	if err := context.Cause(ctx); err != nil {
		return nil, err
	}
	if reader.geoipReader != nil {
		var countryCode string
		if err := reader.geoipReader.Lookup(net.IP(address.AsSlice()), &countryCode); err != nil {
			return nil, fmt.Errorf("lookup GeoIP address: %w", err)
		}
		countryCode = canonicalGeoIPCode(countryCode)
		if countryCode == "" {
			return nil, nil
		}
		return []string{"geoip:" + countryCode}, nil
	}

	for _, code := range reader.codes {
		if err := context.Cause(ctx); err != nil {
			return nil, err
		}
		entry := reader.datEntries[code]
		if entry == nil {
			continue
		}
		for _, cidr := range entry.CIDR {
			if err := context.Cause(ctx); err != nil {
				return nil, err
			}
			prefix, err := v2GeoPrefix(cidr)
			if err != nil {
				return nil, fmt.Errorf("read geoip:%s: %w", code, err)
			}
			if prefix.Contains(address) {
				result = append(result, "geoip:"+canonicalGeoIPCode(code))
				break
			}
		}
	}
	slices.Sort(result)
	return result, nil
}

func v2GeoPrefix(cidr *v2geoCIDR) (netip.Prefix, error) {
	if cidr == nil {
		return netip.Prefix{}, errors.New("empty CIDR entry")
	}
	address, ok := netip.AddrFromSlice(cidr.IP)
	if !ok {
		return netip.Prefix{}, fmt.Errorf("invalid IP length: %d", len(cidr.IP))
	}
	bits := address.BitLen()
	if cidr.Prefix > uint32(bits) {
		return netip.Prefix{}, fmt.Errorf("invalid IPv%d prefix: %d", bits, cidr.Prefix)
	}
	return netip.PrefixFrom(address.Unmap(), int(cidr.Prefix)).Masked(), nil
}
