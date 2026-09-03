package kmutex

import (
	"sync"
	"testing"
)

func TestMutexSerializesPerKey(t *testing.T) {
	mutex := New[int]()
	values := make([]int, 16)
	var waitGroup sync.WaitGroup
	for index := range 256 {
		key := index % len(values)
		waitGroup.Go(func() {
			for range 100 {
				mutex.Lock(key)
				values[key]++
				mutex.Unlock(key)
			}
		})
	}
	waitGroup.Wait()
	for key, value := range values {
		if value != 1600 {
			t.Fatalf("value[%d] = %d, want 1600", key, value)
		}
	}
}
