package libcore

import (
	"testing"

	json "github.com/sagernet/sing/common/json"
	"github.com/stretchr/testify/require"
)

func TestNormalizeConfigReturnsValidConfigUnchanged(t *testing.T) {
	config := `{
  "outbounds": [
    { "type": "direct", "tag": "direct" }
  ],
  "route": { "final": "direct" }
}`

	result := NormalizeConfig(config)

	require.Equal(t, config, result.Result)
	require.Zero(t, result.GetViolationCount())
}

func TestNormalizeConfigPrunesInvalidSelectorMember(t *testing.T) {
	config := `{
  "outbounds": [
    { "type": "selector", "tag": "proxy", "outbounds": ["good", "bad"], "default": "bad" },
    { "type": "direct", "tag": "good" },
    { "type": "does-not-exist", "tag": "bad" }
  ],
  "route": { "final": "proxy" }
}`

	result := NormalizeConfig(config)

	require.NotEmpty(t, result.Result)
	require.EqualValues(t, 1, result.GetViolationCount())
	root := decodeNormalizedRoot(t, result.Result)
	outbounds := decodeNormalizedOutbounds(t, root)
	require.Len(t, outbounds, 2)
	require.Equal(t, []string{"good"}, decodeStringList(t, outbounds[0]["outbounds"]))
	require.NotContains(t, outbounds[0], "default")
}

func TestNormalizeConfigPrunesMultipleURLTestMembers(t *testing.T) {
	config := `{
  "outbounds": [
    { "type": "urltest", "tag": "proxy", "outbounds": ["bad-one", "good", "bad-two"] },
    { "type": "unknown-one", "tag": "bad-one" },
    { "type": "direct", "tag": "good" },
    { "type": "unknown-two", "tag": "bad-two" }
  ],
  "route": { "final": "proxy" }
}`

	result := NormalizeConfig(config)

	require.NotEmpty(t, result.Result)
	require.EqualValues(t, 2, result.GetViolationCount())
	root := decodeNormalizedRoot(t, result.Result)
	outbounds := decodeNormalizedOutbounds(t, root)
	require.Len(t, outbounds, 2)
	require.Equal(t, []string{"good"}, decodeStringList(t, outbounds[0]["outbounds"]))
}

func TestNormalizeConfigDropsEmptyNestedGroups(t *testing.T) {
	config := `{
  "outbounds": [
    { "type": "selector", "tag": "outer", "outbounds": ["empty", "direct"] },
    { "type": "urltest", "tag": "empty", "outbounds": ["bad"] },
    { "type": "unknown", "tag": "bad" },
    { "type": "direct", "tag": "direct" }
  ],
  "route": { "final": "outer" }
}`

	result := NormalizeConfig(config)

	require.NotEmpty(t, result.Result)
	require.EqualValues(t, 2, result.GetViolationCount())
	root := decodeNormalizedRoot(t, result.Result)
	outbounds := decodeNormalizedOutbounds(t, root)
	require.Len(t, outbounds, 2)
	require.Equal(t, []string{"direct"}, decodeStringList(t, outbounds[0]["outbounds"]))
}

func TestNormalizeConfigFailsWhenRouteFinalIsRemoved(t *testing.T) {
	config := `{
  "outbounds": [
    { "type": "selector", "tag": "proxy", "outbounds": ["bad"] },
    { "type": "unknown", "tag": "bad" }
  ],
  "route": { "final": "proxy" }
}`

	result := NormalizeConfig(config)

	require.Empty(t, result.Result)
	require.Zero(t, result.GetViolationCount())
}

func TestNormalizeConfigFailsWhenEntireProxyChainIsRemoved(t *testing.T) {
	config := `{
  "outbounds": [
    { "type": "does-not-exist", "tag": "chain-head", "detour": "chain-tail" },
    { "type": "does-not-exist", "tag": "chain-tail" }
  ],
  "route": { "final": "chain-head" }
}`

	result := NormalizeConfig(config)

	require.Empty(t, result.Result)
	require.Zero(t, result.GetViolationCount())
}

func TestNormalizeConfigFailsWhenCascadeRemovesEntireProfileCollection(t *testing.T) {
	config := `{
  "outbounds": [
    { "type": "selector", "tag": "unused", "outbounds": ["dependent"] },
    { "type": "unknown", "tag": "bad" },
    { "type": "socks", "tag": "dependent", "server": "127.0.0.1", "server_port": 1080, "detour": "bad" },
    { "type": "direct", "tag": "direct" }
  ],
  "route": { "final": "direct" }
}`

	result := NormalizeConfig(config)

	require.Empty(t, result.Result)
	require.Zero(t, result.GetViolationCount())
}

func TestNormalizeConfigRemovesInvalidChainFromMixedProxySet(t *testing.T) {
	config := `{
  "outbounds": [
    { "type": "selector", "tag": "proxy", "outbounds": ["dependent", "good"] },
    { "type": "unknown", "tag": "bad" },
    { "type": "socks", "tag": "dependent", "server": "127.0.0.1", "server_port": 1080, "detour": "bad" },
    { "type": "direct", "tag": "good" }
  ],
  "route": { "final": "proxy" }
}`

	result := NormalizeConfig(config)

	require.NotEmpty(t, result.Result)
	require.EqualValues(t, 2, result.GetViolationCount())
	root := decodeNormalizedRoot(t, result.Result)
	outbounds := decodeNormalizedOutbounds(t, root)
	require.Len(t, outbounds, 2)
	require.Equal(t, []string{"good"}, decodeStringList(t, outbounds[0]["outbounds"]))
}

