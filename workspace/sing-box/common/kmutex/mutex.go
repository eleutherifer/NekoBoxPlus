package kmutex

import "sync"

type Mutex[K comparable] struct {
	access sync.Mutex
	locks  map[K]*keyLock
}

type keyLock struct {
	mutex sync.Mutex
	refs  int
}

func New[K comparable]() *Mutex[K] {
	return &Mutex[K]{locks: make(map[K]*keyLock)}
}

func (m *Mutex[K]) Lock(key K) {
	m.access.Lock()
	lock := m.locks[key]
	if lock == nil {
		lock = new(keyLock)
		m.locks[key] = lock
	}
	lock.refs++
	m.access.Unlock()
	lock.mutex.Lock()
}

func (m *Mutex[K]) Unlock(key K) {
	m.access.Lock()
	lock := m.locks[key]
	if lock == nil || lock.refs == 0 {
		m.access.Unlock()
		panic("unlock of unlocked keyed mutex")
	}
	lock.mutex.Unlock()
	lock.refs--
	if lock.refs == 0 {
		delete(m.locks, key)
	}
	m.access.Unlock()
}
