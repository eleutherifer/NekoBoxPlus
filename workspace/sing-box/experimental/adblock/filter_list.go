//go:build with_adblock

package adblock

import (
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	N "github.com/sagernet/sing/common/network"
)

type AdblockFilterMetadata = adapter.AdblockFilterMetadata

type filterList struct {
	option      option.AdblockFilterList
	interval    time.Duration
	lastUpdated time.Time
	title       string
	lastEtag    string
	content     []byte
	hasRules    bool
	dialer      N.Dialer
	metadata    AdblockFilterMetadata
}

func (l *filterList) shouldUpdate() bool {
	return !l.hasRules && len(l.content) == 0 || l.lastUpdated.IsZero() || time.Since(l.lastUpdated) > l.interval
}

func (l *filterList) applyParsed(parsedFilter parsedFilter) {
	l.applyParsedInterval(parsedFilter)
	l.metadata = parsedFilter.AdblockFilterMetadata
	l.metadata.URI = l.option.URL
	l.metadata.RuleCount = len(parsedFilter.Rules)
	l.metadata.LastUpdated = l.lastUpdated

	l.hasRules = len(parsedFilter.Rules) > 0
	l.title = parsedFilter.Title
}

func (l *filterList) applyMetadata(parsedFilter parsedFilter) {
	l.applyParsedInterval(parsedFilter)
	l.metadata = parsedFilter.AdblockFilterMetadata
	l.metadata.URI = l.option.URL
	l.metadata.LastUpdated = l.lastUpdated
	l.title = parsedFilter.Title
}

func (l *filterList) applyParsedInterval(parsedFilter parsedFilter) {
	if time.Duration(l.option.UpdateInterval) <= 0 && parsedFilter.ExpiresInterval > 0 {
		l.interval = parsedFilter.ExpiresInterval
	}
	if l.interval <= 0 {
		l.interval = defaultFilterUpdateInterval
	}
}

func (s *Service) releaseStoredFilterContent() {
	if s.store == nil {
		return
	}
	for index := range s.filterLists {
		s.filterLists[index].content = nil
	}
}
