//go:build !re2_cgo && !re2_wazero && (amd64 || arm64 || loong64 || mips64 || mips64le || ppc64 || ppc64le || riscv64 || s390x)

package regexpr

import (
	"io"
	"os"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/log"
)

func TestSuppressesRE2Stderr(t *testing.T) {
	SetLogLevel(logLevelNone)
	defer SetLogLevel(log.LevelTrace)

	readPipe, writePipe, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer readPipe.Close()

	oldStderr := os.Stderr
	os.Stderr = writePipe
	defer func() {
		os.Stderr = oldStderr
	}()
	_, compileErr := Compile("(")
	os.Stderr = oldStderr

	if err = writePipe.Close(); err != nil {
		t.Fatal(err)
	}
	output, err := io.ReadAll(readPipe)
	if err != nil {
		t.Fatal(err)
	}
	if compileErr == nil {
		t.Fatal("expected invalid regexp error")
	}
	stderr := string(output)
	if strings.Contains(stderr, "All log messages before absl::InitializeLog()") {
		t.Fatalf("unexpected absl initialization warning on stderr: %q", stderr)
	}
	if strings.Contains(stderr, "Error parsing") {
		t.Fatalf("unexpected re2 parse error on stderr: %q", stderr)
	}
}
