package device

import "runtime"

const maxAndroidWorkers = 4

func workerCount() int {
	return workerCountFor(runtime.GOOS, runtime.NumCPU())
}

func workerCountFor(goos string, cpus int) int {
	if goos == "android" {
		return min(cpus, maxAndroidWorkers)
	}
	return cpus
}
