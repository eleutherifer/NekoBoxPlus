//go:build with_adblock

package adblock

import (
	"encoding/binary"

	"github.com/sagernet/sing-box/common/adblock/adblockrust"
)

type adblockCheckCacheKey struct {
	requestURL  string
	sourceURL   string
	requestType string
	method      adblockrust.RequestMethod
}

type adblockRequestCacheValue struct {
	check        adblockrust.CheckResult
	hasCheck     bool
	exception    bool
	hasException bool
}

// MarshalTo writes deterministic binary form into dst and returns bytes written.
// It returns (n, true) if dst had enough capacity, otherwise (0, false).
func (k adblockCheckCacheKey) MarshalTo(dst []byte) (int, bool) {
	// layout: len(req) uint32 + req bytes + len(src) uint32 + src bytes + len(typ) uint32 + typ bytes + method byte
	nReq := len(k.requestURL)
	nSrc := len(k.sourceURL)
	nTyp := len(k.requestType)
	total := 4 + nReq + 4 + nSrc + 4 + nTyp + 1
	if cap(dst) < total {
		return 0, false
	}
	dst = dst[:total]
	offset := 0
	binary.LittleEndian.PutUint32(dst[offset:offset+4], uint32(nReq))
	offset += 4
	copy(dst[offset:offset+nReq], k.requestURL)
	offset += nReq
	binary.LittleEndian.PutUint32(dst[offset:offset+4], uint32(nSrc))
	offset += 4
	copy(dst[offset:offset+nSrc], k.sourceURL)
	offset += nSrc
	binary.LittleEndian.PutUint32(dst[offset:offset+4], uint32(nTyp))
	offset += 4
	copy(dst[offset:offset+nTyp], k.requestType)
	offset += nTyp
	dst[offset] = byte(k.method)
	offset++
	return total, true
}

func (s *Service) checkCacheGet(key adblockCheckCacheKey) (adblockrust.CheckResult, bool) {
	if s.requestCache == nil {
		return adblockrust.CheckResult{}, false
	}
	value, loaded := s.requestCache.Get(key)
	return value.check, loaded && value.hasCheck
}

func (s *Service) checkCacheStore(key adblockCheckCacheKey, result adblockrust.CheckResult) {
	if s.requestCache == nil {
		return
	}
	value, _ := s.requestCache.Get(key)
	value.check = result
	value.hasCheck = true
	s.requestCache.Add(key, value)
}

func (s *Service) exceptionCacheGet(key adblockCheckCacheKey) (bool, bool) {
	if s.requestCache == nil {
		return false, false
	}
	value, loaded := s.requestCache.Get(key)
	return value.exception, loaded && value.hasException
}

func (s *Service) exceptionCacheStore(key adblockCheckCacheKey, matched bool) {
	if s.requestCache == nil {
		return
	}
	value, _ := s.requestCache.Get(key)
	value.exception = matched
	value.hasException = true
	s.requestCache.Add(key, value)
}

func (s *Service) clearCheckCache() {
	if s.requestCache != nil {
		s.requestCache.Purge()
	}
}
