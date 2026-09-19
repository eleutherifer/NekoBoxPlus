package option

import (
	"github.com/goccy/go-json"
	"strings"
	"testing"

	Xbadoption "github.com/sagernet/sing-box/common/xray/json/badoption"
)

func TestV2RayXHTTPOptionsDefaults(t *testing.T) {
	var options V2RayXHTTPOptions
	if err := json.Unmarshal([]byte(`{"mode":"packet-up","uplink_data_placement":"header","session_id_placement":"header","seq_placement":"query","uplink_http_method":"post"}`), &options); err != nil {
		t.Fatal(err)
	}
	if options.Mode != "packet-up" {
		t.Fatalf("mode = %q", options.Mode)
	}
	if options.XPaddingBytes.From != 100 || options.XPaddingBytes.To != 1000 {
		t.Fatalf("x padding = %+v", options.XPaddingBytes)
	}
	if options.XPaddingKey != "x_padding" || options.XPaddingHeader != "X-Padding" || options.XPaddingPlacement != PlacementQueryInHeader {
		t.Fatalf("padding defaults not applied: %+v", options.V2RayXHTTPBaseOptions)
	}
	if options.XPaddingMethod != "repeat-x" {
		t.Fatalf("padding method = %q", options.XPaddingMethod)
	}
	if options.UplinkHTTPMethod != "POST" {
		t.Fatalf("uplink method = %q", options.UplinkHTTPMethod)
	}
	if options.SessionIDKey != "X-Session" || options.SeqKey != "x_seq" || options.UplinkDataKey != "X-Data" {
		t.Fatalf("metadata/data keys not defaulted: session=%q seq=%q data=%q", options.SessionIDKey, options.SeqKey, options.UplinkDataKey)
	}
	if options.UplinkChunkSize.From != 3000 || options.UplinkChunkSize.To != 4000 {
		t.Fatalf("uplink chunk range = %+v", options.UplinkChunkSize)
	}
	if ua := options.GetRequestHeader().Get("User-Agent"); !strings.Contains(ua, "Chrome/") {
		t.Fatalf("missing Chrome user-agent: %q", ua)
	}
}

func TestV2RayXHTTPNormalizedPath(t *testing.T) {
	tests := []struct {
		name               string
		path               string
		sessionPlacement   string
		sequencePlacement  string
		expectedNormalized string
	}{
		{
			name:               "default placement adds trailing slash",
			path:               "/stream",
			expectedNormalized: "/stream/",
		},
		{
			name:               "query string is stripped",
			path:               "/?world",
			expectedNormalized: "/",
		},
		{
			name:               "non-path placements omit trailing slash",
			path:               "/stream",
			sessionPlacement:   PlacementQuery,
			sequencePlacement:  PlacementQuery,
			expectedNormalized: "/stream",
		},
		{
			name:               "non-path placements preserve file-like path",
			path:               "/stream/filename.extension",
			sessionPlacement:   PlacementQuery,
			sequencePlacement:  PlacementHeader,
			expectedNormalized: "/stream/filename.extension",
		},
		{
			name:               "sequence in path adds trailing slash",
			path:               "/stream",
			sessionPlacement:   PlacementQuery,
			expectedNormalized: "/stream/",
		},
		{
			name:               "session in path adds trailing slash",
			path:               "/stream",
			sequencePlacement:  PlacementCookie,
			expectedNormalized: "/stream/",
		},
		{
			name:               "existing trailing slash is preserved",
			path:               "/stream/",
			sessionPlacement:   PlacementQuery,
			sequencePlacement:  PlacementQuery,
			expectedNormalized: "/stream/",
		},
		{
			name:               "root is unchanged",
			path:               "/",
			sessionPlacement:   PlacementQuery,
			sequencePlacement:  PlacementQuery,
			expectedNormalized: "/",
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			options := V2RayXHTTPBaseOptions{
				Path:               test.path,
				SessionIDPlacement: test.sessionPlacement,
				SeqPlacement:       test.sequencePlacement,
			}
			if normalized := options.GetNormalizedPath(); normalized != test.expectedNormalized {
				t.Fatalf("normalized path = %q, want %q", normalized, test.expectedNormalized)
			}
		})
	}
}

