package libcore

import (
	"runtime"
	"time"
	"unsafe"
)

var stackOverflowSink int

func TriggerManualCrash() {
	TriggerCrashGoPanic()
}

func TriggerCrashGoPanic() {
	panic("manual libcore Go panic")
}

func TriggerCrashGoNilPointer() {
	var value *int
	*value = 1
}

func TriggerCrashGoIndexOutOfRange() {
	values := []int{1}
	_ = values[1]
}

func TriggerCrashGoConcurrentMapWrite() {
	values := map[int]int{}
	for worker := range runtime.GOMAXPROCS(0) * 4 {
		go func(worker int) {
			for i := 0; ; i++ {
				values[i] = worker
			}
		}(worker)
	}
	for {
		time.Sleep(time.Hour)
	}
}

func TriggerCrashGoStackOverflow() {
	triggerCrashGoStackOverflow(1)
}

func triggerCrashGoStackOverflow(depth int) {
	stackOverflowSink += depth
	triggerCrashGoStackOverflow(depth + 1)
}

func TriggerCrashGoUnsafeMemoryWrite() {
	*(*byte)(unsafe.Pointer(uintptr(1))) = 1
}
