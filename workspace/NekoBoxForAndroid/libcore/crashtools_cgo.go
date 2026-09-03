//go:build android && cgo

package libcore

/*
#include <signal.h>
#include <stdlib.h>
#include <string.h>

static void nb4a_crash_abort(void) {
	abort();
}

static void nb4a_crash_sigsegv(void) {
	volatile int *ptr = (int *)0;
	*ptr = 1;
}

static void nb4a_crash_trap(void) {
#if defined(__has_builtin)
#  if __has_builtin(__builtin_trap)
	__builtin_trap();
#  else
	raise(SIGILL);
#  endif
#else
	raise(SIGILL);
#endif
}

static void nb4a_crash_double_free(void) {
	void *ptr = malloc(16);
	free(ptr);
	free(ptr);
}

static void nb4a_crash_heap_corruption(void) {
	char *ptr = (char *)malloc(16);
	free(ptr);
	memset(ptr, 0x41, 4096);
	free(ptr);
}
*/
import "C"

func TriggerCrashNativeAbort() {
	C.nb4a_crash_abort()
}

func TriggerCrashNativeSigsegv() {
	C.nb4a_crash_sigsegv()
}

func TriggerCrashNativeTrap() {
	C.nb4a_crash_trap()
}

func TriggerCrashNativeDoubleFree() {
	C.nb4a_crash_double_free()
}

func TriggerCrashNativeHeapCorruption() {
	C.nb4a_crash_heap_corruption()
}