func TestNormalizeConfigFailsWhenDNSServerDetourIsRemoved(t *testing.T) {
	config := `{
  "dns": {
    "servers": [
      { "type": "https", "tag": "dns-remote", "server": "1.1.1.1", "detour": "🇳🇱 Nederland" }
    ],
    "final": "dns-remote"
  },
  "outbounds": [
    { "type": "selector", "tag": "proxy-set", "outbounds": ["🇳🇱 Nederland", "direct"] },
    { "type": "does-not-exist", "tag": "🇳🇱 Nederland" },
    { "type": "direct", "tag": "direct" }
  ],
  "route": { "final": "direct" }
}`

	result := NormalizeConfig(config)

	require.Empty(t, result.Result)
	require.Zero(t, result.GetViolationCount())
}

func TestNormalizeConfigFailsWhenNestedDNSServerDetourIsRemoved(t *testing.T) {
	config := `{
  "dns": {
    "servers": [
      {
        "type": "balancer",
        "tag": "dns-remote",
        "servers": [
          { "type": "https", "tag": "dns-remote-1", "server": "1.1.1.1", "detour": "bad" }
        ]
      }
    ],
    "final": "dns-remote"
  },
  "outbounds": [
    { "type": "selector", "tag": "proxy-set", "outbounds": ["bad", "direct"] },
    { "type": "does-not-exist", "tag": "bad" },
    { "type": "direct", "tag": "direct" }
  ],
  "route": { "final": "direct" }
}`

	result := NormalizeConfig(config)

	require.Empty(t, result.Result)
	require.Zero(t, result.GetViolationCount())
}

func TestNormalizeConfigPreservesUTF8DNSServerDetour(t *testing.T) {
	config := `{
  "dns": {
    "servers": [
      { "type": "https", "tag": "dns-remote", "server": "1.1.1.1", "detour": "🇳🇱 Nederland 🚀" }
    ],
    "final": "dns-remote"
  },
  "outbounds": [
    { "type": "direct", "tag": "🇳🇱 Nederland 🚀" }
  ],
  "route": { "final": "🇳🇱 Nederland 🚀" }
}`

	result := NormalizeConfig(config)

	require.Equal(t, config, result.Result)
	require.Zero(t, result.GetViolationCount())
}

func TestNormalizeConfigPreservesImplicitOutboundTags(t *testing.T) {
	config := `{
  "outbounds": [
    { "type": "selector", "tag": "proxy", "outbounds": ["2", "bad"] },
    { "type": "unknown", "tag": "bad" },
    { "type": "direct" }
  ],
  "route": { "final": "proxy" }
}`

	result := NormalizeConfig(config)

	require.NotEmpty(t, result.Result)
	root := decodeNormalizedRoot(t, result.Result)
	outbounds := decodeNormalizedOutbounds(t, root)
	require.Len(t, outbounds, 2)
	require.Equal(t, "2", decodeString(t, outbounds[1]["tag"]))
}

func TestNormalizeConfigDoesNotRemoveFirstPartyMalformedOutbounds(t *testing.T) {
	config := `{
  "outbounds": [
    { "type": "selector", "tag": "proxy", "outbounds": ["good"] },
    { "type": "unknown", "tag": 123 },
    { "type": 456, "tag": "malformed-type" },
    { "type": "direct", "tag": "good" }
  ],
  "route": { "final": "proxy" }
}`

	result := NormalizeConfig(config)

	require.Empty(t, result.Result)
	require.Zero(t, result.GetViolationCount())
}

func TestNormalizeConfigDoesNotRemoveStandaloneInvalidOutbound(t *testing.T) {
	config := `{
  "outbounds": [
    { "type": "does-not-exist", "tag": "🇳🇱 Nederland 🚀" },
    { "type": "direct", "tag": "direct" }
  ],
  "route": { "final": "direct" }
}`

	result := NormalizeConfig(config)

	require.Empty(t, result.Result)
	require.Zero(t, result.GetViolationCount())
}

func TestNormalizeConfigFallsBackForUnrelatedErrors(t *testing.T) {
	config := `{
  "dns": {
    "servers": [
      { "type": "does-not-exist", "tag": "dns" }
    ]
  },
  "outbounds": [
    { "type": "direct", "tag": "direct" }
  ],
  "route": { "final": "direct" }
}`

	result := NormalizeConfig(config)

	require.Empty(t, result.Result)
	require.Zero(t, result.GetViolationCount())
}

func decodeNormalizedRoot(t *testing.T, config string) map[string]json.RawMessage {
	t.Helper()
	root, err := json.UnmarshalExtended[map[string]json.RawMessage]([]byte(config))
	require.NoError(t, err)
	return root
}

func decodeNormalizedOutbounds(t *testing.T, root map[string]json.RawMessage) []map[string]json.RawMessage {
	t.Helper()
	var outbounds []map[string]json.RawMessage
	require.NoError(t, json.Unmarshal(root["outbounds"], &outbounds))
	return outbounds
}

func decodeStringList(t *testing.T, content json.RawMessage) []string {
	t.Helper()
	var value []string
	require.NoError(t, json.Unmarshal(content, &value))
	return value
}

func decodeString(t *testing.T, content json.RawMessage) string {
	t.Helper()
	var value string
	require.NoError(t, json.Unmarshal(content, &value))
	return value
}
