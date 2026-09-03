//go:build with_adblock

package adblock

import (
	"context"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	"github.com/sagernet/sing-box/experimental/adblock/consts"
	"github.com/sagernet/sing-box/experimental/adblock/db"
	"github.com/sagernet/sing-box/experimental/adblock/httpconn"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/contrab/freelru"
	"github.com/sagernet/sing/service"
	"golang.org/x/net/http2"
)

const defaultFilterUpdateInterval = time.Hour * 8

var _ adapter.AdblockService = (*Service)(nil)

type Service struct {
	ctx      context.Context
	cancel   context.CancelFunc
	logger   log.ContextLogger
	options  option.AdblockOptions
	outbound adapter.OutboundManager
	store    adapter.AdblockDatabase

	access       sync.RWMutex
	engine       *managedEngine
	companion    companionRules
	htmlFilters  htmlFilterRules
	advanced     advancedRules
	updateTicker *time.Ticker
	filterLists  []filterList

	// rebuildMu serializes all engine rebuilds (background updateEngine and
	// manual ReloadEngine) so filterLists mutations never overlap.
	rebuildMu sync.Mutex
	// reloadChan feeds the throttled reload worker (loopReload).
	reloadChan chan struct{}
	workers    sync.WaitGroup

	tlsCA *tlsCertificateAuthority

	constraints *Constraints

	httpServers  *TypedPool[*http.Server]
	http2Servers *TypedPool[*http.Server]

	requestCache *freelru.Cache[adblockCheckCacheKey, adblockRequestCacheValue]

	stats *serviceStats

	cosmeticSessionsAccess sync.Mutex
	cosmeticSessions       map[string]cosmeticSession
	tlsExclusions          sync.Map

	utls   consts.UTLSFingerprintID
	cronet bool
}

func adblockDebugMessage(args ...any) string {
	return fmt.Sprint(args...)
}

func (s *Service) debug(args ...any) {
	if s != nil && s.logger != nil {
		s.logger.Debug(adblockDebugMessage(args...))
	}
}

func (s *Service) debugContext(ctx context.Context, args ...any) {
	if s != nil && s.logger != nil {
		s.logger.DebugContext(ctx, adblockDebugMessage(args...))
	}
}

func New(ctx context.Context, logger log.ContextLogger, options option.AdblockOptions, logLevels ...log.Level) (adapter.AdblockService, error) {
	if !options.Enabled || !options.HasFilters() {
		if logger != nil {
			logger.Debug("adblock disabled or has no filters")
		}
		return nil, nil
	}
	if err := options.Validate(); err != nil {
		return nil, err
	}
	setAdblockRegexpLogLevel(logLevels...)

	ctx, cancel := context.WithCancel(ctx)
	service := &Service{
		ctx:      ctx,
		cancel:   cancel,
		logger:   logger,
		options:  options,
		outbound: service.FromContext[adapter.OutboundManager](ctx),
		httpServers: NewTypedPool[*http.Server]().SetConstructor(func() *http.Server {
			return &http.Server{}
		}),
		http2Servers: NewTypedPool[*http.Server]().SetConstructor(func() *http.Server {
			srv := &http.Server{}
			_ = http2.ConfigureServer(srv, &http2.Server{})

			return srv
		}),
		requestCache: MustNewLRU[adblockCheckCacheKey, adblockRequestCacheValue](defaultCheckCacheSize),
		stats:        newServiceStats(logger),
		store:        db.New(ctx, options.DatabasePath),
		reloadChan:   make(chan struct{}, 1),
	}
	if options.Filtering.Mode == "" {
		service.options.Filtering.Mode = option.AdblockModeDefault
	}
	if options.TLS != nil && options.TLS.Enabled {
		service.debug("adblock TLS enabled")
		tlsCA, err := newTLSCertificateAuthority(options.TLS, logger)
		if err != nil {
			cancel()
			return nil, err
		}
		service.tlsCA = tlsCA
		service.cronet = options.TLS.Cronet

		if service.cronet {
			if !httpconn.SupportsCronet {
				cancel()
				return nil, E.New("adblock TLS Cronet is configured but Cronet is not included in this build, rebuild with -tags with_adblock_cronet")
			}
			service.utls = consts.Invalid
		} else if options.TLS.UTLS != nil {
			if !httpconn.SupportsUTLS {
				cancel()
				return nil, E.New("adblock TLS uTLS fingerprint is configured but uTLS is not included in this build, rebuild with -tags with_utls")
			}
			fp, err := httpconn.UTLSFingerprintIDFromString(*options.TLS.UTLS)
			if err != nil {
				cancel()
				return nil, err
			}

			service.utls = fp
		} else {
			service.utls = consts.Invalid
		}
	} else {
		service.debug("adblock TLS disabled")
	}
	constraints, err := compileConstraints(options.Constraints)
	if err != nil {
		cancel()
		return nil, err
	}
	service.constraints = constraints
	if constraints != nil {
		service.debug("adblock constraints enabled")
	}
	if options.Filters != nil {
		for _, listOptions := range options.Filters.FilterLists {
			if listOptions.URL == "" {
				continue
			}
			interval := time.Duration(listOptions.UpdateInterval)
			if interval <= 0 {
				interval = defaultFilterUpdateInterval
			}
			service.filterLists = append(service.filterLists, filterList{
				option:   listOptions,
				interval: interval,
			})
		}
	}
	service.debug("adblock service initialized, mode: ", service.options.Filtering.Mode, ", filter lists: ", len(service.filterLists), ", inline rules: ", len(service.inlineRules()))
	return service, nil
}

