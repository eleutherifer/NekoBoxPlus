package endpoint

import (
	"context"
	"os"
	"slices"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/taskmonitor"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/service"
)

var _ adapter.EndpointManager = (*Manager)(nil)

type Manager struct {
	ctx           context.Context
	logger        log.ContextLogger
	registry      adapter.EndpointRegistry
	access        sync.Mutex
	started       bool
	stage         adapter.StartStage
	endpoints     []adapter.Endpoint
	endpointByTag map[string]adapter.Endpoint
}

func NewManager(ctx context.Context, logger log.ContextLogger, registry adapter.EndpointRegistry) *Manager {
	return &Manager{
		ctx:           ctx,
		logger:        logger,
		registry:      registry,
		endpointByTag: make(map[string]adapter.Endpoint),
	}
}

func (m *Manager) Start(stage adapter.StartStage) error {
	m.access.Lock()
	if m.started && m.stage >= stage {
		m.access.Unlock()
		panic("already started")
	}
	m.started = true
	m.stage = stage
	endpoints := slices.Clone(m.endpoints)
	m.access.Unlock()
	if stage == adapter.StartStateStart {
		// started with outbound manager
		return nil
	}
	if stage == adapter.StartStatePostStart {
		// Domain peers may resolve through another endpoint at post-start. Follow
		// detour dependencies, including those hidden behind an outbound/selector.
		var err error
		endpoints, err = orderEndpointDependencies(endpoints, service.FromContext[adapter.OutboundManager](m.ctx))
		if err != nil {
			return err
		}
	}
	// Do not hold access while calling endpoints: bootstrap DNS can look up a
	// detour through OutboundManager, which calls back into Manager.Get.
	for _, endpoint := range endpoints {
		name := "endpoint/" + endpoint.Type() + "[" + endpoint.Tag() + "]"
		done := adapter.LogElapsed(m.logger, stage, " ", name)
		err := adapter.LegacyStart(endpoint, stage)
		done()
		if err != nil {
			return E.Cause(err, stage, " ", name)
		}
	}
	return nil
}

func orderEndpointDependencies(endpoints []adapter.Endpoint, manager adapter.OutboundManager) ([]adapter.Endpoint, error) {
	byTag := make(map[string]adapter.Endpoint, len(endpoints))
	for _, endpoint := range endpoints {
		byTag[endpoint.Tag()] = endpoint
	}
	state := make(map[string]uint8)
	ordered := make([]adapter.Endpoint, 0, len(endpoints))
	var visit func(adapter.Outbound) error
	visit = func(outbound adapter.Outbound) error {
		tag := outbound.Tag()
		switch state[tag] {
		case 1:
			return E.New("circular endpoint dependency: ", tag)
		case 2:
			return nil
		}
		state[tag] = 1
		for _, dependency := range outbound.Dependencies() {
			var next adapter.Outbound = byTag[dependency]
			if next == nil && manager != nil {
				next, _ = manager.Outbound(dependency)
			}
			if next == nil {
				return E.New("endpoint dependency not found: ", dependency)
			}
			if err := visit(next); err != nil {
				return err
			}
		}
		state[tag] = 2
		if endpoint, loaded := byTag[tag]; loaded {
			ordered = append(ordered, endpoint)
		}
		return nil
	}
	for _, endpoint := range endpoints {
		if err := visit(endpoint); err != nil {
			return nil, err
		}
	}
	return ordered, nil
}

func (m *Manager) Close() error {
	m.access.Lock()
	defer m.access.Unlock()
	if !m.started {
		return nil
	}
	m.started = false
	endpoints := m.endpoints
	m.endpoints = nil
	monitor := taskmonitor.New(m.logger, C.StopTimeout)
	var err error
	for _, endpoint := range endpoints {
		name := "endpoint/" + endpoint.Type() + "[" + endpoint.Tag() + "]"
		done := adapter.LogElapsed(m.logger, "close ", name)
		monitor.Start("close ", name)
		err = E.Append(err, endpoint.Close(), func(err error) error {
			return E.Cause(err, "close ", name)
		})
		monitor.Finish()
		done()
	}
	return nil
}

func (m *Manager) Endpoints() []adapter.Endpoint {
	m.access.Lock()
	defer m.access.Unlock()
	return m.endpoints
}

func (m *Manager) Get(tag string) (adapter.Endpoint, bool) {
	m.access.Lock()
	defer m.access.Unlock()
	endpoint, found := m.endpointByTag[tag]
	return endpoint, found
}

func (m *Manager) Remove(tag string) error {
	m.access.Lock()
	endpoint, found := m.endpointByTag[tag]
	if !found {
		m.access.Unlock()
		return os.ErrInvalid
	}
	delete(m.endpointByTag, tag)
	index := common.Index(m.endpoints, func(it adapter.Endpoint) bool {
		return it == endpoint
	})
	if index == -1 {
		panic("invalid endpoint index")
	}
	m.endpoints = append(m.endpoints[:index], m.endpoints[index+1:]...)
	started := m.started
	m.access.Unlock()
	if started {
		return endpoint.Close()
	}
	return nil
}

func (m *Manager) Create(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, outboundType string, options any) error {
	endpoint, err := m.registry.Create(ctx, router, logger, tag, outboundType, options)
	if err != nil {
		return err
	}
	m.access.Lock()
	defer m.access.Unlock()
	if m.started {
		name := "endpoint/" + endpoint.Type() + "[" + endpoint.Tag() + "]"
		for _, stage := range adapter.ListStartStages {
			done := adapter.LogElapsed(m.logger, stage, " ", name)
			err = adapter.LegacyStart(endpoint, stage)
			done()
			if err != nil {
				return E.Cause(err, stage, " ", name)
			}
		}
	}
	if existsEndpoint, loaded := m.endpointByTag[tag]; loaded {
		if m.started {
			err = existsEndpoint.Close()
			if err != nil {
				return E.Cause(err, "close endpoint/", existsEndpoint.Type(), "[", existsEndpoint.Tag(), "]")
			}
		}
		existsIndex := common.Index(m.endpoints, func(it adapter.Endpoint) bool {
			return it == existsEndpoint
		})
		if existsIndex == -1 {
			panic("invalid endpoint index")
		}
		m.endpoints = append(m.endpoints[:existsIndex], m.endpoints[existsIndex+1:]...)
	}
	m.endpoints = append(m.endpoints, endpoint)
	m.endpointByTag[tag] = endpoint
	return nil
}
