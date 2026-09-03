package tf

import "testing"

func TestFragmentServerNameIndexes(t *testing.T) {
	for _, test := range []struct {
		name       string
		serverName string
		want       bool
	}{
		{name: "leading dot", serverName: ".sslip.io", want: true},
		{name: "repeated dot", serverName: "example..com", want: true},
		{name: "only dots", serverName: "..."},
		{name: "empty"},
	} {
		t.Run(test.name, func(t *testing.T) {
			indexes := fragmentServerNameIndexes(test.serverName, 100)
			if (len(indexes) > 0) != test.want {
				t.Fatalf("unexpected fragmentation indexes: %v", indexes)
			}
			for _, index := range indexes {
				if index < 100 || index >= 100+len(test.serverName) {
					t.Fatalf("fragmentation index %d is outside server name", index)
				}
			}
		})
	}
}
