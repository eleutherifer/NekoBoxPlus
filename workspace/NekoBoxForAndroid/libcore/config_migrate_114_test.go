package libcore

import (
	"testing"

	json "github.com/sagernet/sing/common/json"
	"github.com/stretchr/testify/require"
)

func TestMigrateConfig114DNSAndRuleSet(t *testing.T) {
	legacy := `{
  "dns": {
    "independent_cache": true,
    "servers": [{"tag":"dns-remote","address":"https://dns.example/dns-query","address_resolver":"dns-local"}]
  },
  "route": {
    "rule_set": [{"type":"remote","tag":"remote","url":"https://example/rules.srs","download_detour":"proxy"}]
  },
  "experimental": {"cache_file":{"enabled":true,"store_rdrc":true,"rdrc_timeout":"7d"}}
}`

	result, violations, ok := migrateConfig114(legacy)
	require.True(t, ok)
	require.Len(t, violations, 4)

	root, err := json.UnmarshalExtended[map[string]any]([]byte(result))
	require.NoError(t, err)
	dns := root["dns"].(map[string]any)
	require.NotContains(t, dns, "independent_cache")
	server := dns["servers"].([]any)[0].(map[string]any)
	require.Equal(t, "https", server["type"])
	require.Equal(t, "dns.example", server["server"])
	require.NotContains(t, server, "address")

	route := root["route"].(map[string]any)
	ruleSet := route["rule_set"].([]any)[0].(map[string]any)
	require.NotContains(t, ruleSet, "download_detour")
	require.NotEmpty(t, ruleSet["http_client"])
	require.Len(t, root["http_clients"], 1)

	cache := root["experimental"].(map[string]any)["cache_file"].(map[string]any)
	require.Equal(t, true, cache["store_dns"])
	require.NotContains(t, cache, "store_rdrc")
}
