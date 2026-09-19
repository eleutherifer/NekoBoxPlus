package option

import (
	"bytes"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/common/json/badoption"
)

type AdblockOptions struct {
	Enabled          bool                `json:"enabled,omitempty"`           // if false, the adblock module is completely ignored
	Filtering        AdblockFiltering    `json:"filtering,omitempty"`         // filtering options for adblock engine
	TLS              *AdblockTLSOptions  `json:"tls,omitempty"`               // TLS options for adblock engine, if nil - https is NOT filtered
	Filters          *AdblockFilters     `json:"filters,omitempty"`           // filters for adblock engine
	Constraints      AdblockConstraints  `json:"constraints,omitempty"`       // constraints for adblock engine, if empty - no constraints
	DatabasePath     string              `json:"database_path,omitempty"`     // bolt db
	AdblockResources string              `json:"adblock_resources,omitempty"` // adblock-resources repository resources, if empty - no resources
	Tags             []string            `json:"tags,omitempty"`              // runtime tags enabled for $tag rules
	Environment      *AdblockEnvironment `json:"environment,omitempty"`       // filter preprocessor environment; defaults to Firefox-compatible
}

type AdblockEnvironment struct {
	Chromium       bool `json:"chromium,omitempty"`
	Firefox        bool `json:"firefox,omitempty"`
	Legacy         bool `json:"legacy,omitempty"`
	Mobile         bool `json:"mobile,omitempty"`
	MV3            bool `json:"mv3,omitempty"`
	Safari         bool `json:"safari,omitempty"`
	Edge           bool `json:"edge,omitempty"`
	Opera          bool `json:"opera,omitempty"`
	ABP            bool `json:"abp,omitempty"`
	UBO            bool `json:"ubo,omitempty"`
	UBlock         bool `json:"ublock,omitempty"`
	UBOL           bool `json:"ubol,omitempty"`
	DevBuild       bool `json:"dev_build,omitempty"`
	HTMLFiltering  bool `json:"html_filtering,omitempty"`
	IPAddress      bool `json:"ipaddress,omitempty"`
	UserStylesheet bool `json:"user_stylesheet,omitempty"`
	AdGuard        bool `json:"adguard,omitempty"`
}

type AdblockFiltering struct {
	Mode                        AdblockMode                `json:"mode,omitempty"`                          // adblock mode, default to "default"
	DNS                         bool                       `json:"dns,omitempty"`                           // whether to filter DNS requests
	HTTP                        bool                       `json:"http,omitempty"`                          // whether to filter HTTP requests
	HTTPS                       bool                       `json:"https,omitempty"`                         // whether to filter HTTPS requests
	QUIC                        bool                       `json:"quic,omitempty"`                          // whether to filter QUIC requests
	CNAMEUncloaking             bool                       `json:"cname_uncloaking,omitempty"`              // whether to perform CNAME uncloaking for DNS filtering
	DNSBlockMode                AdblockDNSBlockMode        `json:"dns_block_mode,omitempty"`                // DNS block response shape
	DNSBlockTTL                 badoption.Duration         `json:"dns_block_ttl,omitempty"`                 // DNS block response TTL
	CNAMEInfrastructureSuffixes badoption.Listable[string] `json:"cname_infrastructure_suffixes,omitempty"` // extra CNAME uncloaking infrastructure suffixes
	ReplaceMaxBody              int64                      `json:"replace_max_body,omitempty"`              // maximum decoded body size for $replace
}

func (o AdblockOptions) FilterDNS() bool {
	return o.Filtering.DNS
}

func (o AdblockOptions) FilterHTTP() bool {
	return o.Filtering.HTTP
}

func (o AdblockOptions) FilterHTTPS() bool {
	return o.Filtering.HTTPS && o.TLS != nil && o.TLS.Enabled
}

func (o AdblockOptions) FilterQUIC() bool {
	return o.Filtering.QUIC && o.TLS != nil && o.TLS.Enabled
}

type AdblockConstraint struct {
	SourceIPIsNotLoopback bool                       `json:"source_ip_is_not_loopback,omitempty"`
	Inbound               badoption.Listable[string] `json:"inbound,omitempty"`
	ProcessName           badoption.Listable[string] `json:"process_name,omitempty"`
	ProcessPath           badoption.Listable[string] `json:"process_path,omitempty"`
	ProcessPathRegex      badoption.Listable[string] `json:"process_path_regex,omitempty"`
	PackageName           badoption.Listable[string] `json:"package_name,omitempty"`
	PackageNameExclude    badoption.Listable[string] `json:"package_name_exclude,omitempty"`
	PackageNameRegex      badoption.Listable[string] `json:"package_name_regex,omitempty"`
}

type AdblockConstraints []AdblockConstraint

func (a *AdblockConstraints) UnmarshalJSON(data []byte) error {
	data = bytes.TrimSpace(data)
	if len(data) == 0 {
		return E.New("empty adblock constraints")
	}
	if bytes.Equal(data, []byte("null")) {
		*a = nil
		return nil
	}

	type constraints AdblockConstraints
	if data[0] == '[' {
		return json.Unmarshal(data, (*constraints)(a))
	}
	if data[0] == '{' {
		var constraint AdblockConstraint
		if err := json.Unmarshal(data, &constraint); err != nil {
			return err
		}
		*a = AdblockConstraints{constraint}
		return nil
	}
	return E.New("adblock constraints must be an object or an array")
}

