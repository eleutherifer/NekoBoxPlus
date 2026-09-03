package libcore

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"runtime/pprof"
	"runtime/trace"
	"strings"
	"sync"
	"time"
)

var coreProfiler = &profilerState{}

// Allocation profiles are cumulative, so keep a baseline and refresh the current
// profile to preserve recent data even if profiling is not stopped cleanly.
const allocationProfileInterval = 10 * time.Second

const (
	CoreProfilerModeCPU int32 = iota
	CoreProfilerModeTrace
)

type profilerState struct {
	access sync.Mutex

	running         bool
	shutdownPending bool
	mode            int32
	started         time.Time
	stopped         time.Time

	cpuFile   *os.File
	traceFile *os.File
	dir       string

	allocationStop chan struct{}
	allocationDone sync.WaitGroup
}

func CoreProfilingRunning() bool {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()
	return coreProfiler.running || coreProfiler.shutdownPending
}

func HasCoreProfilerSnapshot() bool {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()
	if coreProfiler.running {
		return true
	}
	return profilerFileExists(coreProfiler.dir, "cpu.pprof") ||
		profilerFileExists(coreProfiler.dir, "trace.out") ||
		profilerFileExists(coreProfiler.dir, "allocs.pprof")
}

func StartCoreProfiling(mode int32) (err error) {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()

	if !validCoreProfilerMode(mode) {
		return fmt.Errorf("unknown core profiler mode: %d", mode)
	}
	if coreProfiler.running {
		return nil
	}
	if coreProfiler.shutdownPending {
		return errors.New("core profiler shutdown snapshot is still being collected")
	}
	if mainInstance == nil || mainInstance.state != 1 {
		return errors.New("Core is not started yet")
	}
	if tempPath == "" {
		return errors.New("core is not initialized")
	}
	mode = compatibleCoreProfilerMode(mode, mainInstance.hasAmneziaWG)

	dir := filepath.Join(tempPath, "core-profiler")
	err = os.RemoveAll(dir)
	if err != nil {
		return err
	}
	err = os.MkdirAll(dir, 0700)
	if err != nil {
		return err
	}
	err = writeRuntimeProfile(dir, "allocs", "allocs-start.pprof")
	if err != nil {
		return err
	}

	var cpuFile, traceFile *os.File
	switch mode {
	case CoreProfilerModeCPU:
		cpuFile, err = os.Create(filepath.Join(dir, "cpu.pprof"))
		if err != nil {
			return err
		}
		defer func() {
			if err != nil {
				err = errors.Join(err, cpuFile.Close())
			}
		}()
		err = pprof.StartCPUProfile(cpuFile)
	case CoreProfilerModeTrace:
		traceFile, err = os.Create(filepath.Join(dir, "trace.out"))
		if err != nil {
			return err
		}
		defer func() {
			if err != nil {
				err = errors.Join(err, traceFile.Close())
			}
		}()
		err = trace.Start(traceFile)
	}
	if err != nil {
		return err
	}

	runtime.SetBlockProfileRate(1)
	runtime.SetMutexProfileFraction(1)

	coreProfiler.running = true
	coreProfiler.shutdownPending = false
	coreProfiler.mode = mode
	coreProfiler.started = time.Now()
	coreProfiler.stopped = time.Time{}
	coreProfiler.cpuFile = cpuFile
	coreProfiler.traceFile = traceFile
	coreProfiler.dir = dir
	coreProfiler.startAllocationProfilerLocked()
	return nil
}

func StopCoreProfiling() (err error) {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()
	return coreProfiler.stopAndCaptureLocked()
}

func PrepareCoreProfilerShutdown() (err error) {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()

	if !coreProfiler.running {
		return nil
	}
	err = coreProfiler.stopCollectorsLocked()
	coreProfiler.shutdownPending = true
	err = errors.Join(err, captureRuntimeSnapshot(coreProfiler.dir, "before-close"))
	err = errors.Join(err, writeProfilerMetadata(coreProfiler.dir, coreProfiler.mode, coreProfiler.started, coreProfiler.stopped, "pending"))
	return err
}

func FinishCoreProfilerShutdown(closeCompleted bool) (err error) {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()

	if !coreProfiler.shutdownPending {
		return nil
	}
	coreProfiler.shutdownPending = false
	shutdownStatus := "failed"
	if closeCompleted {
		PerformLibcoreGCSweep()
		err = captureRuntimeSnapshot(coreProfiler.dir, "after-close")
		shutdownStatus = "completed"
	}
	err = errors.Join(err, writeProfilerMetadata(coreProfiler.dir, coreProfiler.mode, coreProfiler.started, coreProfiler.stopped, shutdownStatus))
	return err
}

