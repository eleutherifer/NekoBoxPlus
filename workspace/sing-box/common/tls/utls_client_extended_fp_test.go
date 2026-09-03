//go:build with_utls

package tls

import (
	"reflect"
	"testing"

	utls "github.com/metacubex/utls"
)

func TestUTLSClientHelloIDExtendedAutoAliases(t *testing.T) {
	testCases := []struct {
		name     string
		expected utls.ClientHelloID
	}{
		{"edge_auto", utls.HelloEdge_Auto},
		{"HelloEdge_Auto", utls.HelloEdge_Auto},
		{"360_AUTO", utls.Hello360_Auto},
		{"Hello360_Auto", utls.Hello360_Auto},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			actual := uTLSClientHelloIDExtended(testCase.name)
			if actual == nil {
				t.Fatal("expected a fingerprint")
			}
			if !reflect.DeepEqual(*actual, testCase.expected) {
				t.Fatalf("unexpected fingerprint: %#v", *actual)
			}
		})
	}

	if actual := uTLSClientHelloIDExtended("unsupported"); actual != nil {
		t.Fatalf("expected an unsupported fingerprint to return nil: %#v", *actual)
	}
}
