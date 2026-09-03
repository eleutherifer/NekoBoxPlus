package libcore

import (
	"math"
	"runtime"
	"runtime/debug"
)

const minMemoryLimit int64 = 45 * 1024 * 1024

func EnableMemoryLimit(limit int64) {
	if limit <= 0 {
		debug.SetGCPercent(100)
		debug.SetMemoryLimit(math.MaxInt64)

		return
	}

	if limit < minMemoryLimit {
		limit = minMemoryLimit
	}

	debug.SetGCPercent(10)
	debug.SetMemoryLimit(limit)
}

func ForceGc() {
	go debug.FreeOSMemory()
}

func PerformLibcoreGCSweep() {
	runtime.GC()
	debug.FreeOSMemory()
}
