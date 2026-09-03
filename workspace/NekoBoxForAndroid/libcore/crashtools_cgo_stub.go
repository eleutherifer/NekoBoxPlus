//go:build !android || !cgo

package libcore

func TriggerCrashNativeAbort() {
	panic("native abort crash is only available on Android CGo builds")
}

func TriggerCrashNativeSigsegv() {
	panic("native SIGSEGV crash is only available on Android CGo builds")
}

func TriggerCrashNativeTrap() {
	panic("native trap crash is only available on Android CGo builds")
}

func TriggerCrashNativeDoubleFree() {
	panic("native double free crash is only available on Android CGo builds")
}

func TriggerCrashNativeHeapCorruption() {
	panic("native heap corruption crash is only available on Android CGo builds")
}
