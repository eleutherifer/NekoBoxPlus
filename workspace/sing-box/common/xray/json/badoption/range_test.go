package badoption

import (
	"github.com/goccy/go-json"
	"testing"
)

func TestRangeJSONForms(t *testing.T) {
	tests := []struct {
		name    string
		content []byte
		want    Range
	}{
		{"string range", []byte(`"10-20"`), Range{From: 10, To: 20}},
		{"string value", []byte(`"10"`), Range{From: 10, To: 10}},
		{"number value", []byte(`10`), Range{From: 10, To: 10}},
		{"object value", []byte(`{"from":10,"to":20}`), Range{From: 10, To: 20}},
		{"empty string", []byte(`""`), Range{From: 0, To: 0}},
		{"bare range", []byte(`10-20`), Range{From: 10, To: 20}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			var got Range
			if err := got.UnmarshalJSON(test.content); err != nil {
				t.Fatal(err)
			}
			if got != test.want {
				t.Fatalf("got %+v, want %+v", got, test.want)
			}
		})
	}
}

func TestRangeMarshalSingleValue(t *testing.T) {
	content, err := json.Marshal(&Range{From: 10, To: 10})
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != `10` {
		t.Fatalf("got %s, want 10", content)
	}
}
