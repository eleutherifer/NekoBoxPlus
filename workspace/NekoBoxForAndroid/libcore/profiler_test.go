package libcore

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPhaseFileName(t *testing.T) {
	tests := map[string]struct {
		base      string
		phase     string
		extension string
		qualifier string
		expected  string
	}{
		"generic memstats before gc": {"memstats", "", ".json", "-before-gc", "memstats.json"},
		"generic memstats after gc":  {"memstats", "", ".json", "", "memstats-after-gc.json"},
		"before close heap":          {"heap", "before-close", ".pprof", "", "heap-before-close.pprof"},
		"before close memstats":      {"memstats", "before-close", ".json", "-before-gc", "memstats-before-close-before-gc.json"},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			actual := phaseFileName(test.base, test.phase, test.extension, test.qualifier)
			if actual != test.expected {
				t.Fatalf("expected %q, got %q", test.expected, actual)
			}
		})
	}
}

func TestCoreProfilingRunningIncludesPendingShutdownCapture(t *testing.T) {
	setProfilerStateForTest(t, &profilerState{shutdownPending: true})
	if !CoreProfilingRunning() {
		t.Fatal("pending shutdown capture must keep profiler active")
	}
}

func TestFinishCoreProfilerShutdownFailed(t *testing.T) {
	dir := t.TempDir()
	setProfilerStateForTest(t, &profilerState{
		shutdownPending: true,
		started:         time.Now().Add(-time.Second),
		stopped:         time.Now(),
		dir:             dir,
	})

	if err := FinishCoreProfilerShutdown(false); err != nil {
		t.Fatal(err)
	}
	assertFileContains(t, filepath.Join(dir, "metadata.txt"), "shutdown: failed")
	if _, err := os.Stat(filepath.Join(dir, "heap-after-close.pprof")); !os.IsNotExist(err) {
		t.Fatalf("unexpected after-close heap: %v", err)
	}
	if err := FinishCoreProfilerShutdown(true); err != nil {
		t.Fatalf("idempotent finish failed: %v", err)
	}
}

func TestFinishCoreProfilerShutdownCompleted(t *testing.T) {
	dir := t.TempDir()
	setProfilerStateForTest(t, &profilerState{
		shutdownPending: true,
		started:         time.Now().Add(-time.Second),
		stopped:         time.Now(),
		dir:             dir,
	})

	if err := FinishCoreProfilerShutdown(true); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{
		"allocs-after-close.pprof",
		"heap-after-close.pprof",
		"memstats-after-close-before-gc.json",
		"memstats-after-close.json",
		"proc-status-after-close.txt",
	} {
		info, err := os.Stat(filepath.Join(dir, name))
		if err != nil {
			t.Errorf("missing %s: %v", name, err)
		} else if info.Size() == 0 {
			t.Errorf("empty %s", name)
		}
	}
	assertFileContains(t, filepath.Join(dir, "metadata.txt"), "shutdown: completed")
}

func TestCopyProfilerFilesCopiesCompleteSnapshot(t *testing.T) {
	sourceDir := t.TempDir()
	destinationDir := t.TempDir()
	for _, name := range []string{"allocs.pprof", "heap-after-close.pprof", "metadata.txt"} {
		if err := os.WriteFile(filepath.Join(sourceDir, name), []byte(name), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if err := copyProfilerFiles(sourceDir, destinationDir); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"allocs.pprof", "heap-after-close.pprof", "metadata.txt"} {
		assertFileContains(t, filepath.Join(destinationDir, name), name)
	}
}

func setProfilerStateForTest(t *testing.T, state *profilerState) {
	t.Helper()
	previous := coreProfiler
	coreProfiler = state
	t.Cleanup(func() {
		coreProfiler = previous
	})
}

func assertFileContains(t *testing.T, path string, expected string) {
	t.Helper()
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(content), expected) {
		t.Fatalf("%s does not contain %q", path, expected)
	}
}
