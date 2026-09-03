//go:build !re2_cgo && !re2_wazero && (amd64 || arm64 || loong64 || mips64 || mips64le || ppc64 || ppc64le || riscv64 || s390x)

package regexpr

import (
	"io"
	"sync"
	"unsafe"
)

var (
	loggingMu           sync.Mutex
	loggingConfigured   bool
	loggingPreviousDest io.Writer
)

func configureLogging(suppress bool) {
	loggingMu.Lock()
	defer loggingMu.Unlock()

	if suppress {
		if loggingConfigured {
			return
		}
		module := goRE2GetChildModule()
		goRE2PutChildModule(module)
		if goRE2HostWASI != nil {
			hostWASI := (*goRE2HostWASIHeader)(goRE2HostWASI)
			loggingPreviousDest = hostWASI.stderr
			hostWASI.stderr = io.Discard
			loggingConfigured = true
		}
		return
	}

	if loggingConfigured && goRE2HostWASI != nil {
		(*goRE2HostWASIHeader)(goRE2HostWASI).stderr = loggingPreviousDest
	}
	loggingPreviousDest = nil
	loggingConfigured = false
}

type goRE2HostWASIHeader struct {
	stdout io.Writer
	stderr io.Writer
}

// These private symbols belong to github.com/wasilibs/go-re2 v1.12.0's
// default wasm2go backend. They let Adblock silence RE2's WASI stderr without
// redirecting the whole process stderr file descriptor.
//
//go:linkname goRE2HostWASI github.com/wasilibs/go-re2/internal.hostWASI
var goRE2HostWASI unsafe.Pointer

//go:linkname goRE2GetChildModule github.com/wasilibs/go-re2/internal.getChildModule
func goRE2GetChildModule() unsafe.Pointer

//go:linkname goRE2PutChildModule github.com/wasilibs/go-re2/internal.putChildModule
func goRE2PutChildModule(unsafe.Pointer)
