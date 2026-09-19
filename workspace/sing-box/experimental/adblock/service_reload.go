//go:build with_adblock

package adblock

import (
	"context"
	"strings"
	"time"

	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	E "github.com/sagernet/sing/common/exceptions"
)

// engineReloadMinInterval bounds how often ReloadEngine may rebuild the engine.
// Requests arriving within the cooldown are coalesced, but the final request is
// always applied: a trailing rebuild runs once the interval elapses.
const engineReloadMinInterval = 5 * time.Second

// ReloadEngine schedules a throttled engine rebuild from the cached filter
// database (no network access). It is safe to call from any goroutine; repeated
// calls within engineReloadMinInterval collapse into a single rebuild, with a
// guaranteed trailing rebuild so the latest database state is always applied.
func (s *Service) ReloadEngine() {
	select {
	case s.reloadChan <- struct{}{}:
	default:
		// a reload is already pending; the trailing rebuild reads the latest
		// database state, so this request is safely coalesced.
	}
}

// loopReload is the reload worker, started once in StartStatePostStart. It owns
// the throttle state and guarantees rebuilds never overlap with background
// updateEngine runs (both serialize on s.rebuildMu).
func (s *Service) loopReload() {
	var lastReload time.Time
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-s.reloadChan:
			if elapsed := time.Since(lastReload); elapsed < engineReloadMinInterval {
				timer := time.NewTimer(engineReloadMinInterval - elapsed)
				select {
				case <-s.ctx.Done():
					timer.Stop()
					return
				case <-timer.C:
				}
			}
			// Coalesce any signals that arrived while waiting so they do not
			// trigger redundant trailing rebuilds; one rebuild picks up all
			// pending database changes since it always reads current state.
			s.drainReloadRequests()
			if err := s.reloadEngineFromStore(s.ctx); err != nil {
				if s.logger != nil {
					s.logger.Warn("reload adblock engine: ", err)
				}
				// leave lastReload unchanged so a failed reload does not reset
				// the throttle; the next signal is still rate-limited.
				continue
			}
			lastReload = time.Now()
		}
	}
}

func (s *Service) drainReloadRequests() {
	for {
		select {
		case <-s.reloadChan:
		default:
			return
		}
	}
}

// reloadEngineFromStore rebuilds the engine from the cached filter content in
// the database without any network access. It serializes against background
// updateEngine runs via s.rebuildMu so filterLists mutations never overlap.
func (s *Service) reloadEngineFromStore(ctx context.Context) error {
	s.rebuildMu.Lock()
	defer s.rebuildMu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}

	s.debugContext(ctx, "engine reload from store started")
	store := s.store
	if store == nil {
		return E.New("adblock store unavailable")
	}

	ruleSets := make([]adblockrust.RuleSet, 0, 1+len(s.filterLists))
	var companion companionRules
	var htmlFilters htmlFilterRules
	var advanced advancedRules
	ruleCount := 0
	if inlineRules := s.inlineRules(); len(inlineRules) > 0 {
		parsedInline := parseFilterLinesWithEnvironment([]byte(strings.Join(inlineRules, "\n")), s.options.Environment)
		companion.merge(parsedInline.Companion)
		htmlFilters.merge(parsedInline.HTML)
		advanced.merge(parsedInline.Advanced)
		if len(parsedInline.Rules) > 0 {
			ruleSets = append(ruleSets, adblockrust.RuleSet{
				Rules:       parsedInline.Rules,
				Format:      adblockrust.RuleFormatStandard,
				Permissions: 0xff,
			})
		}
		ruleCount += len(parsedInline.Rules)
	}

	for index := range s.filterLists {
		list := &s.filterLists[index]
		if saved := store.LoadFilterList(cacheTag(list.option.URL)); saved != nil {
			list.content = saved.Content
			list.lastUpdated = saved.LastUpdated
			list.lastEtag = saved.LastEtag
		}
		parsedFilter := parseFilterLinesWithEnvironment(list.content, s.options.Environment)
		list.applyParsed(parsedFilter)
		companion.merge(parsedFilter.Companion)
		htmlFilters.merge(parsedFilter.HTML)
		advanced.merge(parsedFilter.Advanced)
		if len(parsedFilter.Rules) > 0 {
			ruleSets = append(ruleSets, adblockrust.RuleSet{
				Rules:       parsedFilter.Rules,
				Format:      adblockRuleFormat(list.option.Format),
				Permissions: adblockRulePermissions(list.option.Trust),
			})
			ruleCount += len(parsedFilter.Rules)
		}
	}

	if ruleCount == 0 && companion.empty() && htmlFilters.empty() && advanced.empty() {
		s.debugContext(ctx, "engine reload failed: no valid filters")
		return E.New("no valid adblock filters")
	}

	engine, err := adblockrust.NewEngineWithRuleSets(ruleSets, s.options.AdblockResources)
	if err != nil {
		s.debugContext(ctx, "engine reload build failed: ", err)
		return err
	}

	if len(s.options.Tags) > 0 {
		if err := engine.UseTags(s.options.Tags); err != nil {
			_ = engine.Close()
			return err
		}
	}
	oldEngine, ok := s.installEngine(ctx, engine, companion, htmlFilters, advanced)
	if !ok {
		s.debugContext(ctx, "engine reload canceled: ", ctx.Err())
		return ctx.Err()
	}
	s.releaseStoredFilterContent()
	s.clearCheckCache()
	s.debugContext(ctx, "check cache cleared")
	if oldEngine != nil {
		s.debugContext(ctx, "old engine closing")
		_ = oldEngine.close()
	}
	s.logger.Info("adblock engine reloaded with ", ruleCount, " filters")
	return nil
}

// installEngine atomically replaces the active engine with a freshly built one.
// It refuses the swap (and closes the new engine) if the service or request
// context is already cancelled, which guarantees a concurrent Close can never
// leave a live engine installed after teardown. The caller must not hold
// s.access and owns closing the returned displaced engine.
func (s *Service) installEngine(ctx context.Context, engine adblockrust.Engine, companion companionRules, htmlFilters htmlFilterRules, advanced advancedRules) (old *managedEngine, installed bool) {
	s.access.Lock()
	defer s.access.Unlock()
	if s.ctx.Err() != nil || ctx.Err() != nil {
		_ = engine.Close()
		return nil, false
	}
	old = s.engine
	s.engine = newManagedEngine(engine)
	s.companion = companion
	s.htmlFilters = htmlFilters
	s.advanced = advanced
	return old, true
}
