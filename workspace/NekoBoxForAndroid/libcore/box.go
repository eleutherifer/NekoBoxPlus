package libcore

import (
	"context"
	"errors"
	"fmt"
	"libcore/device"
	"log"
	"os"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/common/trafficcontrol"
	adblockRegexpr "github.com/sagernet/sing-box/experimental/adblock/regexpr"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/filemanager"
	"github.com/sagernet/sing/service/pause"
)

var mainInstance *BoxInstance

func VersionBox() string {
	version := []string{
		"sing-box-plus: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func VersionModules() string {
	return strings.Join([]string{
		"adblock-rust: " + VersionAdblockRust,
		"adblock-resources: " + VersionAdblockResources,
		"uBlock: " + VersionUBlock,
		"amneziawg-go: " + VersionAmnezia,
		"ByeDPI: " + VersionByeDPI,
		"MasterDnsVPN: " + VersionMasterDnsVPN,
	}, "\n")
}

func ResetAllConnections(system bool) {
	if !system {
		log.Println("TODO: Reset user connections")
		return
	}
	if mainInstance == nil {
		return
	}
	mainInstance.access.Lock()
	defer mainInstance.access.Unlock()
	if err := mainInstance.resetConnectionsLocked(); err != nil {
		log.Println("Reset system connections failed:", err)
	}
}

type clashTrafficManagerProvider interface {
	TrafficManager() *trafficcontrol.Manager
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	ctx          context.Context
	cancel       context.CancelFunc
	state        int
	closeDone    chan struct{}
	closeErr     error
	hasAmneziaWG bool

	v2api        *boxapi.SbV2rayServer
	selector     *group.Selector
	pauseManager pause.Manager
	logWriter    *boxPlatformLogWriterWrapper
	forTest      bool
	localDNS     LocalDNSTransport
	urlTestReady urlTestReadinessState
}

const (
	boxStateNew = iota
	boxStateStarted
	boxStateClosing
	boxStateClosed
)

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	return newSingBoxInstance(config, localTransport, false)
}

func NewSingBoxInstanceWithPaths(
	config string,
	localTransport LocalDNSTransport,
	routingAssetsPath string,
	routingCachePath string,
) (b *BoxInstance, err error) {
	return newSingBoxInstanceWithRoutingPaths(
		config,
		localTransport,
		false,
		false,
		routingAssetsPath,
		routingCachePath,
	)
}

func newSingBoxInstance(config string, localTransport LocalDNSTransport, forTest bool) (b *BoxInstance, err error) {
	return newSingBoxInstanceWithProtect(config, localTransport, forTest, false)
}

func newSingBoxInstanceWithProtect(
	config string,
	localTransport LocalDNSTransport,
	forTest bool,
	strictProtect bool,
) (b *BoxInstance, err error) {
	return newSingBoxInstanceWithProtectContext(
		context.Background(),
		config,
		localTransport,
		forTest,
		strictProtect,
	)
}

func newSingBoxInstanceWithProtectContext(
	parentCtx context.Context,
	config string,
	localTransport LocalDNSTransport,
	forTest bool,
	strictProtect bool,
) (b *BoxInstance, err error) {
	return newSingBoxInstanceWithRoutingPathsContext(
		parentCtx,
		config,
		localTransport,
		forTest,
		strictProtect,
		"",
		"",
	)
}

func newSingBoxInstanceWithRoutingPaths(
	config string,
	localTransport LocalDNSTransport,
	forTest bool,
	strictProtect bool,
	routingAssetsPath string,
	routingCachePath string,
) (b *BoxInstance, err error) {
	return newSingBoxInstanceWithRoutingPathsContext(
		context.Background(),
		config,
		localTransport,
		forTest,
		strictProtect,
		routingAssetsPath,
		routingCachePath,
	)
}