func WriteCoreProfilerSnapshot(outputDir string) (err error) {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()

	if coreProfiler.running {
		err = coreProfiler.stopAndCaptureLocked()
		if err != nil {
			return err
		}
	}
	if coreProfiler.shutdownPending {
		return errors.New("core profiler shutdown snapshot is still being collected")
	}
	if coreProfiler.dir == "" || !HasCoreProfilerSnapshotLocked() {
		return errors.New("no profiler snapshot has been collected yet")
	}
	err = os.MkdirAll(outputDir, 0700)
	if err != nil {
		return err
	}

	return copyProfilerFiles(coreProfiler.dir, outputDir)
}

func DeleteCoreProfilerSnapshot() (err error) {
	coreProfiler.access.Lock()
	defer coreProfiler.access.Unlock()

	if coreProfiler.running {
		err = coreProfiler.stopAndCaptureLocked()
		if err != nil {
			return err
		}
	}
	if coreProfiler.shutdownPending {
		return errors.New("core profiler shutdown snapshot is still being collected")
	}
	if coreProfiler.dir != "" {
		err = os.RemoveAll(coreProfiler.dir)
	}
	coreProfiler.dir = ""
	coreProfiler.started = time.Time{}
	coreProfiler.stopped = time.Time{}
	coreProfiler.shutdownPending = false
	return err
}

func (p *profilerState) stopAndCaptureLocked() error {
	if !p.running {
		return nil
	}
	err := p.stopCollectorsLocked()
	err = errors.Join(err, captureRuntimeSnapshot(p.dir, ""))
	err = errors.Join(err, writeProfilerMetadata(p.dir, p.mode, p.started, p.stopped, "not-requested"))
	return err
}

func (p *profilerState) stopCollectorsLocked() error {
	if !p.running {
		return nil
	}

	if p.traceFile != nil {
		trace.Stop()
	}
	if p.cpuFile != nil {
		pprof.StopCPUProfile()
	}
	runtime.SetBlockProfileRate(0)
	runtime.SetMutexProfileFraction(0)
	p.stopAllocationProfilerLocked()

	err := closeProfilerFiles(p.cpuFile, p.traceFile)
	err = errors.Join(err, writeRuntimeProfile(p.dir, "allocs", "allocs.pprof"))
	p.cpuFile = nil
	p.traceFile = nil
	p.running = false
	p.stopped = time.Now()
	return err
}

func (p *profilerState) startAllocationProfilerLocked() {
	p.allocationStop = make(chan struct{})
	stop := p.allocationStop
	dir := p.dir
	p.allocationDone.Go(func() {
		ticker := time.NewTicker(allocationProfileInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				_ = writeRuntimeProfile(dir, "allocs", "allocs.pprof")
			case <-stop:
				return
			}
		}
	})
}

func (p *profilerState) stopAllocationProfilerLocked() {
	if p.allocationStop == nil {
		return
	}
	close(p.allocationStop)
	p.allocationDone.Wait()
	p.allocationStop = nil
}

func closeProfilerFiles(files ...*os.File) error {
	var err error
	for _, file := range files {
		if file == nil {
			continue
		}
		if closeErr := file.Close(); closeErr != nil && err == nil {
			err = closeErr
		}
	}
	return err
}

func captureRuntimeSnapshot(outputDir string, phase string) error {
	var memStats runtime.MemStats
	runtime.ReadMemStats(&memStats)
	if err := writeJSONFile(filepath.Join(outputDir, phaseFileName("memstats", phase, ".json", "-before-gc")), &memStats); err != nil {
		return err
	}

	runtime.GC()
	if err := writeRuntimeProfile(outputDir, "allocs", phaseFileName("allocs", phase, ".pprof", "")); err != nil {
		return err
	}
	if err := writeRuntimeProfiles(outputDir, phase); err != nil {
		return err
	}
	runtime.ReadMemStats(&memStats)
	if err := writeJSONFile(filepath.Join(outputDir, phaseFileName("memstats", phase, ".json", "")), &memStats); err != nil {
		return err
	}
	return writeProcSnapshot(outputDir, phase)
}

func writeRuntimeProfiles(outputDir string, phase string) error {
	profiles := []string{"heap", "goroutine", "threadcreate", "block", "mutex"}
	for _, name := range profiles {
		if err := writeRuntimeProfile(outputDir, name, phaseFileName(name, phase, ".pprof", "")); err != nil {
			return err
		}
	}

	goroutineDebug, err := os.Create(filepath.Join(outputDir, phaseFileName("goroutine-debug", phase, ".txt", "")))
	if err != nil {
		return err
	}
	err = pprof.Lookup("goroutine").WriteTo(goroutineDebug, 2)
	err = errors.Join(err, goroutineDebug.Close())
	if err != nil {
		return fmt.Errorf("write goroutine debug profile: %w", err)
	}

	return nil
}

func phaseFileName(base string, phase string, extension string, qualifier string) string {
	if phase == "" {
		if qualifier == "-before-gc" {
			return base + extension
		}
		if base == "memstats" {
			return base + "-after-gc" + extension
		}
		return base + qualifier + extension
	}
	return base + "-" + phase + qualifier + extension
}

