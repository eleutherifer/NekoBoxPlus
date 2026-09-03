package libcore

import (
	"fmt"
	"maps"
	"net"
	"os"
	"slices"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/golang/protobuf/proto"
)

const (
	v2geoDomainPlain      = 0
	v2geoDomainRegex      = 1
	v2geoDomainRootDomain = 2
	v2geoDomainFull       = 3
)

type v2geoDomain struct {
	Type  int32  `protobuf:"varint,1,opt,name=type,proto3,enum=Domain_Type" json:"type,omitempty"`
	Value string `protobuf:"bytes,2,opt,name=value,proto3" json:"value,omitempty"`
}

func (*v2geoDomain) Reset()           {}
func (*v2geoDomain) ProtoMessage()    {}
func (m *v2geoDomain) String() string { return proto.CompactTextString(m) }

type v2geoCIDR struct {
	IP     []byte `protobuf:"bytes,1,opt,name=ip,proto3" json:"ip,omitempty"`
	Prefix uint32 `protobuf:"varint,2,opt,name=prefix,proto3" json:"prefix,omitempty"`
}

func (*v2geoCIDR) Reset()           {}
func (*v2geoCIDR) ProtoMessage()    {}
func (m *v2geoCIDR) String() string { return proto.CompactTextString(m) }

type v2geoIP struct {
	CountryCode string       `protobuf:"bytes,1,opt,name=country_code,json=countryCode,proto3" json:"country_code,omitempty"`
	CIDR        []*v2geoCIDR `protobuf:"bytes,2,rep,name=cidr,proto3" json:"cidr,omitempty"`
}

func (*v2geoIP) Reset()           {}
func (*v2geoIP) ProtoMessage()    {}
func (m *v2geoIP) String() string { return proto.CompactTextString(m) }

type v2geoIPList struct {
	Entry []*v2geoIP `protobuf:"bytes,1,rep,name=entry,proto3" json:"entry,omitempty"`
}

func (*v2geoIPList) Reset()           {}
func (*v2geoIPList) ProtoMessage()    {}
func (m *v2geoIPList) String() string { return proto.CompactTextString(m) }

type v2geoSite struct {
	CountryCode string         `protobuf:"bytes,1,opt,name=country_code,json=countryCode,proto3" json:"country_code,omitempty"`
	Domain      []*v2geoDomain `protobuf:"bytes,2,rep,name=domain,proto3" json:"domain,omitempty"`
}

func (*v2geoSite) Reset()           {}
func (*v2geoSite) ProtoMessage()    {}
func (m *v2geoSite) String() string { return proto.CompactTextString(m) }

type v2geoSiteList struct {
	Entry []*v2geoSite `protobuf:"bytes,1,rep,name=entry,proto3" json:"entry,omitempty"`
}

func (*v2geoSiteList) Reset()           {}
func (*v2geoSiteList) ProtoMessage()    {}
func (m *v2geoSiteList) String() string { return proto.CompactTextString(m) }

type v2geoFileKey struct {
	path        string
	size        int64
	modTimeNano int64
}

type v2geoParsedCache[T any] struct {
	mu      sync.Mutex
	key     v2geoFileKey
	entries map[string]*T
	codes   []string
}

var (
	v2geoIPCache   v2geoParsedCache[v2geoIP]
	v2geoSiteCache v2geoParsedCache[v2geoSite]
	v2geoCacheUses atomic.Int32
)

func beginV2GeoCacheScope() func() bool {
	v2geoCacheUses.Add(1)
	return func() bool {
		if v2geoCacheUses.Add(-1) != 0 {
			return false
		}
		return clearV2GeoCache()
	}
}

func clearV2GeoCache() bool {
	clearedIP := v2geoIPCache.clear()
	clearedSite := v2geoSiteCache.clear()
	return clearedIP || clearedSite
}

func (c *v2geoParsedCache[T]) clear() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	hadEntries := c.entries != nil
	c.key = v2geoFileKey{}
	c.entries = nil
	c.codes = nil
	return hadEntries
}

func loadV2GeoIP(path string) (map[string]*v2geoIP, []string, error) {
	return loadCachedV2Geo(path, &v2geoIPCache, "geoip.dat", func(bs []byte) ([]*v2geoIP, error) {
		var list v2geoIPList
		if err := proto.Unmarshal(bs, &list); err != nil {
			return nil, err
		}
		return list.Entry, nil
	}, func(entry *v2geoIP) string {
		return entry.CountryCode
	})
}

func loadV2GeoSite(path string) (map[string]*v2geoSite, []string, error) {
	return loadCachedV2Geo(path, &v2geoSiteCache, "geosite.dat", func(bs []byte) ([]*v2geoSite, error) {
		var list v2geoSiteList
		if err := proto.Unmarshal(bs, &list); err != nil {
			return nil, err
		}
		return list.Entry, nil
	}, func(entry *v2geoSite) string {
		return entry.CountryCode
	})
}

func loadCachedV2Geo[T any](
	path string,
	cache *v2geoParsedCache[T],
	assetName string,
	unmarshal func([]byte) ([]*T, error),
	countryCode func(*T) string,
) (map[string]*T, []string, error) {
	stat, err := os.Stat(path)
	if err != nil {
		return nil, nil, err
	}
	useCache := v2geoCacheUses.Load() > 0
	key := v2geoFileKey{
		path:        path,
		size:        stat.Size(),
		modTimeNano: stat.ModTime().UnixNano(),
	}

	if useCache {
		cache.mu.Lock()
		if cache.entries != nil && cache.key == key {
			codes := slices.Clone(cache.codes)
			entries := cache.entries
			cache.mu.Unlock()
			return entries, codes, nil
		}
		cache.mu.Unlock()
	}

	bs, err := os.ReadFile(path)
	if err != nil {
		return nil, nil, err
	}
	list, err := unmarshal(bs)
	if err != nil {
		return nil, nil, err
	}
	entries := make(map[string]*T, len(list))
	for _, entry := range list {
		if entry == nil {
			continue
		}
		code := strings.ToLower(strings.TrimSpace(countryCode(entry)))
		if code == "" {
			continue
		}
		entries[code] = entry
	}
	if len(entries) == 0 {
		return nil, nil, fmt.Errorf("%s has no entries", assetName)
	}
	codes := slices.Sorted(maps.Keys(entries))
	if useCache && v2geoCacheUses.Load() > 0 {
		cache.mu.Lock()
		cache.key = key
		cache.entries = entries
		cache.codes = codes
		cache.mu.Unlock()
	}
	return entries, codes, nil
}

func cidrString(cidr *v2geoCIDR) (string, error) {
	if cidr == nil {
		return "", fmt.Errorf("empty CIDR entry")
	}
	ip := net.IP(cidr.IP)
	switch len(cidr.IP) {
	case net.IPv4len:
		if cidr.Prefix > 32 {
			return "", fmt.Errorf("invalid IPv4 prefix: %d", cidr.Prefix)
		}
	case net.IPv6len:
		if cidr.Prefix > 128 {
			return "", fmt.Errorf("invalid IPv6 prefix: %d", cidr.Prefix)
		}
	default:
		return "", fmt.Errorf("invalid IP length: %d", len(cidr.IP))
	}
	return fmt.Sprintf("%s/%d", ip.String(), cidr.Prefix), nil
}