func (s *Service) Start(stage adapter.StartStage) error {
	s.debug("start stage: ", stage)
	switch stage {
	case adapter.StartStateInitialize:
		if err := s.store.Start(stage); err != nil {
			return err
		}
		s.stats.setStatsContainer(s.store)
	case adapter.StartStateStart:
		for index := range s.filterLists {
			s.debug("prepare adblock filter list: ", s.filterLists[index].option.URL)
			if err := s.prepareFilterList(index); err != nil {
				return err
			}
		}
	case adapter.StartStatePostStart:
		s.updateTicker = time.NewTicker(time.Hour * 4)
		s.debug("update ticker started")
		s.workers.Go(func() {
			if err := s.updateEngine(s.ctx); err != nil {
				s.logger.Warn("initialize adblock: ", err)
			}
			s.loopUpdate()
		})
		s.workers.Go(s.loopReload)
		if s.tlsCA != nil {
			s.workers.Go(s.loopTLSExclusionCleanup)
		}
	}
	return nil
}

func (s *Service) loopUpdate() {
	for {
		select {
		case <-s.ctx.Done():
			s.debug("update loop stopped: ", s.ctx.Err())
			return
		case <-s.updateTicker.C:
			s.debug("scheduled update started")
			if err := s.updateEngine(s.ctx); err != nil {
				s.logger.Error("update adblock: ", err)
			}
		}
	}
}

func (s *Service) HasProcessConstraints() bool {
	return s.constraints != nil && s.constraints.hasProcessConstraint
}

func (s *Service) Stats() adapter.AdblockStats {
	return s.stats
}

func (s *Service) defaultDialer() N.Dialer {
	if s.outbound == nil {
		return nil
	}
	return s.outbound.Default()
}

func (s *Service) Name() string {
	return "adblock"
}

func (s *Service) readyEngine() (*managedEngine, adblockrust.Engine) {
	s.access.RLock()
	defer s.access.RUnlock()
	if s.engine == nil {
		return nil, nil
	}
	engine := s.engine
	return engine, engine.retain()
}

func (s *Service) readyCompanion() companionRules {
	s.access.RLock()
	defer s.access.RUnlock()
	return s.companion
}

func (s *Service) readyHTMLFilters() htmlFilterRules {
	s.access.RLock()
	defer s.access.RUnlock()
	return s.htmlFilters
}

func (s *Service) readyAdvancedRules() advancedRules {
	s.access.RLock()
	defer s.access.RUnlock()
	return s.advanced
}

func (s *Service) Close() error {
	s.debug("closing adblock service")
	s.cancel()
	if s.updateTicker != nil {
		s.updateTicker.Stop()
		s.debug("update ticker stopped")
	}
	s.workers.Wait()
	s.rebuildMu.Lock()
	defer s.rebuildMu.Unlock()
	s.stats.stop()
	s.access.Lock()
	engine := s.engine
	s.engine = nil
	s.access.Unlock()
	if s.store != nil {
		_ = s.store.Close()
	}
	if engine != nil {
		err := engine.forceClose()
		s.debug("engine closed: ", err)
		return err
	}
	s.debug("service closed")
	return nil
}

func (s *Service) constraintsMatch(metadata *adapter.InboundContext) bool {
	if s.constraints == nil {
		return true
	}
	return s.constraints.Match(metadata)
}

func (s *Service) domainCheckNoStats(engine adblockrust.Engine, domain string, scheme string, requestType string) adblockrust.CheckResult {
	if domain == "" {
		return adblockrust.CheckResult{}
	}
	if domain[len(domain)-1] == '.' {
		domain = domain[:len(domain)-1]
	}
	requestURL := scheme + "://" + domain + "/"
	sourceURL := ""
	if requestType == "document" || requestType == "subdocument" {
		sourceURL = requestURL
	}
	result, err := s.requestCheck(engine, requestURL, sourceURL, requestType, adblockrust.RequestMethodGet)
	if err != nil {
		s.debug("domain check failed: ", requestURL, ", type: ", requestType, ", error: ", err)
		return adblockrust.CheckResult{}
	}
	return result
}

func (s *Service) domainException(engine adblockrust.Engine, domain string, scheme string, requestType string) bool {
	if domain == "" {
		return false
	}
	if domain[len(domain)-1] == '.' {
		domain = domain[:len(domain)-1]
	}
	requestURL := scheme + "://" + domain + "/"
	sourceURL := ""
	if requestType == "document" || requestType == "subdocument" {
		sourceURL = requestURL
	}
	matched := s.requestException(engine, requestURL, sourceURL, requestType, adblockrust.RequestMethodGet)
	s.debug("domain exception check: ", requestURL, ", type: ", requestType, ", matched: ", matched)
	return matched
}