func writeRuntimeProfile(outputDir string, profileName string, fileName string) error {
	profile := pprof.Lookup(profileName)
	if profile == nil {
		return fmt.Errorf("runtime profile %s is unavailable", profileName)
	}
	temporary, err := os.CreateTemp(outputDir, "."+fileName+"-*")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)

	err = profile.WriteTo(temporary, 0)
	err = errors.Join(err, temporary.Close())
	if err != nil {
		return fmt.Errorf("write %s profile: %w", profileName, err)
	}
	if err := os.Rename(temporaryPath, filepath.Join(outputDir, fileName)); err != nil {
		return fmt.Errorf("publish %s profile: %w", profileName, err)
	}
	return nil
}

func writeProfilerMetadata(outputDir string, mode int32, started time.Time, stopped time.Time, shutdownStatus string) error {
	buildInfo, loaded := debug.ReadBuildInfo()
	buildText := "build info unavailable\n"
	if loaded {
		buildText = buildInfo.String()
	}
	err := os.WriteFile(filepath.Join(outputDir, "buildinfo.txt"), []byte(buildText), 0600)
	if err != nil {
		return err
	}

	metadata := fmt.Sprintf(
		"mode: %s\nstarted: %s\nstopped: %s\nduration: %s\nshutdown: %s\ngo: %s\nplatform: %s/%s\ngoroutines: %d\n",
		coreProfilerModeName(mode),
		started.Format(time.RFC3339Nano),
		stopped.Format(time.RFC3339Nano),
		stopped.Sub(started),
		shutdownStatus,
		runtime.Version(),
		runtime.GOOS,
		runtime.GOARCH,
		runtime.NumGoroutine(),
	)
	return os.WriteFile(filepath.Join(outputDir, "metadata.txt"), []byte(metadata), 0600)
}

func validCoreProfilerMode(mode int32) bool {
	return mode == CoreProfilerModeCPU || mode == CoreProfilerModeTrace
}

// Go execution tracing currently crashes the Android runtime while an
// AmneziaWG endpoint is active. Keep profiler collection available by falling
// back to CPU profiling for that configuration.
func compatibleCoreProfilerMode(mode int32, hasAmneziaWG bool) int32 {
	if mode == CoreProfilerModeTrace && hasAmneziaWG {
		return CoreProfilerModeCPU
	}
	return mode
}

func coreProfilerModeName(mode int32) string {
	switch mode {
	case CoreProfilerModeCPU:
		return "cpu"
	case CoreProfilerModeTrace:
		return "trace"
	default:
		return "unknown"
	}
}

func writeJSONFile(path string, value any) error {
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	encoder := json.NewEncoder(file)
	encoder.SetIndent("", "  ")
	err = encoder.Encode(value)
	err = errors.Join(err, file.Close())
	return err
}

func writeProcSnapshot(outputDir string, phase string) error {
	var procErrors []string
	for _, snapshot := range []struct {
		source string
		name   string
	}{
		{source: "/proc/self/status", name: "status"},
		{source: "/proc/self/statm", name: "statm"},
		{source: "/proc/self/smaps_rollup", name: "smaps_rollup"},
		{source: "/proc/cpuinfo", name: "cpuinfo"},
	} {
		source := snapshot.source
		content, readErr := os.ReadFile(source)
		if readErr != nil {
			procErrors = append(procErrors, fmt.Sprintf("read %s: %s", source, readErr))
			continue
		}
		writeErr := os.WriteFile(filepath.Join(outputDir, phaseFileName("proc-"+snapshot.name, phase, ".txt", "")), content, 0600)
		if writeErr != nil {
			procErrors = append(procErrors, fmt.Sprintf("write proc-%s.txt: %s", snapshot.name, writeErr))
		}
	}
	if len(procErrors) > 0 {
		return os.WriteFile(filepath.Join(outputDir, phaseFileName("proc-errors", phase, ".txt", "")), []byte(strings.Join(procErrors, "\n")+"\n"), 0600)
	}
	return nil
}

func copyProfilerFiles(sourceDir string, outputDir string) error {
	entries, err := os.ReadDir(sourceDir)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if !entry.Type().IsRegular() {
			continue
		}
		name := entry.Name()
		source, err := os.Open(filepath.Join(sourceDir, name))
		if err != nil {
			return err
		}
		destination, err := os.OpenFile(filepath.Join(outputDir, name), os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
		if err != nil {
			_ = source.Close()
			return err
		}
		_, copyErr := io.Copy(destination, source)
		err = errors.Join(copyErr, destination.Close(), source.Close())
		if err != nil {
			return fmt.Errorf("copy %s: %w", name, err)
		}
	}
	return nil
}

func profilerFileExists(dir string, name string) bool {
	if dir == "" {
		return false
	}
	info, err := os.Stat(filepath.Join(dir, name))
	return err == nil && info.Size() > 0
}

func HasCoreProfilerSnapshotLocked() bool {
	return profilerFileExists(coreProfiler.dir, "cpu.pprof") ||
		profilerFileExists(coreProfiler.dir, "trace.out") ||
		profilerFileExists(coreProfiler.dir, "allocs.pprof")
}
