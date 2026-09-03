package byedpi

import (
	"reflect"
	"testing"
)

func TestSanitizeArgsDropsEmbeddedControlAndDebugArgs(t *testing.T) {
	args := []string{
		"--ip", "0.0.0.0",
		"--port", "1080",
		"--protect-path", "/tmp/protect",
		"-D",
		"-x", "2",
		"--debug", "1",
		"--split", "1",
		"--disorder", "3",
		"--tlsrec", "1+s",
	}
	want := []string{
		"--split", "1",
		"--disorder", "3",
		"--tlsrec", "1+s",
	}

	if got := sanitizeArgs(args); !reflect.DeepEqual(got, want) {
		t.Fatalf("sanitizeArgs() = %#v, want %#v", got, want)
	}
}
