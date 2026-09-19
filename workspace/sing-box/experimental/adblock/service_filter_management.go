//go:build with_adblock

package adblock

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/experimental/adblock/db"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/ntp"
)

type FilterUpdateResult struct {
	url string
	upd string
	mod string
	err error
}

func (f FilterUpdateResult) URL() string {
	return f.url
}

func (f FilterUpdateResult) LastUpdated() string {
	return f.upd
}

func (f FilterUpdateResult) LastModified() string {
	return f.mod
}

func (f FilterUpdateResult) Error() error {
	return f.err
}

func (s *Service) GetFilterMetadata(uri string) (adapter.AdblockFilterMetadata, error) {
	s.rebuildMu.Lock()
	for _, list := range s.filterLists {
		if list.option.URL == uri && list.metadata.URI != "" {
			metadata := list.metadata
			s.rebuildMu.Unlock()
			return metadata, nil
		}
	}
	s.rebuildMu.Unlock()
	list := filterList{
		option: option.AdblockFilterList{},
		dialer: s.defaultDialer(),
	}
	list.option.URL = uri
	if err := s.fetchFilterList(s.ctx, &list); err != nil {
		return adapter.AdblockFilterMetadata{}, err
	}
	parsedFilter := parseFilterMetadataWithEnvironment(list.content, s.options.Environment)
	list.applyMetadata(parsedFilter)
	return list.metadata, nil
}

func (s *Service) GetStoredFilterMetadata(uri string, databasePath string) (*adapter.AdblockFilterMetadata, error) {
	var list *filterList

	err := s.withAdblockDB(databasePath, func(store adapter.AdblockDatabase) error {
		saved := store.LoadFilterList(cacheTag(uri))
		if saved == nil {
			return nil
		}

		list = &filterList{
			content:     saved.Content,
			lastUpdated: saved.LastUpdated,
			lastEtag:    saved.LastEtag,
		}

		parsedFilter := parseFilterMetadataWithEnvironment(list.content, s.options.Environment)
		list.applyMetadata(parsedFilter)

		return nil
	})
	if err != nil {
		return nil, err
	}
	if list != nil {
		return &list.metadata, nil
	}

	return nil, nil
}

func (s *Service) PreCacheFilter(uri string, databasePath string) (string, error) {
	list := filterList{
		option: option.AdblockFilterList{},
		dialer: s.defaultDialer(),
	}
	list.option.URL = uri
	if err := s.fetchFilterList(s.ctx, &list); err != nil {
		return "", err
	}
	list.applyMetadata(parseFilterMetadataWithEnvironment(list.content, s.options.Environment))

	err := s.withAdblockDB(databasePath, func(store adapter.AdblockDatabase) error {
		return store.SaveFilterList(cacheTag(uri), &adapter.SavedBinary{
			Content:     list.content,
			LastUpdated: list.lastUpdated,
			LastEtag:    list.lastEtag,
		})
	})
	if err != nil {
		return "", err
	}
	version := list.metadata.LastModified
	if FilterLastModified(version) == "" {
		version = list.lastUpdated.Format(time.DateTime)
	}
	return version, nil
}

func (s *Service) DeleteCachedFilter(uri string, databasePath string) error {
	return s.withAdblockDB(databasePath, func(store adapter.AdblockDatabase) error {
		return store.DeleteFilterList(cacheTag(uri))
	})
}

func (s *Service) PreCacheFilters(uriList []string, databasePath string) (results chan FilterUpdateResult) {
	results = make(chan FilterUpdateResult, len(uriList))
	errAll := func(err error) {
		for _, uri := range uriList {
			results <- FilterUpdateResult{url: uri, err: err}
		}
		close(results)
	}

	store, ownClose, err := s.getAdblockDB(databasePath)
	if err != nil {
		errAll(err)
		return
	}

	go func() {
		if ownClose {
			defer store.Close()
		}

		var (
			wg        sync.WaitGroup
			storeLock sync.Mutex
		)
		for _, uri := range uriList {
			wg.Add(1)
			go func() {
				defer wg.Done()
				list := filterList{
					option: option.AdblockFilterList{},
					dialer: s.defaultDialer(),
				}
				list.option.URL = uri
				if err := s.fetchFilterList(s.ctx, &list); err != nil {
					results <- FilterUpdateResult{url: uri, err: err}
					return
				}
				list.applyMetadata(parseFilterMetadataWithEnvironment(list.content, s.options.Environment))

				storeLock.Lock()
				defer storeLock.Unlock()
				results <- FilterUpdateResult{
					url: uri,
					upd: list.lastUpdated.Format(time.DateTime),
					mod: FilterLastModified(list.metadata.LastModified),
					err: store.SaveFilterList(
						cacheTag(uri),
						&adapter.SavedBinary{
							Content:     list.content,
							LastUpdated: list.lastUpdated,
							LastEtag:    list.lastEtag,
						},
					)}
			}()

		}
		wg.Wait()
		close(results)
	}()

	return results
}

