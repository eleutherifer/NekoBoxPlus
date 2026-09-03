//go:build with_adblock

package adblock

import (
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
)

var _ adapter.AdblockStats = (*serviceStats)(nil)

type serviceStatsContainer interface {
	LoadAdblockStats() (total uint64, blocked uint64, loaded bool)
	StoreAdblockStats(total uint64, blocked uint64) error
}

type serviceStats struct {
	totalReq   atomic.Uint64
	blockedReq atomic.Uint64

	statsSaveAccess    sync.Mutex
	statsSaveScheduled bool
	statsSaveTimer     *time.Timer

	statsContainer serviceStatsContainer

	logger log.ContextLogger
}

func newServiceStats(logger log.ContextLogger) *serviceStats {
	return &serviceStats{logger: logger}
}

func (s *serviceStats) setStatsContainer(container serviceStatsContainer) {
	s.statsContainer = container
	total, blocked, loaded := container.LoadAdblockStats()
	if loaded {
		s.totalReq.Store(total)
		s.blockedReq.Store(blocked)
	}
}

func (s *serviceStats) TotalRequests() uint64 {
	return s.totalReq.Load()
}

func (s *serviceStats) BlockedRequests() uint64 {
	return s.blockedReq.Load()
}

func (s *serviceStats) recordRequest(blocked bool) {
	s.totalReq.Add(1)
	if blocked {
		s.blockedReq.Add(1)
	}
	s.scheduleStatsSave()
}

func (s *serviceStats) scheduleStatsSave() {
	if s.statsContainer == nil {
		return
	}
	s.statsSaveAccess.Lock()
	if s.statsSaveScheduled {
		s.statsSaveAccess.Unlock()
		return
	}
	s.statsSaveScheduled = true
	s.statsSaveTimer = time.AfterFunc(5*time.Second, func() {
		s.statsSaveAccess.Lock()
		s.statsSaveScheduled = false
		s.statsSaveTimer = nil
		s.statsSaveAccess.Unlock()
		s.saveStats()
	})
	s.statsSaveAccess.Unlock()
}

func (s *serviceStats) saveStats() {
	if s.statsContainer == nil {
		return
	}
	if err := s.statsContainer.StoreAdblockStats(s.totalReq.Load(), s.blockedReq.Load()); err != nil {
		if s.logger != nil {
			s.logger.Warn("save adblock stats: ", err)
		}
	}
}

func (s *serviceStats) stop() {
	s.statsSaveAccess.Lock()
	if s.statsSaveTimer != nil {
		s.statsSaveTimer.Stop()
		s.statsSaveTimer = nil
	}
	s.statsSaveScheduled = false
	s.statsSaveAccess.Unlock()
	s.saveStats()
}
