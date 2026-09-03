package option

import (
	"testing"

	"github.com/sagernet/sing/common/json"
)

func TestAwgUint32RangeJSON(t *testing.T) {
	tests := []struct {
		name    string
		content string
		want    AwgUint32Range
	}{
		{name: "number", content: `25`, want: "25"},
		{name: "string value", content: `"25"`, want: "25"},
		{name: "range", content: `"20-30"`, want: "20-30"},
		{name: "maximum", content: `4294967295`, want: "4294967295"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			var got AwgUint32Range
			if err := json.Unmarshal([]byte(test.content), &got); err != nil {
				t.Fatal(err)
			}
			if got != test.want {
				t.Fatalf("range = %q, want %q", got, test.want)
			}
		})
	}
}

func TestAwgUint32RangeRejectsInvalidValues(t *testing.T) {
	for _, content := range []string{`-1`, `4294967296`, `"30-20"`, `"1-2-3"`, `"invalid"`} {
		t.Run(content, func(t *testing.T) {
			var value AwgUint32Range
			if err := json.Unmarshal([]byte(content), &value); err == nil {
				t.Fatal("expected error")
			}
		})
	}
}

func TestAwgPersistentKeepaliveCompatibility(t *testing.T) {
	var peer AwgPeerOptions
	if err := json.Unmarshal([]byte(`{"persistent_keepalive_interval":25}`), &peer); err != nil {
		t.Fatal(err)
	}
	if peer.PersistentKeepaliveInterval != "25" {
		t.Fatalf("persistent keepalive = %q", peer.PersistentKeepaliveInterval)
	}
}
