//go:build with_adblock

package adblock

import (
	"bytes"
	"encoding/gob"
	"sync"

	xxhash "github.com/cespare/xxhash/v2"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/contrab/freelru"
)

const defaultCheckCacheSize = 16384

func MustNewLRU[K comparable, V comparable](capacity uint32, customHashFunc ...freelru.HashKeyCallback[K]) *freelru.Cache[K, V] {
	var hashFunc freelru.HashKeyCallback[K]
	if len(customHashFunc) > 0 {
		hashFunc = customHashFunc[0]
	} else {
		hashFunc = newHasherFunc[K]()
	}

	return common.Must1(freelru.New[K, V](capacity, hashFunc, true))
}

type BinaryMarshaler interface {
	MarshalTo([]byte) (int, bool)
}

var bufPool = sync.Pool{
	New: func() any { b := make([]byte, 4096); return &b },
}

func newHasherFunc[K comparable]() freelru.HashKeyCallback[K] {
	return func(v K) uint32 {
		if mt, ok := any(v).(BinaryMarshaler); ok {
			return marshalerHash(mt)
		}
		// Generic fallback (not used by the check cache, whose key implements
		// BinaryMarshaler). Scoped here so the hot path never allocates it.
		var buf bytes.Buffer
		_ = gob.NewEncoder(&buf).Encode(v)
		return uint32(xxhash.Sum64(buf.Bytes()))
	}
}

// marshalerHash hashes a BinaryMarshaler key using a pooled scratch buffer.
// The pooled buffer is large enough for realistic (url, source, type) keys,
// so the common path performs zero heap allocations.
func marshalerHash(mt BinaryMarshaler) uint32 {
	p := bufPool.Get().(*[]byte)
	buf := *p
	if n, ok := mt.MarshalTo(buf); ok {
		sum := uint32(xxhash.Sum64(buf[:n]))
		bufPool.Put(p)
		return sum
	}
	bufPool.Put(p)
	// Pooled buffer too small; grow a fresh buffer until MarshalTo fits.
	size := len(buf) * 2
	for {
		tmp := make([]byte, size)
		if n, ok := mt.MarshalTo(tmp); ok {
			return uint32(xxhash.Sum64(tmp[:n]))
		}
		size *= 2
	}
}

func newStringHasherFunc() freelru.HashKeyCallback[string] {
	return func(v string) uint32 {
		return uint32(xxhash.Sum64([]byte(v)))
	}
}