func TestV2RayXHTTPOptionsRejectsInvalidCombinations(t *testing.T) {
	tests := []string{
		`{"mode":"packet-up","headers":{"Host":"example.com"}}`,
		`{"mode":"stream-up","uplink_data_placement":"header"}`,
		`{"mode":"stream-one","uplink_http_method":"GET"}`,
		`{"mode":"packet-up","session_id_placement":"path","seq_placement":"header"}`,
		`{"mode":"packet-up","xmux":{"max_connections":1,"max_concurrency":1}}`,
	}
	for _, content := range tests {
		t.Run(content, func(t *testing.T) {
			var options V2RayXHTTPOptions
			if err := json.Unmarshal([]byte(content), &options); err == nil {
				t.Fatal("expected error")
			}
		})
	}
}

func TestV2RayXHTTPOptionsLegacySessionAliases(t *testing.T) {
	var options V2RayXHTTPOptions
	if err := json.Unmarshal([]byte(`{"mode":"packet-up","session_placement":"header","session_key":"X-Legacy-Session","seq_placement":"query"}`), &options); err != nil {
		t.Fatal(err)
	}
	if options.SessionIDPlacement != "" {
		t.Fatalf("session_id_placement = %q", options.SessionIDPlacement)
	}
	if options.SessionIDKey != "" {
		t.Fatalf("session_id_key = %q", options.SessionIDKey)
	}
	if options.SessionPlacement != PlacementHeader {
		t.Fatalf("session_placement = %q", options.SessionPlacement)
	}
	if options.SessionKey != "X-Legacy-Session" {
		t.Fatalf("session_key = %q", options.SessionKey)
	}
	if options.GetNormalizedSessionPlacement() != PlacementHeader {
		t.Fatalf("normalized session placement = %q", options.GetNormalizedSessionPlacement())
	}
	if options.GetNormalizedSessionKey() != "X-Legacy-Session" {
		t.Fatalf("normalized session key = %q", options.GetNormalizedSessionKey())
	}
}

func TestV2RayXHTTPOptionsSessionIDTable(t *testing.T) {
	var options V2RayXHTTPOptions
	if err := json.Unmarshal([]byte(`{"session_id_table":"Base62","session_id_length":"6"}`), &options); err != nil {
		t.Fatal(err)
	}
	if options.SessionIDTable != XHTTPSessionIDPredefinedTable["Base62"] {
		t.Fatalf("session_id_table = %q", options.SessionIDTable)
	}
	if options.SessionIDLength != (Xbadoption.Range{From: 6, To: 6}) {
		t.Fatalf("session_id_length = %+v", options.SessionIDLength)
	}
}

func TestV2RayXHTTPOptionsRejectsInvalidSessionIDTable(t *testing.T) {
	tests := []string{
		`{"session_id_table":"number","session_id_length":"3"}`,
		`{"session_id_table":"number","session_id_length":"0"}`,
		`{"session_id_table":"é","session_id_length":"32"}`,
	}
	for _, content := range tests {
		t.Run(content, func(t *testing.T) {
			var options V2RayXHTTPOptions
			if err := json.Unmarshal([]byte(content), &options); err == nil {
				t.Fatal("expected error")
			}
		})
	}
}

func TestV2RayXHTTPOptionsSupportsAutoUplinkDataPlacement(t *testing.T) {
	var options V2RayXHTTPOptions
	if err := json.Unmarshal([]byte(`{"mode":"packet-up","uplink_data_placement":"auto"}`), &options); err != nil {
		t.Fatal(err)
	}
	if options.GetNormalizedUplinkDataPlacement() != PlacementAuto {
		t.Fatalf("uplink_data_placement = %q", options.GetNormalizedUplinkDataPlacement())
	}
	if options.GetNormalizedUplinkDataKey() != "X-Data" {
		t.Fatalf("uplink_data_key = %q", options.GetNormalizedUplinkDataKey())
	}
}

func TestV2RayXHTTPOptionsLenientClientValues(t *testing.T) {
	var options V2RayXHTTPOptions
	if err := json.Unmarshal([]byte(`{
		"sc_max_buffered_posts": 999999,
		"xmux": {
			"max_concurrency": {"from": -5, "to": 5000},
			"c_max_reuse_times": {"from": -1, "to": 3}
		}
	}`), &options); err != nil {
		t.Fatal(err)
	}
	if options.GetNormalizedScMaxBufferedPosts() != 999999 {
		t.Fatalf("sc_max_buffered_posts = %d", options.GetNormalizedScMaxBufferedPosts())
	}
	if options.Xmux.MaxConcurrency != (Xbadoption.Range{From: 0, To: 5000}) {
		t.Fatalf("xmux max concurrency = %+v", options.Xmux.MaxConcurrency)
	}
	if options.Xmux.CMaxReuseTimes != (Xbadoption.Range{From: 0, To: 3}) {
		t.Fatalf("xmux c max reuse times = %+v", options.Xmux.CMaxReuseTimes)
	}
}

