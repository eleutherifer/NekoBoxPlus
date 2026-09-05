package device

import "testing"

func TestWorkerCountFor(t *testing.T) {
	tests := []struct {
		name string
		goos string
		cpus int
		want int
	}{
		{name: "Android capped", goos: "android", cpus: 8, want: 4},
		{name: "Android below cap", goos: "android", cpus: 2, want: 2},
		{name: "other OS", goos: "linux", cpus: 8, want: 8},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := workerCountFor(test.goos, test.cpus); got != test.want {
				t.Fatalf("worker count = %d, want %d", got, test.want)
			}
		})
	}
}
