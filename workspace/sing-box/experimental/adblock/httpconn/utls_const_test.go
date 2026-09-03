//go:build with_adblock && with_utls

package httpconn

import (
	"reflect"
	"testing"

	utls "github.com/metacubex/utls"
	"github.com/sagernet/sing-box/experimental/adblock/consts"
)

func TestExtendedUTLSFingerprints(t *testing.T) {
	testCases := []struct {
		name         string
		expectedID   consts.UTLSFingerprintID
		expectedUTLS utls.ClientHelloID
	}{
		{"Golang", consts.Golang, utls.HelloGolang},
		{" custom ", consts.Custom, utls.HelloCustom},
		{"RandomizedALPN", consts.RandomizedALPN, utls.HelloRandomizedALPN},
		{"randomizednoalpn", consts.RandomizedNoALPN, utls.HelloRandomizedNoALPN},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			fingerprintID, err := UTLSFingerprintIDFromString(testCase.name)
			if err != nil {
				t.Fatal(err)
			}
			if fingerprintID != testCase.expectedID {
				t.Fatalf("unexpected fingerprint ID: %d", fingerprintID)
			}

			fingerprint, err := uTLSClientHelloID(fingerprintID)
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(fingerprint, testCase.expectedUTLS) {
				t.Fatalf("unexpected fingerprint: %#v", fingerprint)
			}
		})
	}

	if _, err := UTLSFingerprintIDFromString("unsupported"); err == nil {
		t.Fatal("expected an unsupported fingerprint error")
	}
}