func TestV2RayXHTTPOptionsXmuxDefaults(t *testing.T) {
	var options V2RayXHTTPOptions
	if err := json.Unmarshal([]byte(`{"xmux":{}}`), &options); err != nil {
		t.Fatal(err)
	}
	if options.Mode != "auto" {
		t.Fatalf("mode = %q", options.Mode)
	}
	if options.Xmux.MaxConcurrency != (Xbadoption.Range{}) {
		t.Fatalf("xmux max concurrency = %+v", options.Xmux.MaxConcurrency)
	}
	if options.Xmux.MaxConnections != (Xbadoption.Range{From: 6, To: 6}) {
		t.Fatalf("xmux max connections = %+v", options.Xmux.MaxConnections)
	}
	if options.Xmux.HMaxRequestTimes != (Xbadoption.Range{From: 600, To: 900}) {
		t.Fatalf("xmux h max request times = %+v", options.Xmux.HMaxRequestTimes)
	}
	if options.Xmux.HMaxReusableSecs != (Xbadoption.Range{From: 1800, To: 3000}) {
		t.Fatalf("xmux h max reusable secs = %+v", options.Xmux.HMaxReusableSecs)
	}
}

func TestV2RayXHTTPXmuxZeroValueDefaults(t *testing.T) {
	var options V2RayXHTTPXmuxOptions
	if options.GetNormalizedMaxConcurrency() != (Xbadoption.Range{}) {
		t.Fatalf("xmux max concurrency = %+v", options.GetNormalizedMaxConcurrency())
	}
	if options.GetNormalizedMaxConnections() != (Xbadoption.Range{From: 6, To: 6}) {
		t.Fatalf("xmux max connections = %+v", options.GetNormalizedMaxConnections())
	}
	if options.GetNormalizedHMaxRequestTimes() != (Xbadoption.Range{From: 600, To: 900}) {
		t.Fatalf("xmux h max request times = %+v", options.GetNormalizedHMaxRequestTimes())
	}
	if options.GetNormalizedHMaxReusableSecs() != (Xbadoption.Range{From: 1800, To: 3000}) {
		t.Fatalf("xmux h max reusable secs = %+v", options.GetNormalizedHMaxReusableSecs())
	}
}

func TestV2RayXHTTPOptionsNonEmptyXmuxDoesNotReceiveEmptyDefaults(t *testing.T) {
	var options V2RayXHTTPOptions
	if err := json.Unmarshal([]byte(`{"xmux":{"max_concurrency":2}}`), &options); err != nil {
		t.Fatal(err)
	}
	if options.Xmux.MaxConcurrency != (Xbadoption.Range{From: 2, To: 2}) {
		t.Fatalf("xmux max concurrency = %+v", options.Xmux.MaxConcurrency)
	}
	if options.Xmux.HMaxRequestTimes != (Xbadoption.Range{}) {
		t.Fatalf("xmux h max request times = %+v", options.Xmux.HMaxRequestTimes)
	}
	if options.Xmux.HMaxReusableSecs != (Xbadoption.Range{}) {
		t.Fatalf("xmux h max reusable secs = %+v", options.Xmux.HMaxReusableSecs)
	}
}

func TestV2RayXHTTPOptionsDownloadModeValidation(t *testing.T) {
	var options V2RayXHTTPOptions
	if err := json.Unmarshal([]byte(`{"mode":"stream-one","download":{"host":"example.com"}}`), &options); err != nil {
		t.Fatal(err)
	}
	if options.Download != nil {
		t.Fatalf("download should be nil when mode is stream-one, got %+v", options.Download)
	}
	if err := json.Unmarshal([]byte(`{"mode":"packet-up","download":{"host":"example.com"}}`), &options); err != nil {
		t.Fatal(err)
	}
	if options.Download.Mode != "packet-up" {
		t.Fatalf("download mode = %q", options.Download.Mode)
	}
}