func newSingBoxInstanceWithRoutingPathsContext(
	parentCtx context.Context,
	config string,
	localTransport LocalDNSTransport,
	forTest bool,
	strictProtect bool,
	routingAssetsPath string,
	routingCachePath string,
) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(parentCtx)
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	ctx = filemanager.WithDefault(ctx, workingPath, tempPath, os.Getuid(), os.Getgid())
	service.MustRegister[adapter.PlatformInterface](ctx, &boxPlatformInterfaceWrapper{
		forTest:       forTest,
		strictProtect: strictProtect,
	})

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %w", err)
	}
	options.Certificate = currentCertificateOptions()
	err = validateByeDPIOptions(options)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("validate config: %w", err)
	}
	hasAmneziaWG := false
	for _, endpoint := range options.Endpoints {
		if endpoint.Type == constant.TypeAwg {
			hasAmneziaWG = true
			break
		}
	}
	waitForAssetExtraction()
	var loadedRoutingResources bool
	if routingAssetsPath != "" && routingCachePath != "" {
		loadedRoutingResources, err = prepareRoutingRuleSetsWithPaths(&options, routingAssetsPath, routingCachePath)
	} else {
		loadedRoutingResources, err = prepareRoutingRuleSets(&options)
	}
	if loadedRoutingResources {
		debug.FreeOSMemory()
	}
	if err != nil {
		cancel()
		return nil, fmt.Errorf("prepare routing rules: %w", err)
	}

	var platformLogWriter *boxPlatformLogWriterWrapper
	var logWriter sblog.PlatformWriter
	if forTest {
		options.Log = &option.LogOptions{
			Disabled: true,
			Level:    "none",
		}
	} else {
		logLevel := sblog.LevelTrace
		if options.Log != nil && options.Log.Level != "" {
			logLevel, err = parseLogLevel(options.Log.Level)
			if err != nil {
				cancel()
				return nil, err
			}
		}
		platformLogWriter = newBoxPlatformLogWriter(logLevel)
		logWriter = platformLogWriter
	}

	// create box
	var instance *box.Box
	func() {
		endV2GeoCacheScope := beginV2GeoCacheScope()
		defer func() {
			clearedLegacyGeoCache := endV2GeoCacheScope()
			options = option.Options{}
			if clearedLegacyGeoCache || err != nil {
				debug.FreeOSMemory()
			}
		}()
		instance, err = box.New(box.Options{
			Options:           options,
			Context:           ctx,
			PlatformLogWriter: logWriter,
		})
	}()
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %w", err)
	}

	b = &BoxInstance{
		Box:          instance,
		ctx:          ctx,
		cancel:       cancel,
		pauseManager: service.FromContext[pause.Manager](ctx),
		logWriter:    platformLogWriter,
		forTest:      forTest,
		localDNS:     localTransport,
		hasAmneziaWG: hasAmneziaWG,
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) SetLogLevel(level string, enabled bool) error {
	parsedLevel, err := parseLogLevel(level)
	if err != nil {
		return err
	}
	if b.logWriter != nil {
		b.logWriter.SetLevel(parsedLevel)
	}
	logOptions := &option.LogOptions{Disabled: !enabled, Level: level}
	adblockRegexpr.SetLogLevel(adblockRegexpr.CalculateLogLevels(parsedLevel, logOptions)...)
	neko_log.SetLogEnabled(enabled)
	return nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == boxStateNew {
		b.state = boxStateStarted
		err = b.Box.Start()
		if err == nil {
			b.watchEndpointAuthentication()
		}
		if err == nil && !b.forTest {
			debug.FreeOSMemory()
		}
		return err
	}
	return errors.New("already started")
}

func (b *BoxInstance) watchEndpointAuthentication() {
	for _, currentEndpoint := range b.Endpoint().Endpoints() {
		switch endpoint := currentEndpoint.(type) {
		case adapter.OpenVPNEndpoint:
			go b.watchOpenVPNAuthentication(endpoint)
		case adapter.OpenConnectEndpoint:
			go b.watchOpenConnectAuthentication(endpoint)
		}
	}
}

func (b *BoxInstance) watchOpenVPNAuthentication(endpoint adapter.OpenVPNEndpoint) {
	for {
		status := endpoint.OpenVPNStatus()
		if detail, required := openVPNAuthenticationDetail(status); required {
			if intfNB4A != nil {
				intfNB4A.EndpointAuthenticationRequired("OpenVPN", detail)
			}
			return
		}
		select {
		case <-b.ctx.Done():
			return
		case <-endpoint.StatusUpdated():
		}
	}
}

func (b *BoxInstance) watchOpenConnectAuthentication(endpoint adapter.OpenConnectEndpoint) {
	for {
		status := endpoint.OpenConnectStatus()
		if detail, required := openConnectAuthenticationDetail(status); required {
			if intfNB4A != nil {
				intfNB4A.EndpointAuthenticationRequired("OpenConnect", detail)
			}
			return
		}
		select {
		case <-b.ctx.Done():
			return
		case <-endpoint.StatusUpdated():
		}
	}
}

