//go:build with_adblock

package adblock

import (
	"strings"
	"time"
)

const (
	tlsExclusionTTL             = 6 * time.Hour
	tlsExclusionCleanupInterval = 24 * time.Hour
)

func normalizeTLSExclusionDomain(domain string) string {
	return strings.ToLower(strings.TrimSuffix(strings.TrimSpace(domain), "."))
}

func (s *Service) tlsExclusionActive(domain string) bool {
	domain = normalizeTLSExclusionDomain(domain)
	if domain == "" {
		return false
	}
	value, loaded := s.tlsExclusions.Load(domain)
	if !loaded {
		return false
	}
	addedAt, loaded := value.(time.Time)
	if !loaded {
		s.tlsExclusions.Delete(domain)
		return false
	}
	elapsed := time.Since(addedAt)
	if elapsed < 0 || elapsed >= tlsExclusionTTL {
		s.tlsExclusions.Delete(domain)
		return false
	}
	return true
}

func (s *Service) cleanupTLSExclusions() {
	s.tlsExclusions.Range(func(domain, value any) bool {
		addedAt, loaded := value.(time.Time)
		if !loaded {
			s.tlsExclusions.Delete(domain)
			return true
		}
		elapsed := time.Since(addedAt)
		if elapsed < 0 || elapsed >= tlsExclusionTTL {
			s.tlsExclusions.Delete(domain)
		}
		return true
	})
}

func (s *Service) loopTLSExclusionCleanup() {
	ticker := time.NewTicker(tlsExclusionCleanupInterval)
	defer ticker.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			s.cleanupTLSExclusions()
		}
	}
}
