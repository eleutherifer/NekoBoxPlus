package transport

import (
	"cmp"
	"context"
	"errors"
	"io"
	"os"
	"slices"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	F "github.com/sagernet/sing/common/format"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
)

var _ adapter.DNSTransport = (*BalancerTransport)(nil)

func RegisterBalancer(registry *dns.TransportRegistry) {
	dns.RegisterTransport[option.BalancerDNSServerOptions](registry, C.DNSTypeBalancer, NewBalancer)
}

type BalancerTransport struct {
	dns.TransportAdapter

	access    sync.Mutex
	started   map[adapter.StartStage]bool
	closed    bool
	measured  bool
	measuring bool
	measureCh chan struct{}
	children  []*balancerChild

	queryDeadline time.Duration
}

type balancerChild struct {
	transport adapter.DNSTransport
	tag       string
}

type balancerMeasureResult struct {
	child    *balancerChild
	response *mDNS.Msg
	err      error
	elapsed  time.Duration
}

func NewBalancer(ctx context.Context, logger log.ContextLogger, tag string, options option.BalancerDNSServerOptions) (adapter.DNSTransport, error) {
	if len(options.Servers) == 0 {
		return nil, E.New("missing balancer servers")
	}
	registry := service.FromContext[adapter.DNSTransportRegistry](ctx)
	if registry == nil {
		return nil, E.New("missing DNS transport registry")
	}
	parentManager := service.FromContext[adapter.DNSTransportManager](ctx)
	manager := newBalancerLocalManager(parentManager)
	childCtx := service.ExtendContext(ctx)
	childCtx = service.ContextWith[adapter.DNSTransportManager](childCtx, manager)
	children := make([]*balancerChild, 0, len(options.Servers))
	seen := make(map[string]bool, len(options.Servers))
	childTags := make([]string, len(options.Servers))
	hiddenTags := make([]string, len(options.Servers))
	for i, server := range options.Servers {
		childTag := server.Tag
		if childTag == "" {
			childTag = F.ToString(i)
		}
		if seen[childTag] {
			return nil, E.New("duplicate balancer child server tag: ", childTag)
		}
		seen[childTag] = true
		hiddenTag := F.ToString(tag, "/", childTag)
		if tag == "" {
			hiddenTag = F.ToString("balancer/", childTag)
		}
		childTags[i] = childTag
		hiddenTags[i] = hiddenTag
		manager.reserve(childTag, hiddenTag)
	}
	for i, server := range options.Servers {
		childTag := childTags[i]
		hiddenTag := hiddenTags[i]
		transport, err := registry.CreateDNSTransport(childCtx, logger, hiddenTag, server.Type, server.Options)
		if err != nil {
			return nil, E.Cause(err, "create balancer child server[", i, "]")
		}
		child := &balancerChild{
			transport: transport,
			tag:       childTag,
		}
		children = append(children, child)
		manager.set(childTag, hiddenTag, transport)
	}
	return &BalancerTransport{
		TransportAdapter: dns.NewTransportAdapter(C.DNSTypeBalancer, tag, manager.externalDependencies(children)),
		started:          make(map[adapter.StartStage]bool),
		children:         children,
		queryDeadline:    time.Duration(options.QueryDeadline),
	}, nil
}

func (t *BalancerTransport) Start(stage adapter.StartStage) error {
	t.access.Lock()
	if t.closed {
		t.access.Unlock()
		return E.New("balancer transport is closed")
	}
	if t.started[stage] {
		t.access.Unlock()
		return nil
	}
	t.started[stage] = true
	children := slices.Clone(t.children)
	t.access.Unlock()
	if stage == adapter.StartStateStart {
		return t.startChildren(children)
	}
	for _, child := range children {
		err := adapter.LegacyStart(child.transport, stage)
		if err != nil {
			return E.Cause(err, stage, " dns/", child.transport.Type(), "[", child.tag, "]")
		}
	}
	return nil
}

func (t *BalancerTransport) startChildren(children []*balancerChild) error {
	started := make(map[string]bool, len(children)*2)
	for {
		var canContinue bool
	startOne:
		for _, child := range children {
			if started[child.tag] {
				continue
			}
			for _, dependency := range child.transport.Dependencies() {
				if !started[dependency] {
					if t.hasChild(children, dependency) {
						continue startOne
					}
				}
			}
			started[child.tag] = true
			started[child.transport.Tag()] = true
			canContinue = true
			err := adapter.LegacyStart(child.transport, adapter.StartStateStart)
			if err != nil {
				return E.Cause(err, "start dns/", child.transport.Type(), "[", child.tag, "]")
			}
		}
		if len(started) >= len(children)*2 {
			return nil
		}
		if !canContinue {
			pending := slices.Collect(func(yield func(string) bool) {
				for _, child := range children {
					if !started[child.tag] && !yield(child.tag) {
						return
					}
				}
			})
			return E.New("circular balancer child server dependency: ", pending)
		}
	}
}