func (s *Service) withAdblockDB(databasePath string, cb func(store adapter.AdblockDatabase) error) error {
	store, ownClose, err := s.getAdblockDB(databasePath)
	if err != nil {
		return err
	}

	if ownClose {
		defer store.Close()
	}

	return cb(store)
}

func (s *Service) getAdblockDB(databasePath string) (adapter.AdblockDatabase, bool, error) {
	if s.store != nil {
		return s.store, false, nil
	}

	if databasePath == "" {
		return nil, false, E.New("adblock database_path is not available")
	}
	store := db.New(s.ctx, databasePath)
	if err := store.Start(adapter.StartStateInitialize); err != nil {
		return nil, false, err
	}

	return store, true, nil
}

func (s *Service) prepareFilterList(index int) error {
	list := &s.filterLists[index]
	// The dialer is only needed to fetch list updates; tests and configurations
	// without an outbound manager still prime list.content from the cache.
	if s.outbound != nil {
		if list.option.DownloadDetour != "" {
			outbound, loaded := s.outbound.Outbound(list.option.DownloadDetour)
			if !loaded {
				return E.New("adblock download detour not found: ", list.option.DownloadDetour)
			}
			list.dialer = outbound
			s.debug("filter list uses download detour: ", list.option.URL, ", detour: ", list.option.DownloadDetour)
		} else {
			list.dialer = s.outbound.Default()
			s.debug("filter list uses default outbound: ", list.option.URL)
		}
	}
	if s.store == nil {
		return nil
	}
	if saved := s.store.LoadFilterList(cacheTag(list.option.URL)); saved != nil {
		list.content = saved.Content
		list.lastUpdated = saved.LastUpdated
		list.lastEtag = saved.LastEtag
		s.debug("filter list loaded from cache: ", list.option.URL, ", bytes: ", len(list.content))
	}
	return nil
}

func (s *Service) updateEngine(ctx context.Context) error {
	s.rebuildMu.Lock()
	defer s.rebuildMu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}

	s.debugContext(ctx, "engine update started")
	ruleSets := make([]adblockrust.RuleSet, 0, 1+len(s.filterLists))
	var companion companionRules
	var htmlFilters htmlFilterRules
	var advanced advancedRules
	ruleCount := 0
	inlineRules := s.inlineRules()
	if len(inlineRules) > 0 {
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
		s.debugContext(ctx, "inline rules loaded: ", len(parsedInline.Rules))
	}
	for index := range s.filterLists {
		var updated bool
		list := &s.filterLists[index]

		if list.shouldUpdate() {
			updated = true
			s.debugContext(ctx, "filter list update started: ", list.option.URL)
			if err := s.fetchFilterList(ctx, list); err != nil {
				if list.title != "" {
					s.logger.Warn(fmt.Sprintf("fetch adblock filter list \"%s\" (%s): %v", list.title, list.option.URL, err))
				} else {
					s.logger.Warn(fmt.Sprintf("fetch adblock filter list \"%s\": %v", list.option.URL, err))
				}
				s.debugContext(ctx, "filter list update failed: ", list.option.URL, ": ", err)
			}
		}

		// Parse whatever content is available — either freshly fetched or primed
		// from the cache by prepareFilterList. This is the single classification
		// point that splits a list into network rules and the advanced/companion/
		// html components, so every rule kind stays in sync between a first run
		// (content fetched) and a restart (content loaded from cache).
		var parsedFilter parsedFilter
		if len(list.content) > 0 {
			parsedFilter = parseFilterLinesWithEnvironment(list.content, s.options.Environment)
			list.applyParsed(parsedFilter)
		}
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

		if !updated {
			continue
		}

		parsedTitle := list.title
		if parsedTitle == "" {
			parsedTitle = list.option.URL
		}

		s.logger.Debug(fmt.Sprintf("parsed adblock filter list \"%s\" (%d rules), expires in %s", parsedTitle, len(parsedFilter.Rules), list.interval))
	}

	if ruleCount == 0 && companion.empty() && htmlFilters.empty() && advanced.empty() {
		s.debugContext(ctx, "engine update failed: no valid filters")
		return E.New("no valid adblock filters")
	}
	s.debugContext(ctx, "engine build started, filters: ", ruleCount)
	engine, err := adblockrust.NewEngineWithRuleSets(ruleSets, s.options.AdblockResources)
	if err != nil {
		s.debugContext(ctx, "engine build failed: ", err)
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
		s.debugContext(ctx, "engine update canceled: ", ctx.Err())
		return ctx.Err()
	}
	s.releaseStoredFilterContent()
	s.clearCheckCache()
	s.debugContext(ctx, "check cache cleared")
	if oldEngine != nil {
		s.debugContext(ctx, "old engine closing")
		_ = oldEngine.close()
	}
	s.logger.Info("adblock engine ready with ", ruleCount, " filters")
	return nil
}

