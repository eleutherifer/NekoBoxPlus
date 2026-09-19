package adblockrust

import (
	"strings"

	"github.com/sagernet/sing-box/common/adblock/adblockrust/resources"
)

type Engine interface {
	Check(url string, sourceURL string, requestType string, method RequestMethod) (bool, error)
	CheckDetailed(url string, sourceURL string, requestType string, method RequestMethod) (CheckResult, error)
	CheckDetailedNoFilter(url string, sourceURL string, requestType string, method RequestMethod) (CheckResult, error)
	CheckException(url string, sourceURL string, requestType string, method RequestMethod) (bool, error)
	CSPDirectives(url string, sourceURL string, requestType string, method RequestMethod) (string, error)
	URLCosmeticResources(url string) (CosmeticResources, error)
	HiddenClassIDSelectors(classes []string, ids []string, exceptions []string) ([]string, error)
	UseTags(tags []string) error
	EnableTags(tags []string) error
	DisableTags(tags []string) error
	TagExists(tag string) (bool, error)
	Close() error
}

type RequestMethod uint8

const (
	RequestMethodNone    RequestMethod = 0
	RequestMethodConnect RequestMethod = 1
	RequestMethodDelete  RequestMethod = 2
	RequestMethodGet     RequestMethod = 3
	RequestMethodHead    RequestMethod = 4
	RequestMethodOptions RequestMethod = 5
	RequestMethodPatch   RequestMethod = 6
	RequestMethodPost    RequestMethod = 7
	RequestMethodPut     RequestMethod = 8
	RequestMethodOther   RequestMethod = 9
)

func ParseRequestMethod(method string) RequestMethod {
	switch {
	case method == "":
		return RequestMethodNone
	case strings.EqualFold(method, "CONNECT"):
		return RequestMethodConnect
	case strings.EqualFold(method, "DELETE"):
		return RequestMethodDelete
	case strings.EqualFold(method, "GET"):
		return RequestMethodGet
	case strings.EqualFold(method, "HEAD"):
		return RequestMethodHead
	case strings.EqualFold(method, "OPTIONS"):
		return RequestMethodOptions
	case strings.EqualFold(method, "PATCH"):
		return RequestMethodPatch
	case strings.EqualFold(method, "POST"):
		return RequestMethodPost
	case strings.EqualFold(method, "PUT"):
		return RequestMethodPut
	default:
		return RequestMethodOther
	}
}

type CheckResult struct {
	Matched      bool   `json:"matched"`
	Important    bool   `json:"important"`
	Redirect     string `json:"redirect,omitempty"`
	RewrittenURL string `json:"rewritten_url,omitempty"`
	Exception    string `json:"exception,omitempty"`
	Filter       string `json:"filter,omitempty"`
}

type CosmeticResources struct {
	HideSelectors     []string
	ProceduralActions []string
	Exceptions        []string
	InjectedScript    string
	GenericHide       bool
}

type RuleSet struct {
	Rules       []string
	Format      RuleFormat
	Permissions uint8
}

type RuleFormat string

const (
	RuleFormatStandard RuleFormat = "standard"
	RuleFormatHosts    RuleFormat = "hosts"
)

func NewEngine(rules []string, adblockResources string) (Engine, error) {
	return NewEngineWithRuleSets([]RuleSet{{Rules: rules}}, adblockResources)
}

func NewEngineWithRuleSets(ruleSets []RuleSet, adblockResources string) (Engine, error) {
	if adblockResources == "" {
		bundledResources, err := resources.GetBundledAssets("")
		if err != nil {
			return nil, err
		}
		adblockResources = bundledResources
	}
	return newEngine(ruleSets, adblockResources)
}