func openVPNAuthenticationDetail(status adapter.OpenVPNStatus) (string, bool) {
	if status.State != adapter.OpenVPNStateAuthPending {
		return "", false
	}
	if status.Challenge == nil {
		return "", true
	}
	if status.Challenge.Message != "" {
		return status.Challenge.Message, true
	}
	return status.Challenge.URL, true
}

func openConnectAuthenticationDetail(status adapter.OpenConnectStatus) (string, bool) {
	if status.State != adapter.OpenConnectStateAuthPending {
		return "", false
	}
	challenge := status.AuthChallenge
	if challenge == nil {
		return "", true
	}
	if challenge.Message != "" {
		return challenge.Message, true
	}
	if challenge.Browser != nil {
		return challenge.Browser.URL, true
	}
	if challenge.Error != "" {
		return challenge.Error, true
	}
	return challenge.Banner, true
}

func (b *BoxInstance) Close() (err error) {
	return b.CloseTimeout(int64(constant.FatalStopTimeout / time.Millisecond))
}

func (b *BoxInstance) CloseTimeout(timeoutMillis int64) (err error) {
	b.access.Lock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout <= 0 {
		timeout = constant.FatalStopTimeout
	}

	if b.state == boxStateClosed {
		err = b.closeErr
		b.access.Unlock()
		return err
	}
	if b.state == boxStateClosing {
		done := b.closeDone
		b.access.Unlock()
		select {
		case <-done:
			b.access.Lock()
			err = b.closeErr
			b.access.Unlock()
			return err
		case <-time.After(timeout):
			return errors.New("sing-box did not close in time")
		}
	}

	b.state = boxStateClosing
	done := make(chan struct{})
	b.closeDone = done
	boxInstance := b.Box
	cancel := b.cancel
	b.access.Unlock()

	go func() {
		var closeErr error
		defer func() {
			defer close(done)
			if r := recover(); r != nil {
				closeErr = fmt.Errorf("box.Close goroutine panic: %s\n%s", r, string(debug.Stack()))
			}
			boxInstance = nil
			cancel = nil
			if mainInstance == b {
				mainInstance = nil
				goServeProtect(false)
			}
			b.access.Lock()
			b.closeErr = closeErr
			b.state = boxStateClosed
			b.detachLocked()
			b.access.Unlock()
			if !b.forTest {
				debug.FreeOSMemory()
			}
		}()
		if cancel != nil {
			cancel()
		}
		if boxInstance != nil {
			closeErr = boxInstance.Close()
		}
	}()

	select {
	case <-done:
		b.access.Lock()
		err = b.closeErr
		b.access.Unlock()
		return err
	case <-time.After(timeout):
		return errors.New("sing-box did not close in time")
	}
}

func (b *BoxInstance) detachLocked() {
	b.Box = nil
	b.ctx = nil
	b.cancel = nil
	b.v2api = nil
	b.selector = nil
	b.pauseManager = nil
	b.logWriter = nil
}

func (b *BoxInstance) ResetNetwork() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.ResetNetwork", func(err_ error) { err = err_ })

	return b.resetConnectionsLocked()
}

func (b *BoxInstance) resetConnectionsLocked() error {
	if b.state != 1 || b.Box == nil {
		return nil
	}
	b.urlTestReady.invalidate()
	clashServer := clashServerFromInstance(b)
	if trafficManagerProvider, ok := clashServer.(clashTrafficManagerProvider); ok {
		if trafficManager := trafficManagerProvider.TrafficManager(); trafficManager != nil {
			trafficManager.CloseAllConnections()
		}
	}
	b.Box.Router().ResetNetwork()
	log.Println("Reset network done")
	return nil
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstance = b
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api.StatsService())
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	return b.v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	return b.SelectOutboundInGroup("proxy", tag)
}

func (b *BoxInstance) SelectOutboundInGroup(groupTag string, outboundTag string) bool {
	outbound, loaded := b.Outbound().Outbound(groupTag)
	if !loaded {
		return false
	}
	selector, isSelector := outbound.(*group.Selector)
	if !isSelector {
		return false
	}
	return selector.SelectOutbound(outboundTag)
}
