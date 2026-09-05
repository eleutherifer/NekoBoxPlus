package protect

import (
	"errors"
	"sync"
)

var (
	access    sync.RWMutex
	protector func(fd int) error
)

func SetProtector(fn func(fd int) error) {
	access.Lock()
	defer access.Unlock()
	protector = fn
}

func FD(fd int) error {
	access.RLock()
	fn := protector
	access.RUnlock()
	if fn == nil {
		return errors.New("protect function is not set")
	}
	return fn(fd)
}