func (a AdblockConstraints) HasProcessRules() bool {
	if len(a) == 0 {
		return false
	}

	for _, rule := range a {
		if len(rule.ProcessName) > 0 ||
			len(rule.ProcessPath) > 0 ||
			len(rule.ProcessPathRegex) > 0 ||
			len(rule.PackageName) > 0 ||
			len(rule.PackageNameExclude) > 0 ||
			len(rule.PackageNameRegex) > 0 {
			return true
		}
	}

	return false
}

type AdblockFilters struct {
	FilterLists []AdblockFilterList `json:"lists,omitempty"` // those files should be read & passed to filter_set_add_filters
	Rules       []string            `json:"rules,omitempty"` // those filters should be passed to filter_set_add_filters directly
}

type AdblockFilterList struct {
	adblockFilterListBase
}

type adblockFilterListBase struct {
	URL            string              `json:"url"`
	Format         AdblockFilterFormat `json:"format,omitempty"`
	DownloadDetour string              `json:"download_detour,omitempty"`
	UpdateInterval badoption.Duration  `json:"update_interval,omitempty"`
	Trust          bool                `json:"trust,omitempty"`
}

type AdblockFilterFormat string

const (
	AdblockFilterFormatStandard AdblockFilterFormat = "standard"
	AdblockFilterFormatHosts    AdblockFilterFormat = "hosts"
)

var validAdblockFilterFormats = map[AdblockFilterFormat]struct{}{
	AdblockFilterFormatStandard: {},
	AdblockFilterFormatHosts:    {},
}

func (f *AdblockFilterFormat) UnmarshalJSON(data []byte) error {
	var value string
	if err := json.Unmarshal(data, &value); err != nil {
		return err
	}
	format := AdblockFilterFormat(value)
	if _, ok := validAdblockFilterFormats[format]; !ok {
		*f = AdblockFilterFormatStandard
		return nil
	}
	*f = format
	return nil
}

func (o *AdblockFilterList) UnmarshalJSON(data []byte) error {
	if len(data) > 0 && data[0] == '"' {
		var url string
		if err := json.Unmarshal(data, &url); err != nil {
			return err
		}
		o.URL = url
		return nil
	}
	var base adblockFilterListBase
	if err := json.Unmarshal(data, &base); err != nil {
		return err
	}
	o.adblockFilterListBase = base
	return nil
}

type AdblockTLSOptions struct {
	Enabled     bool    `json:"enabled,omitempty"`
	Certificate string  `json:"certificate,omitempty"`
	Key         string  `json:"key,omitempty"`
	SkipEV      bool    `json:"skip_ev,omitempty"`
	UTLS        *string `json:"utls,omitempty"`
	Cronet      bool    `json:"cronet,omitempty"`
}

type AdblockMode string

const (
	AdblockModeDefault       AdblockMode = "default"
	AdblockModeEmptyResponse AdblockMode = "empty_response"
)

type AdblockDNSBlockMode string

const (
	AdblockDNSBlockModeZeroIP   AdblockDNSBlockMode = "zero_ip"
	AdblockDNSBlockModeNXDOMAIN AdblockDNSBlockMode = "nxdomain"
)

var validAdblockModes = map[AdblockMode]struct{}{
	AdblockModeDefault:       {},
	AdblockModeEmptyResponse: {},
}

func (m *AdblockMode) UnmarshalJSON(data []byte) error {
	var value string
	if err := json.Unmarshal(data, &value); err != nil {
		return err
	}
	mode := AdblockMode(value)
	if _, ok := validAdblockModes[mode]; !ok {
		*m = AdblockModeDefault
		return nil
	}
	*m = mode
	return nil
}

var validAdblockDNSBlockModes = map[AdblockDNSBlockMode]struct{}{
	AdblockDNSBlockModeZeroIP:   {},
	AdblockDNSBlockModeNXDOMAIN: {},
}

func (m *AdblockDNSBlockMode) UnmarshalJSON(data []byte) error {
	var value string
	if err := json.Unmarshal(data, &value); err != nil {
		return err
	}
	mode := AdblockDNSBlockMode(value)
	if _, ok := validAdblockDNSBlockModes[mode]; !ok {
		*m = AdblockDNSBlockModeZeroIP
		return nil
	}
	*m = mode
	return nil
}

func (f AdblockFiltering) DNSBlockTTLValue() uint32 {
	ttl := time.Duration(f.DNSBlockTTL)
	if ttl <= 0 {
		return 60
	}
	return uint32(ttl / time.Second)
}

func (f AdblockFiltering) ReplaceMaxBodyValue() int64 {
	if f.ReplaceMaxBody <= 0 {
		return 16 << 20
	}
	return f.ReplaceMaxBody
}

func (o AdblockOptions) HasFilters() bool {
	if o.Filters == nil {
		return false
	}
	for _, rule := range o.Filters.Rules {
		if rule != "" {
			return true
		}
	}
	for _, list := range o.Filters.FilterLists {
		if list.URL != "" {
			return true
		}
	}
	return false
}

func (o AdblockOptions) Validate() error {
	if !o.Enabled {
		return nil
	}
	if o.DatabasePath == "" {
		return E.New("missing database_path")
	}
	if o.TLS != nil && o.TLS.Enabled {
		if o.TLS.Certificate == "" {
			return E.New("missing adblock.tls.certificate")
		}
		if o.TLS.Key == "" {
			return E.New("missing adblock.tls.key")
		}
	}
	return nil
}