func (t *BalancerTransport) hasChild(children []*balancerChild, tag string) bool {
	for _, child := range children {
		if child.tag == tag || child.transport.Tag() == tag {
			return true
		}
	}
	return false
}

func (t *BalancerTransport) Close() error {
	t.access.Lock()
	if t.closed {
		t.access.Unlock()
		return nil
	}
	t.closed = true
	children := slices.Clone(t.children)
	t.access.Unlock()
	var err error
	for _, child := range children {
		if closer, ok := child.transport.(io.Closer); ok {
			err = E.Append(err, closer.Close(), func(err error) error {
				return E.Cause(err, "close dns/", child.transport.Type(), "[", child.tag, "]")
			})
		}
	}
	return err
}

func (t *BalancerTransport) Reset() {
	t.access.Lock()
	children := slices.Clone(t.children)
	t.measured = false
	t.access.Unlock()
	for _, child := range children {
		child.transport.Reset()
	}
}

func (t *BalancerTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	for {
		t.access.Lock()
		if t.closed {
			t.access.Unlock()
			return nil, E.New("balancer transport is closed")
		}
		if t.measured {
			children := slices.Clone(t.children)
			t.access.Unlock()
			return t.exchangeRanked(ctx, message, children)
		}
		if !t.measuring {
			t.measuring = true
			t.measureCh = make(chan struct{})
			children := slices.Clone(t.children)
			t.access.Unlock()
			response, err := t.measure(ctx, message, children)
			return response, err
		}
		measureCh := t.measureCh
		t.access.Unlock()
		select {
		case <-measureCh:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
}

func (t *BalancerTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	go func() {
		callback(t.Exchange(ctx, message))
	}()
}

func (t *BalancerTransport) measure(ctx context.Context, message *mDNS.Msg, children []*balancerChild) (*mDNS.Msg, error) {
	results := make([]balancerMeasureResult, len(children))
	var wg sync.WaitGroup
	for i, child := range children {
		wg.Go(func() {
			query := message.Copy()
			queryCtx, cancel := t.withQueryTimeout(ctx, true)
			defer cancel()
			start := time.Now()
			response, err := child.transport.Exchange(queryCtx, query)
			if err == nil && response == nil {
				err = E.New("empty DNS response")
			}
			results[i] = balancerMeasureResult{
				child:    child,
				response: response,
				err:      err,
				elapsed:  time.Since(start),
			}
		})
	}
	wg.Wait()
	slices.SortStableFunc(results, func(a, b balancerMeasureResult) int {
		if a.err == nil && b.err != nil {
			return -1
		}
		if a.err != nil && b.err == nil {
			return 1
		}
		if a.err == nil && b.err == nil {
			return cmp.Compare(a.elapsed, b.elapsed)
		}
		return 0
	})
	ranked := make([]*balancerChild, 0, len(results))
	var (
		response *mDNS.Msg
		errs     []error
	)
	for _, result := range results {
		ranked = append(ranked, result.child)
		if result.err == nil && response == nil {
			response = result.response
		} else if result.err != nil {
			errs = append(errs, result.err)
		}
	}
	t.access.Lock()
	t.children = ranked
	t.measured = true
	t.measuring = false
	close(t.measureCh)
	t.measureCh = nil
	t.access.Unlock()
	if response != nil {
		return response, nil
	}
	return nil, errors.Join(errs...)
}

func (t *BalancerTransport) exchangeRanked(ctx context.Context, message *mDNS.Msg, children []*balancerChild) (*mDNS.Msg, error) {
	var (
		errs   []error
		failed []*balancerChild
	)
	for index, child := range children {
		queryCtx, cancel := t.withQueryTimeout(ctx, false)
		response, err := child.transport.Exchange(queryCtx, message.Copy())
		cancel()
		if err == nil && response == nil {
			err = E.New("empty DNS response")
		}
		if err == nil {
			if index > 0 || len(failed) > 0 {
				t.promote(child, failed)
			}
			return response, nil
		}
		errs = append(errs, err)
		failed = append(failed, child)
	}
	if len(failed) > 0 {
		t.demoteFailed(failed)
	}
	return nil, errors.Join(errs...)
}

func (t *BalancerTransport) withQueryTimeout(ctx context.Context, measure bool) (context.Context, context.CancelFunc) {
	queryDeadline := t.queryDeadline
	if queryDeadline == 0 && measure {
		queryDeadline = C.DNSTimeout
	}
	if queryDeadline == 0 {
		return ctx, func() {}
	}
	return context.WithTimeout(ctx, queryDeadline)
}

func (t *BalancerTransport) promote(success *balancerChild, failed []*balancerChild) {
	t.access.Lock()
	defer t.access.Unlock()
	ranked := make([]*balancerChild, 0, len(t.children))
	ranked = append(ranked, success)
	for _, child := range t.children {
		if child == success || slices.Contains(failed, child) {
			continue
		}
		ranked = append(ranked, child)
	}
	ranked = append(ranked, failed...)
	t.children = ranked
}

func (t *BalancerTransport) demoteFailed(failed []*balancerChild) {
	t.access.Lock()
	defer t.access.Unlock()
	ranked := make([]*balancerChild, 0, len(t.children))
	for _, child := range t.children {
		if slices.Contains(failed, child) {
			continue
		}
		ranked = append(ranked, child)
	}
	ranked = append(ranked, failed...)
	t.children = ranked
}

type balancerLocalManager struct {
	parent         adapter.DNSTransportManager
	transportByTag map[string]adapter.DNSTransport
}

func newBalancerLocalManager(parent adapter.DNSTransportManager) *balancerLocalManager {
	return &balancerLocalManager{
		parent:         parent,
		transportByTag: make(map[string]adapter.DNSTransport),
	}
}

func (m *balancerLocalManager) reserve(tag string, hiddenTag string) {
	placeholder := &balancerPlaceholderTransport{tag: hiddenTag}
	m.transportByTag[tag] = placeholder
	m.transportByTag[hiddenTag] = placeholder
}

func (m *balancerLocalManager) set(tag string, hiddenTag string, transport adapter.DNSTransport) {
	if placeholder, ok := m.transportByTag[tag].(*balancerPlaceholderTransport); ok {
		placeholder.set(transport)
	}
	if placeholder, ok := m.transportByTag[hiddenTag].(*balancerPlaceholderTransport); ok {
		placeholder.set(transport)
	}
	m.transportByTag[tag] = transport
	m.transportByTag[hiddenTag] = transport
}

func (m *balancerLocalManager) Start(stage adapter.StartStage) error {
	return nil
}

func (m *balancerLocalManager) Close() error {
	return nil
}

func (m *balancerLocalManager) Transports() []adapter.DNSTransport {
	transports := make([]adapter.DNSTransport, 0, len(m.transportByTag))
	seen := make(map[adapter.DNSTransport]bool)
	for _, transport := range m.transportByTag {
		if seen[transport] {
			continue
		}
		seen[transport] = true
		transports = append(transports, transport)
	}
	return transports
}

func (m *balancerLocalManager) Transport(tag string) (adapter.DNSTransport, bool) {
	transport, loaded := m.transportByTag[tag]
	if loaded {
		return transport, true
	}
	if m.parent == nil {
		return nil, false
	}
	return m.parent.Transport(tag)
}

func (m *balancerLocalManager) Default() adapter.DNSTransport {
	if m.parent == nil {
		return nil
	}
	return m.parent.Default()
}

func (m *balancerLocalManager) FakeIP() adapter.FakeIPTransport {
	if m.parent == nil {
		return nil
	}
	return m.parent.FakeIP()
}

func (m *balancerLocalManager) Remove(tag string) error {
	return os.ErrInvalid
}

func (m *balancerLocalManager) Create(ctx context.Context, logger log.ContextLogger, tag string, outboundType string, options any) error {
	return os.ErrInvalid
}

func (m *balancerLocalManager) externalDependencies(children []*balancerChild) []string {
	var dependencies []string
	for _, child := range children {
		for _, dependency := range child.transport.Dependencies() {
			if _, loaded := m.transportByTag[dependency]; loaded {
				continue
			}
			if !slices.Contains(dependencies, dependency) {
				dependencies = append(dependencies, dependency)
			}
		}
	}
	return dependencies
}

type balancerPlaceholderTransport struct {
	access    sync.RWMutex
	tag       string
	transport adapter.DNSTransport
}

func (t *balancerPlaceholderTransport) set(transport adapter.DNSTransport) {
	t.access.Lock()
	t.transport = transport
	t.access.Unlock()
}

func (t *balancerPlaceholderTransport) get() (adapter.DNSTransport, error) {
	t.access.RLock()
	transport := t.transport
	t.access.RUnlock()
	if transport == nil {
		return nil, E.New("balancer child server is not initialized: ", t.tag)
	}
	return transport, nil
}

func (t *balancerPlaceholderTransport) Start(stage adapter.StartStage) error {
	transport, err := t.get()
	if err != nil {
		return err
	}
	return adapter.LegacyStart(transport, stage)
}

func (t *balancerPlaceholderTransport) Close() error {
	transport, err := t.get()
	if err != nil {
		return err
	}
	return transport.Close()
}

func (t *balancerPlaceholderTransport) Type() string {
	transport, err := t.get()
	if err != nil {
		return C.DNSTypeBalancer
	}
	return transport.Type()
}

func (t *balancerPlaceholderTransport) Tag() string {
	return t.tag
}

func (t *balancerPlaceholderTransport) Dependencies() []string {
	transport, err := t.get()
	if err != nil {
		return nil
	}
	return transport.Dependencies()
}

func (t *balancerPlaceholderTransport) Reset() {
	transport, err := t.get()
	if err != nil {
		return
	}
	transport.Reset()
}

func (t *balancerPlaceholderTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	transport, err := t.get()
	if err != nil {
		return nil, err
	}
	return transport.Exchange(ctx, message)
}

func (t *balancerPlaceholderTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	transport, err := t.get()
	if err != nil {
		callback(nil, err)
		return
	}
	transport.ExchangeAsync(ctx, message, callback)
}