func adblockRuleFormat(format option.AdblockFilterFormat) adblockrust.RuleFormat {
	switch format {
	case option.AdblockFilterFormatHosts:
		return adblockrust.RuleFormatHosts
	default:
		return adblockrust.RuleFormatStandard
	}
}

func adblockRulePermissions(trusted bool) uint8 {
	if trusted {
		return 0xff
	}
	return 0
}

func (s *Service) inlineRules() []string {
	if s.options.Filters == nil {
		return nil
	}
	rules := make([]string, 0, len(s.options.Filters.Rules))
	for _, rule := range s.options.Filters.Rules {
		rule = strings.TrimSpace(rule)
		if rule != "" {
			rules = append(rules, rule)
		}
	}
	return rules
}

func (s *Service) fetchFilterList(ctx context.Context, list *filterList) error {
	s.debugContext(ctx, "fetch adblock filter list: ", list.option.URL)
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, list.option.URL, nil)
	if err != nil {
		return err
	}
	if list.lastEtag != "" {
		request.Header.Set("If-None-Match", list.lastEtag)
		s.debugContext(ctx, "fetch adblock filter list with etag: ", list.option.URL)
	}
	response, err := s.httpClient(list).Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	switch response.StatusCode {
	case http.StatusOK:
	case http.StatusNotModified:
		list.lastUpdated = time.Now()
		s.debugContext(ctx, "filter list not modified: ", list.option.URL)
		if s.store != nil {
			if saved := s.store.LoadFilterList(cacheTag(list.option.URL)); saved != nil {
				if len(list.content) == 0 {
					list.content = saved.Content
				}
				saved.LastUpdated = list.lastUpdated
				_ = s.store.SaveFilterList(cacheTag(list.option.URL), saved)
			}
		}
		return nil
	default:
		return E.New("unexpected status: ", response.Status)
	}
	content, err := io.ReadAll(response.Body)
	if err != nil {
		return err
	}
	list.content = content
	list.lastUpdated = time.Now()
	list.lastEtag = response.Header.Get("Etag")
	s.debugContext(ctx, "filter list fetched: ", list.option.URL, ", bytes: ", len(content), ", etag: ", list.lastEtag != "")
	if s.store != nil {
		_ = s.store.SaveFilterList(cacheTag(list.option.URL), &adapter.SavedBinary{
			Content:     content,
			LastUpdated: list.lastUpdated,
			LastEtag:    list.lastEtag,
		})
	}
	return nil
}

func (s *Service) httpClient(list *filterList) *http.Client {
	transport := &http.Transport{
		ForceAttemptHTTP2:   true,
		TLSHandshakeTimeout: C.TCPTimeout,
		TLSClientConfig: &tls.Config{
			Time:    ntp.TimeFuncFromContext(s.ctx),
			RootCAs: adapter.RootPoolFromContext(s.ctx),
		},
	}
	if list.dialer != nil {
		transport.DialContext = func(ctx context.Context, network string, address string) (net.Conn, error) {
			return list.dialer.DialContext(ctx, network, M.ParseSocksaddr(address))
		}
	}
	return &http.Client{
		Transport: transport,
		Timeout:   C.TCPTimeout,
	}
}

func FilterLastModified(lastModified string) string {
	if lastModified == "%timestamp%" {
		return ""
	}
	return lastModified
}
