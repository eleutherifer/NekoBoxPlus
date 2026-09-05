package xhttp

import (
	"context"
	gotls "crypto/tls"
	"errors"
	"fmt"
	"io"
	"maps"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing-box/adapter"
	commonCongestion "github.com/sagernet/sing-box/common/congestion"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/common/xray/buf"
	xrcrypto "github.com/sagernet/sing-box/common/xray/crypto"
	xrnet "github.com/sagernet/sing-box/common/xray/net"
	"github.com/sagernet/sing-box/common/xray/pipe"
	"github.com/sagernet/sing-box/common/xray/signal/done"
	"github.com/sagernet/sing-box/common/xray/uuid"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	qtls "github.com/sagernet/sing-quic"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/ntp"
	sHTTP "github.com/sagernet/sing/protocol/http"
	"github.com/sagernet/sing/service"
	"golang.org/x/net/http2"
)

type Client struct {
	ctx             context.Context
	options         *option.V2RayXHTTPOptions
	dest            M.Socksaddr
	downloadDest    *M.Socksaddr
	logger          logger.ContextLogger
	baseRequestURL  url.URL
	baseRequestURL2 url.URL
	getHTTPClient   func() (DialerClient, *XmuxClient, error)
	getHTTPClient2  func() (DialerClient, *XmuxClient, error)
	xmuxManager     *XmuxManager
	xmuxManager2    *XmuxManager
	profiles        []*xhttpClientProfile
	probeMu         sync.Mutex
	probeCond       *sync.Cond
	probeRunning    bool
	probeDone       bool
	selectedProfile *xhttpClientProfile
	closed          atomic.Bool
	closeOnce       sync.Once
	closeErr        error
}

type xhttpClientProfile struct {
	name            string
	options         *option.V2RayXHTTPOptions
	baseRequestURL  url.URL
	baseRequestURL2 url.URL
	getHTTPClient   func() (DialerClient, *XmuxClient, error)
	getHTTPClient2  func() (DialerClient, *XmuxClient, error)
	xmuxManager     *XmuxManager
	xmuxManager2    *XmuxManager
}

type xhttpSessionProfileKind string

type xhttpTLSAdjustment uint8

const (
	xhttpSessionProfilePrimary                     xhttpSessionProfileKind = "primary"
	xhttpSessionProfileLegacy                      xhttpSessionProfileKind = "legacy"
	xhttpSessionProfileLegacyKeySessionIDPlacement xhttpSessionProfileKind = "legacy-key-session-id-placement"
	xhttpSessionProfileSessionIDKeyLegacyPlacement xhttpSessionProfileKind = "session-id-key-legacy-placement"
)

const (
	xhttpTLSUnchanged xhttpTLSAdjustment = iota
	xhttpTLSFallbackToTCP
)

type xhttpSessionPair struct {
	key       string
	placement string
}

const xhttpFallbackProbeTimeout = 5 * time.Second

func (c *Client) nextPacketUpHTTPClient(
	currentClient DialerClient,
	currentXmuxClient *XmuxClient,
	lastWrite time.Time,
) (DialerClient, *XmuxClient, error) {
	profile := c.primaryProfile()
	return c.nextPacketUpHTTPClientForProfile(profile, currentClient, currentXmuxClient, lastWrite)
}

func (c *Client) nextPacketUpHTTPClientForProfile(
	profile *xhttpClientProfile,
	currentClient DialerClient,
	currentXmuxClient *XmuxClient,
	lastWrite time.Time,
) (DialerClient, *XmuxClient, error) {
	if currentXmuxClient != nil && (currentXmuxClient.LeftRequests.Add(-1) <= 0 ||
		(currentXmuxClient.UnreusableAt != time.Time{} && lastWrite.After(currentXmuxClient.UnreusableAt))) {
		return profile.getHTTPClient()
	}
	return currentClient, currentXmuxClient, nil
}

func dialerClientFromXmux(xmuxClient *XmuxClient) (DialerClient, error) {
	httpClient, ok := xmuxClient.XmuxConn.(DialerClient)
	if !ok {
		return nil, E.New("xhttp xmux connection does not implement DialerClient")
	}
	return httpClient, nil
}

func (c *Client) primaryProfile() *xhttpClientProfile {
	if len(c.profiles) > 0 {
		return c.profiles[0]
	}
	return &xhttpClientProfile{
		name:            "primary",
		options:         c.options,
		baseRequestURL:  c.baseRequestURL,
		baseRequestURL2: c.baseRequestURL2,
		getHTTPClient:   c.getHTTPClient,
		getHTTPClient2:  c.getHTTPClient2,
		xmuxManager:     c.xmuxManager,
		xmuxManager2:    c.xmuxManager2,
	}
}

func (c *Client) hasFallbackProfiles() bool {
	return len(c.profiles) > 1
}

func cloneXHTTPOptions(options option.V2RayXHTTPOptions) option.V2RayXHTTPOptions {
	if options.Headers != nil {
		options.Headers = maps.Clone(options.Headers)
	}
	if options.Download != nil {
		download := *options.Download
		if download.Headers != nil {
			download.Headers = maps.Clone(download.Headers)
		}
		options.Download = &download
	}
	return options
}

func xhttpProfileOptions(base option.V2RayXHTTPOptions, kind xhttpSessionProfileKind) option.V2RayXHTTPOptions {
	profile := cloneXHTTPOptions(base)
	applyXHTTPSessionProfile(&profile.V2RayXHTTPBaseOptions, kind)
	if profile.Download != nil {
		applyXHTTPSessionProfile(&profile.Download.V2RayXHTTPBaseOptions, kind)
	}
	return profile
}

func applyXHTTPSessionProfile(options *option.V2RayXHTTPBaseOptions, kind xhttpSessionProfileKind) {
	if kind == xhttpSessionProfilePrimary {
		if pair, ok := xhttpSessionIDPair(options); ok {
			setXHTTPSessionIDPair(options, pair)
			options.SessionKey = ""
			options.SessionPlacement = ""
		}
		return
	}
	pair, ok := xhttpSessionPairForProfile(options, kind)
	if !ok {
		return
	}
	setXHTTPSessionIDPair(options, pair)
	options.SessionKey = ""
	options.SessionPlacement = ""
}

func setXHTTPSessionIDPair(options *option.V2RayXHTTPBaseOptions, pair xhttpSessionPair) {
	options.SessionIDKey = pair.key
	options.SessionIDPlacement = pair.placement
}

func xhttpSessionPairForProfile(options *option.V2RayXHTTPBaseOptions, kind xhttpSessionProfileKind) (xhttpSessionPair, bool) {
	sessionIDPair, sessionIDOk := xhttpSessionIDPair(options)
	legacyPair, legacyOk := xhttpLegacySessionPair(options)
	switch kind {
	case xhttpSessionProfilePrimary:
		if sessionIDOk {
			return sessionIDPair, true
		}
		return legacyPair, legacyOk
	case xhttpSessionProfileLegacy:
		return legacyPair, legacyOk
	case xhttpSessionProfileLegacyKeySessionIDPlacement:
		if !sessionIDOk || !legacyOk {
			return xhttpSessionPair{}, false
		}
		return validateXHTTPSessionPair(xhttpSessionPair{key: legacyPair.key, placement: sessionIDPair.placement})
	case xhttpSessionProfileSessionIDKeyLegacyPlacement:
		if !sessionIDOk || !legacyOk {
			return xhttpSessionPair{}, false
		}
		return validateXHTTPSessionPair(xhttpSessionPair{key: sessionIDPair.key, placement: legacyPair.placement})
	default:
		return xhttpSessionPair{}, false
	}
}

func xhttpSessionIDPair(options *option.V2RayXHTTPBaseOptions) (xhttpSessionPair, bool) {
	if options.SessionIDKey == "" && options.SessionIDPlacement == "" {
		return xhttpSessionPair{}, false
	}
	placement := options.SessionIDPlacement
	if placement == "" {
		placement = option.PlacementPath
	}
	key := options.SessionIDKey
	if key == "" {
		key = defaultXHTTPSessionKey(placement)
	}
	return validateXHTTPSessionPair(xhttpSessionPair{key: key, placement: placement})
}

func xhttpLegacySessionPair(options *option.V2RayXHTTPBaseOptions) (xhttpSessionPair, bool) {
	if options.SessionKey == "" && options.SessionPlacement == "" {
		return xhttpSessionPair{}, false
	}
	placement := options.SessionPlacement
	if placement == "" {
		if options.SessionIDPlacement != "" {
			return xhttpSessionPair{}, false
		}
		placement = option.PlacementPath
	}
	key := options.SessionKey
	if key == "" {
		key = defaultXHTTPSessionKey(placement)
	}
	return validateXHTTPSessionPair(xhttpSessionPair{key: key, placement: placement})
}

func defaultXHTTPSessionKey(placement string) string {
	switch placement {
	case option.PlacementHeader:
		return "X-Session"
	case option.PlacementCookie, option.PlacementQuery:
		return "x_session"
	default:
		return ""
	}
}

func validateXHTTPSessionPair(pair xhttpSessionPair) (xhttpSessionPair, bool) {
	if pair.placement == "" {
		return xhttpSessionPair{}, false
	}
	if pair.placement != option.PlacementPath && pair.key == "" {
		return xhttpSessionPair{}, false
	}
	return pair, true
}

func xhttpFallbackProfileKinds(options *option.V2RayXHTTPOptions) []xhttpSessionProfileKind {
	blocks := []*option.V2RayXHTTPBaseOptions{&options.V2RayXHTTPBaseOptions}
	if options.Download != nil {
		blocks = append(blocks, &options.Download.V2RayXHTTPBaseOptions)
	}
	for _, block := range blocks {
		if _, ok := xhttpSessionIDPair(block); !ok {
			return nil
		}
		if _, ok := xhttpLegacySessionPair(block); !ok {
			return nil
		}
	}
	kinds := []xhttpSessionProfileKind{
		xhttpSessionProfileLegacy,
		xhttpSessionProfileLegacyKeySessionIDPlacement,
		xhttpSessionProfileSessionIDKeyLegacyPlacement,
	}
	seen := map[string]bool{xhttpSessionProfileSignature(blocks, xhttpSessionProfilePrimary): true}
	var fallbackKinds []xhttpSessionProfileKind
	for _, kind := range kinds {
		signature := xhttpSessionProfileSignature(blocks, kind)
		if signature == "" || seen[signature] {
			continue
		}
		seen[signature] = true
		fallbackKinds = append(fallbackKinds, kind)
	}
	return fallbackKinds
}

func xhttpSessionProfileSignature(blocks []*option.V2RayXHTTPBaseOptions, kind xhttpSessionProfileKind) string {
	var signature strings.Builder
	for _, block := range blocks {
		pair, ok := xhttpSessionPairForProfile(block, kind)
		if !ok {
			return ""
		}
		signature.WriteString(pair.key)
		signature.WriteByte('@')
		signature.WriteString(pair.placement)
		signature.WriteByte(';')
	}
	return signature.String()
}

func NewClient(ctx context.Context, logger logger.ContextLogger, dialer N.Dialer, serverAddr M.Socksaddr, options option.V2RayXHTTPOptions, tlsConfig tls.Config) (adapter.V2RayClientTransport, error) {
	if _, err := commonCongestion.New(options.CongestionController, options.CWND, nil); err != nil {
		return nil, err
	}
	if options.Download != nil {
		if _, err := commonCongestion.New(options.Download.CongestionController, options.Download.CWND, nil); err != nil {
			return nil, E.Cause(err, "invalid XHTTP download congestion control")
		}
	}
	configMode, err := option.NormalizeXHTTPMode(options.Mode)
	if err != nil {
		return nil, err
	}
	if options.Download != nil {
		options.Download.Mode, err = option.NormalizeXHTTPMode(options.Download.Mode)
		if err != nil {
			return nil, err
		}
		if configMode == "stream-one" {
			return nil, E.New(`download is not allowed when mode is "stream-one"`)
		}
	}
	tlsConfig, tlsAdjustment, err := prepareXHTTPTLSConfig(tlsConfig)
	if err != nil {
		return nil, err
	}
	logXHTTPTLSAdjustment(logger, "XHTTP", tlsAdjustment)
	mode := configMode
	dest := serverAddr
	isReality := isRealityConfig(tlsConfig)
	if mode == "auto" {
		mode = "packet-up"
		if isReality {
			mode = "stream-one"
			if options.Download != nil {
				mode = "stream-up"
			}
		}
	}
	options.Mode = mode
	rawOptions := options
	if rawOptions.SessionIDPlacement == rawOptions.SessionPlacement && rawOptions.SessionIDKey == "" {
		rawOptions.SessionIDPlacement = ""
	}
	options = xhttpProfileOptions(rawOptions, xhttpSessionProfilePrimary)
	baseRequestURL, err := getBaseRequestURL(
		&options.V2RayXHTTPBaseOptions, dest, tlsConfig,
	)
	if err != nil {
		return nil, err
	}
	var xmuxOptions option.V2RayXHTTPXmuxOptions
	if options.Xmux != nil {
		xmuxOptions = *options.Xmux
		if err := xmuxOptions.Normalize(); err != nil {
			return nil, err
		}
	}
	xmuxManager := NewXmuxManager(xmuxOptions, func() XmuxConn {
		return createHTTPClient(ctx, dest, dialer, &options.V2RayXHTTPBaseOptions, tlsConfig)
	})
	getHTTPClient := func() (DialerClient, *XmuxClient, error) {
		xmuxClient := xmuxManager.GetXmuxClient(ctx)
		httpClient, err := dialerClientFromXmux(xmuxClient)
		return httpClient, xmuxClient, err
	}
	baseRequestURL2 := baseRequestURL
	getHTTPClient2 := getHTTPClient
	var xmuxManager2 *XmuxManager
	var downloadDest *M.Socksaddr
	downloadDialer := dialer
	var downloadTLSConfig tls.Config
	if options.Download != nil {
		options2 := options.Download
		dialer2 := dialer
		if options2.Detour != "" {
			var ok bool
			dialer2, ok = service.FromContext[adapter.OutboundManager](ctx).Outbound(options2.Detour)
			if !ok {
				return nil, E.New("outbound detour not found: ", options2.Detour)
			}
		}
		downloadDialer = dialer2
		dest2 := options2.ServerOptions.Build()
		downloadDest = &dest2
		var tlsConfig2 tls.Config
		if options2.TLS != nil {
			tlsConfig2, err = tls.NewClient(ctx, logger, options2.Server, common.PtrValueOrDefault(options2.TLS))
			if err != nil {
				return nil, err
			}
			tlsConfig2, tlsAdjustment, err = prepareXHTTPTLSConfig(tlsConfig2)
			if err != nil {
				return nil, E.Cause(err, "prepare XHTTP download TLS")
			}
			logXHTTPTLSAdjustment(logger, "XHTTP download", tlsAdjustment)
		}
		downloadTLSConfig = tlsConfig2
		baseRequestURL2, err = getBaseRequestURL(&options2.V2RayXHTTPBaseOptions, dest2, tlsConfig2)
		if err != nil {
			return nil, err
		}
		var xmuxOptions2 option.V2RayXHTTPXmuxOptions
		if options2.Xmux != nil {
			xmuxOptions2 = *options2.Xmux
			if err := xmuxOptions2.Normalize(); err != nil {
				return nil, err
			}
		}
		xmuxManager2 = NewXmuxManager(xmuxOptions2, func() XmuxConn {
			return createHTTPClient(ctx, dest2, dialer2, &options2.V2RayXHTTPBaseOptions, tlsConfig2)
		})
		getHTTPClient2 = func() (DialerClient, *XmuxClient, error) {
			xmuxClient2 := xmuxManager2.GetXmuxClient(ctx)
			httpClient2, err := dialerClientFromXmux(xmuxClient2)
			return httpClient2, xmuxClient2, err
		}
	}
	newProfile := func(kind xhttpSessionProfileKind) (*xhttpClientProfile, error) {
		profileOptions := xhttpProfileOptions(rawOptions, kind)
		profileBaseRequestURL, err := getBaseRequestURL(&profileOptions.V2RayXHTTPBaseOptions, dest, tlsConfig)
		if err != nil {
			return nil, err
		}
		profileXmuxOptions := xmuxOptions
		profileXmuxManager := NewXmuxManager(profileXmuxOptions, func() XmuxConn {
			return createHTTPClient(ctx, dest, dialer, &profileOptions.V2RayXHTTPBaseOptions, tlsConfig)
		})
		profileGetHTTPClient := func() (DialerClient, *XmuxClient, error) {
			xmuxClient := profileXmuxManager.GetXmuxClient(ctx)
			httpClient, err := dialerClientFromXmux(xmuxClient)
			return httpClient, xmuxClient, err
		}
		profileBaseRequestURL2 := profileBaseRequestURL
		profileGetHTTPClient2 := profileGetHTTPClient
		var profileXmuxManager2 *XmuxManager
		if profileOptions.Download != nil {
			profileBaseRequestURL2, err = getBaseRequestURL(&profileOptions.Download.V2RayXHTTPBaseOptions, *downloadDest, downloadTLSConfig)
			if err != nil {
				return nil, err
			}
			var profileXmuxOptions2 option.V2RayXHTTPXmuxOptions
			if profileOptions.Download.Xmux != nil {
				profileXmuxOptions2 = *profileOptions.Download.Xmux
				if err := profileXmuxOptions2.Normalize(); err != nil {
					return nil, err
				}
			}
			profileOptions2 := profileOptions.Download
			profileXmuxManager2 = NewXmuxManager(profileXmuxOptions2, func() XmuxConn {
				return createHTTPClient(ctx, *downloadDest, downloadDialer, &profileOptions2.V2RayXHTTPBaseOptions, downloadTLSConfig)
			})
			profileGetHTTPClient2 = func() (DialerClient, *XmuxClient, error) {
				xmuxClient2 := profileXmuxManager2.GetXmuxClient(ctx)
				httpClient2, err := dialerClientFromXmux(xmuxClient2)
				return httpClient2, xmuxClient2, err
			}
		}
		return &xhttpClientProfile{
			name:            string(kind),
			options:         &profileOptions,
			baseRequestURL:  profileBaseRequestURL,
			baseRequestURL2: profileBaseRequestURL2,
			getHTTPClient:   profileGetHTTPClient,
			getHTTPClient2:  profileGetHTTPClient2,
			xmuxManager:     profileXmuxManager,
			xmuxManager2:    profileXmuxManager2,
		}, nil
	}
	profiles := []*xhttpClientProfile{{
		name:            string(xhttpSessionProfilePrimary),
		options:         &options,
		baseRequestURL:  baseRequestURL,
		baseRequestURL2: baseRequestURL2,
		getHTTPClient:   getHTTPClient,
		getHTTPClient2:  getHTTPClient2,
		xmuxManager:     xmuxManager,
		xmuxManager2:    xmuxManager2,
	}}
	if mode != "stream-one" {
		for _, kind := range xhttpFallbackProfileKinds(&rawOptions) {
			profile, err := newProfile(kind)
			if err != nil {
				return nil, err
			}
			profiles = append(profiles, profile)
		}
	}
	client := &Client{
		ctx:             ctx,
		options:         &options,
		dest:            dest,
		downloadDest:    downloadDest,
		logger:          logger,
		getHTTPClient:   getHTTPClient,
		getHTTPClient2:  getHTTPClient2,
		xmuxManager:     xmuxManager,
		xmuxManager2:    xmuxManager2,
		baseRequestURL:  baseRequestURL,
		baseRequestURL2: baseRequestURL2,
		profiles:        profiles,
	}
	client.probeCond = sync.NewCond(&client.probeMu)
	if client.hasFallbackProfiles() {
		client.debugFallback(ctx, "XHTTP session fallback profiles enabled: ", xhttpProfileNames(profiles[1:]))
	}
	return client, nil
}

func (c *Client) DialContext(ctx context.Context) (net.Conn, error) {
	if c.closed.Load() {
		return nil, E.New("xhttp client closed")
	}
	profile, waitAccepted := c.profileForDial()
	conn, err := c.dialContextWithProfile(ctx, profile, waitAccepted)
	if err == nil {
		if waitAccepted {
			c.storeSelectedProfile(profile)
		}
		return conn, nil
	}
	if !waitAccepted {
		return nil, err
	}
	c.debugFallback(ctx, "XHTTP session fallback initial profile ", profile.name, " failed: ", err)
	fallbackProfile, probeErr := c.resolveFallbackProfile(err)
	if probeErr != nil {
		return nil, probeErr
	}
	return c.dialContextWithProfile(ctx, fallbackProfile, false)
}

func (c *Client) profileForDial() (*xhttpClientProfile, bool) {
	if !c.hasFallbackProfiles() {
		return c.primaryProfile(), false
	}
	c.probeMu.Lock()
	c.ensureProbeCondLocked()
	for c.probeRunning && c.selectedProfile == nil && !c.probeDone {
		c.debugFallback(c.fallbackLogContext(), "XHTTP session fallback waiting for in-progress probe")
		c.probeCond.Wait()
	}
	if c.selectedProfile != nil {
		profile := c.selectedProfile
		c.probeMu.Unlock()
		c.debugFallback(c.fallbackLogContext(), "XHTTP session fallback using selected profile: ", profile.name)
		return profile, false
	}
	if c.probeDone {
		c.probeMu.Unlock()
		return c.primaryProfile(), false
	}
	c.probeMu.Unlock()
	return c.primaryProfile(), true
}

func (c *Client) dialContextWithProfile(ctx context.Context, profile *xhttpClientProfile, waitAccepted bool) (net.Conn, error) {
	options := profile.options
	mode := options.Mode
	sessionId := ""
	if options.Mode != "stream-one" {
		sessionId = generateSessionID(&options.V2RayXHTTPBaseOptions)
	}
	requestURL := profile.baseRequestURL
	requestURL2 := profile.baseRequestURL2
	httpClient, xmuxClient, err := profile.getHTTPClient()
	if err != nil {
		return nil, err
	}
	httpClient2 := httpClient
	xmuxClient2 := xmuxClient
	if c.downloadDest != nil {
		httpClient2, xmuxClient2, err = profile.getHTTPClient2()
		if err != nil {
			return nil, err
		}
	}
	httpVersion := httpVersionFromClient(httpClient)
	destLabel := formatDestWithNetwork(httpClient, c.dest)
	if c.logger != nil {
		c.logger.DebugContext(ctx, fmt.Sprintf("XHTTP is dialing to %s, mode %s, HTTP version %s, host %s", destLabel, mode, httpVersion, requestURL.Host))
	}
	if c.downloadDest != nil {
		httpVersion2 := httpVersionFromClient(httpClient2)
		destLabel2 := formatDestWithNetwork(httpClient2, *c.downloadDest)
		if c.logger != nil {
			c.logger.DebugContext(ctx, fmt.Sprintf("XHTTP is downloading from %s, mode %s, HTTP version %s, host %s", destLabel2, "stream-down", httpVersion2, requestURL2.Host))
		}
	}
	holdXmuxClient := true
	holdXmuxClient2 := mode != "stream-one" || c.downloadDest != nil
	if holdXmuxClient && xmuxClient != nil {
		xmuxClient.AddOpenUsage(1)
	}
	if holdXmuxClient2 && xmuxClient2 != nil && (!holdXmuxClient || xmuxClient2 != xmuxClient) {
		xmuxClient2.AddOpenUsage(1)
	}
	releaseXmuxUsage := sync.OnceFunc(func() {
		if holdXmuxClient && xmuxClient != nil {
			xmuxClient.ReleaseUsage()
		}
		if holdXmuxClient2 && xmuxClient2 != nil && (!holdXmuxClient || xmuxClient2 != xmuxClient) {
			xmuxClient2.ReleaseUsage()
		}
	})
	var closed atomic.Int32
	cancelUpload := func() {}
	conn := splitConn{
		onClose: func() {
			if closed.Add(1) > 1 {
				return
			}
			cancelUpload()
			releaseXmuxUsage()
		},
	}
	var streamReader *io.PipeReader
	if mode == "stream-one" || mode == "stream-up" {
		reader, writer := io.Pipe()
		streamReader = reader
		conn.writer = newIdleTimeoutWriteCloser(writer, streamUploadIdleTimeout(&options.V2RayXHTTPBaseOptions))
	}
	if mode == "stream-one" {
		requestURL.Path = options.GetNormalizedPath()
		if xmuxClient != nil {
			xmuxClient.LeftRequests.Add(-1)
		}
		conn.reader, conn.remoteAddr, conn.localAddr, err = httpClient.OpenStream(ctx, requestURL.String(), sessionId, streamReader, false)
		if err != nil {
			closeErr := conn.Close()
			return nil, errors.Join(err, closeErr)
		}
		if waitAccepted {
			if err = waitStreamAccepted(ctx, conn.reader); err != nil {
				closeErr := conn.Close()
				return nil, errors.Join(err, closeErr)
			}
		}
		return &conn, nil
	} else {
		if xmuxClient2 != nil {
			xmuxClient2.LeftRequests.Add(-1)
		}
		conn.reader, conn.remoteAddr, conn.localAddr, err = httpClient2.OpenStream(ctx, requestURL2.String(), sessionId, nil, false)
		if err != nil {
			closeErr := conn.Close()
			return nil, errors.Join(err, closeErr)
		}
		if waitAccepted {
			if err = waitStreamAccepted(ctx, conn.reader); err != nil {
				closeErr := conn.Close()
				return nil, errors.Join(err, closeErr)
			}
		}
	}
	if mode == "stream-up" {
		if xmuxClient != nil {
			xmuxClient.LeftRequests.Add(-1)
		}
		_, _, _, err = httpClient.OpenStream(ctx, requestURL.String(), sessionId, streamReader, true)
		if err != nil {
			closeErr := conn.Close()
			return nil, errors.Join(err, closeErr)
		}
		return &conn, nil
	}
	scMaxEachPostBytes := options.GetNormalizedScMaxEachPostBytes()
	scMinPostsIntervalMs := options.GetNormalizedScMinPostsIntervalMs()
	maxUploadSize := max(int32(0), scMaxEachPostBytes.Rand())
	uploadPipeReader, uploadPipeWriter := pipe.New(pipe.WithSizeLimit(max(0, maxUploadSize-buf.Size)))
	conn.writer = uploadWriter{
		uploadPipeWriter,
		maxUploadSize,
	}
	uploadBaseCtx := context.WithoutCancel(ctx)
	uploadCtx, cancelPacketUpload := context.WithCancel(uploadBaseCtx)
	cancelUpload = cancelPacketUpload
	go func() {
		defer uploadPipeReader.Interrupt()
		var seq int64
		var lastWrite time.Time
		dynamicHTTPClient := httpClient
		dynamicXmuxClient := xmuxClient
		for {
			select {
			case <-uploadCtx.Done():
				return
			default:
			}
			remainder, err := uploadPipeReader.ReadMultiBuffer()
			if err != nil {
				return
			}
			if maxUploadSize == 0 {
				buf.ReleaseMulti(remainder)
				return
			}
			doSplit := atomic.Bool{}
			for doSplit.Store(true); doSplit.Load(); {
				var chunk buf.MultiBuffer
				remainder, chunk = buf.SplitSize(remainder, maxUploadSize)
				if chunk.IsEmpty() {
					break
				}
				wroteRequest := done.New()
				reqCtx := httptrace.WithClientTrace(uploadCtx, &httptrace.ClientTrace{
					WroteRequest: func(httptrace.WroteRequestInfo) {
						closeSilently(wroteRequest)
					},
				})
				seqStr := strconv.FormatInt(seq, 10)
				seq += 1
				if scMinPostsIntervalMs.From > 0 {
					sleepDuration := time.Duration(scMinPostsIntervalMs.Rand())*time.Millisecond - time.Since(lastWrite)
					if !sleepWithContext(uploadCtx, sleepDuration) {
						buf.ReleaseMulti(chunk)
						buf.ReleaseMulti(remainder)
						return
					}
				}
				select {
				case <-uploadCtx.Done():
					buf.ReleaseMulti(chunk)
					buf.ReleaseMulti(remainder)
					return
				default:
				}
				lastWrite = time.Now()
				var err error
				dynamicHTTPClient, dynamicXmuxClient, err = c.nextPacketUpHTTPClientForProfile(profile, dynamicHTTPClient, dynamicXmuxClient, lastWrite)
				if err != nil {
					buf.ReleaseMulti(chunk)
					buf.ReleaseMulti(remainder)
					uploadPipeReader.Interrupt()
					return
				}
				hClient := dynamicHTTPClient
				hXmuxClient := dynamicXmuxClient
				if hXmuxClient != nil {
					hXmuxClient.AddPacketUsage(1)
				}
				container := &buf.MultiBufferContainer{MultiBuffer: chunk}
				contentLength := int64(chunk.Len())
				go func(hClient DialerClient, hXmuxClient *XmuxClient, body *buf.MultiBufferContainer, baseCtx context.Context, seqStr string, contentLength int64) {
					defer func() {
						if hXmuxClient != nil {
							hXmuxClient.AddPacketUsage(-1)
						}
					}()
					postCtx, cancelPost := context.WithCancel(baseCtx)
					defer cancelPost()
					defer closeSilently(wroteRequest)
					err := hClient.PostPacket(
						postCtx,
						requestURL.String(),
						sessionId,
						seqStr,
						body,
						contentLength,
					)
					if err != nil {
						uploadPipeReader.Interrupt()
						doSplit.Store(false)
					}
				}(hClient, hXmuxClient, container, reqCtx, seqStr, contentLength)
				if _, ok := hClient.(*DefaultDialerClient); ok {
					select {
					case <-wroteRequest.Wait():
					case <-uploadCtx.Done():
						buf.ReleaseMulti(remainder)
						return
					}
				}
			}
			if !doSplit.Load() {
				buf.ReleaseMulti(remainder)
				return
			}
		}
	}()
	return &conn, nil
}

func waitStreamAccepted(ctx context.Context, reader io.ReadCloser) error {
	accepted, ok := reader.(acceptedReadCloser)
	if !ok {
		return nil
	}
	return accepted.WaitAccepted(ctx)
}

func (c *Client) ensureProbeCondLocked() {
	if c.probeCond == nil {
		c.probeCond = sync.NewCond(&c.probeMu)
	}
}

func (c *Client) storeSelectedProfile(profile *xhttpClientProfile) {
	var stored bool
	c.probeMu.Lock()
	c.ensureProbeCondLocked()
	if c.selectedProfile == nil {
		c.selectedProfile = profile
		c.probeDone = true
		stored = true
	}
	c.probeMu.Unlock()
	if stored {
		c.debugFallback(c.fallbackLogContext(), "XHTTP session fallback selected profile: ", profile.name)
	}
}

func (c *Client) resolveFallbackProfile(firstErr error) (*xhttpClientProfile, error) {
	c.probeMu.Lock()
	c.ensureProbeCondLocked()
	if c.selectedProfile != nil {
		profile := c.selectedProfile
		c.probeMu.Unlock()
		c.debugFallback(c.fallbackLogContext(), "XHTTP session fallback using selected profile: ", profile.name)
		return profile, nil
	}
	if c.probeDone {
		c.probeMu.Unlock()
		c.debugFallback(c.fallbackLogContext(), "XHTTP session fallback already completed without a selected profile")
		return nil, firstErr
	}
	if c.probeRunning {
		c.debugFallback(c.fallbackLogContext(), "XHTTP session fallback waiting for in-progress probe")
		for c.probeRunning && c.selectedProfile == nil && !c.probeDone {
			c.probeCond.Wait()
		}
		profile := c.selectedProfile
		c.probeMu.Unlock()
		if profile != nil {
			c.debugFallback(c.fallbackLogContext(), "XHTTP session fallback using selected profile: ", profile.name)
			return profile, nil
		}
		c.debugFallback(c.fallbackLogContext(), "XHTTP session fallback probe completed without a selected profile")
		return nil, firstErr
	}
	c.probeRunning = true
	c.probeMu.Unlock()
	c.debugFallback(c.fallbackLogContext(), "XHTTP session fallback probing profiles after error: ", firstErr)

	var selected *xhttpClientProfile
	probeCtx := c.ctx
	if probeCtx == nil {
		probeCtx = context.Background()
	}
	for _, profile := range c.profiles[1:] {
		c.debugFallback(probeCtx, "XHTTP session fallback probing profile: ", profile.name)
		if err := c.probeFallbackProfile(probeCtx, profile); err != nil {
			c.debugFallback(probeCtx, "XHTTP session fallback profile ", profile.name, " failed: ", err)
			continue
		}
		c.debugFallback(probeCtx, "XHTTP session fallback profile accepted: ", profile.name)
		selected = profile
		break
	}

	c.probeMu.Lock()
	c.probeRunning = false
	c.probeDone = true
	c.selectedProfile = selected
	c.probeCond.Broadcast()
	c.probeMu.Unlock()
	if selected != nil {
		c.debugFallback(c.fallbackLogContext(), "XHTTP session fallback selected profile: ", selected.name)
		return selected, nil
	}
	c.debugFallback(c.fallbackLogContext(), "XHTTP session fallback no profile accepted; returning original error: ", firstErr)
	return nil, firstErr
}

func (c *Client) probeFallbackProfile(ctx context.Context, profile *xhttpClientProfile) error {
	if profile.options.Mode != "packet-up" || c.downloadDest != nil {
		conn, err := c.dialContextWithProfile(ctx, profile, true)
		if err != nil {
			return err
		}
		closeErr := conn.Close()
		if closeErr != nil {
			c.debugFallback(ctx, "XHTTP session fallback profile ", profile.name, " close failed: ", closeErr)
			return closeErr
		}
		return nil
	}
	probeCtx, cancel := context.WithTimeout(ctx, xhttpFallbackProbeTimeout)
	defer cancel()
	httpClient, xmuxClient, err := profile.getHTTPClient()
	if err != nil {
		return err
	}
	if xmuxClient != nil {
		xmuxClient.AddPacketUsage(1)
		defer xmuxClient.AddPacketUsage(-1)
	}
	sessionId := generateSessionID(&profile.options.V2RayXHTTPBaseOptions)
	return httpClient.PostPacket(
		probeCtx,
		profile.baseRequestURL.String(),
		sessionId,
		"0",
		strings.NewReader(""),
		0,
	)
}

func (c *Client) fallbackLogContext() context.Context {
	if c.ctx != nil {
		return c.ctx
	}
	return context.Background()
}

func (c *Client) debugFallback(ctx context.Context, args ...any) {
	if c.logger == nil {
		return
	}
	if ctx == nil {
		ctx = context.Background()
	}
	c.logger.DebugContext(ctx, args...)
}

func xhttpProfileNames(profiles []*xhttpClientProfile) string {
	names := make([]string, 0, len(profiles))
	for _, profile := range profiles {
		names = append(names, profile.name)
	}
	return strings.Join(names, ", ")
}

func generateSessionID(options *option.V2RayXHTTPBaseOptions) string {
	table := options.SessionIDTable
	length := options.SessionIDLength.Rand()
	if table != "" && length > 0 {
		id := make([]byte, length)
		for i := range id {
			id[i] = table[int(xrcrypto.RandBetween(0, int64(len(table))))]
		}
		return string(id)
	}
	sessionID := uuid.New()
	return sessionID.String()
}

func streamUploadIdleTimeout(options *option.V2RayXHTTPBaseOptions) time.Duration {
	timeout := C.TCPTimeout
	if options != nil {
		scStreamUpServerSecs := options.GetNormalizedScStreamUpServerSecs()
		if scStreamUpServerSecs.To > 0 {
			timeout = max(timeout, time.Duration(scStreamUpServerSecs.To)*time.Second)
		}
	}
	return timeout
}

func (c *Client) Close() error {
	c.closeOnce.Do(func() {
		c.closed.Store(true)
		c.closeErr = c.resetXmuxManagers()
	})
	return c.closeErr
}

func (c *Client) Reset() error {
	return c.resetXmuxManagers()
}

func (c *Client) resetXmuxManagers() error {
	var err error
	if len(c.profiles) == 0 {
		if c.xmuxManager != nil {
			err = errors.Join(err, c.xmuxManager.Close())
		}
		if c.xmuxManager2 != nil && c.xmuxManager2 != c.xmuxManager {
			err = errors.Join(err, c.xmuxManager2.Close())
		}
		return err
	}
	closed := map[*XmuxManager]bool{}
	for _, profile := range c.profiles {
		for _, manager := range []*XmuxManager{profile.xmuxManager, profile.xmuxManager2} {
			if manager == nil || closed[manager] {
				continue
			}
			closed[manager] = true
			err = errors.Join(err, manager.Close())
		}
	}
	return err
}

func sleepWithContext(ctx context.Context, duration time.Duration) bool {
	if duration <= 0 {
		return true
	}
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-timer.C:
		return true
	case <-ctx.Done():
		return false
	}
}

func prepareXHTTPTLSConfig(tlsConfig tls.Config) (tls.Config, xhttpTLSAdjustment, error) {
	if tlsConfig == nil {
		return nil, xhttpTLSUnchanged, nil
	}
	preparedConfig := tlsConfig.Clone()
	nextProtos := preparedConfig.NextProtos()
	if len(nextProtos) == 0 {
		preparedConfig.SetNextProtos([]string{http2.NextProtoTLS, "http/1.1"})
		return preparedConfig, xhttpTLSUnchanged, nil
	}
	if isRealityConfig(preparedConfig) {
		nextProtos = xhttpTCPNextProtos(nextProtos)
		if len(nextProtos) == 0 {
			nextProtos = []string{http2.NextProtoTLS}
		}
		preparedConfig.SetNextProtos(nextProtos)
		return preparedConfig, xhttpTLSUnchanged, nil
	}
	if nextProtos[0] == http3.NextProtoH3 {
		_, stdConfigErr := preparedConfig.STDConfig()
		if stdConfigErr == nil {
			return preparedConfig, xhttpTLSUnchanged, nil
		}
		tcpNextProtos := xhttpTCPNextProtos(nextProtos)
		if len(tcpNextProtos) > 0 {
			preparedConfig.SetNextProtos(tcpNextProtos)
			return preparedConfig, xhttpTLSFallbackToTCP, nil
		}
		return nil, xhttpTLSUnchanged, E.Cause(stdConfigErr, "XHTTP HTTP/3 is incompatible with the configured TLS engine and no TCP ALPN fallback is configured")
	}
	if slices.Contains(nextProtos, http3.NextProtoH3) {
		preparedConfig.SetNextProtos(xhttpTCPNextProtos(nextProtos))
	}
	return preparedConfig, xhttpTLSUnchanged, nil
}

func logXHTTPTLSAdjustment(logger logger.ContextLogger, transportName string, adjustment xhttpTLSAdjustment) {
	switch adjustment {
	case xhttpTLSFallbackToTCP:
		logger.Warn("the configured TLS engine is not supported over ", transportName, " HTTP/3; falling back to a configured TCP ALPN")
	}
}

func xhttpTCPNextProtos(nextProtos []string) []string {
	return slices.DeleteFunc(slices.Clone(nextProtos), func(nextProto string) bool {
		return nextProto == http3.NextProtoH3
	})
}

func decideHTTPVersion(tlsConfig tls.Config) string {
	if isRealityConfig(tlsConfig) {
		return "2"
	}
	if tlsConfig == nil {
		return "1.1"
	}
	nextProtos := tlsConfig.NextProtos()

	if len(nextProtos) == 0 {
		tlsConfig.SetNextProtos([]string{http2.NextProtoTLS, "http/1.1"})
	}

	if len(nextProtos) > 0 && nextProtos[0] == http3.NextProtoH3 {
		return "3"
	}
	if len(nextProtos) > 0 && nextProtos[0] == "http/1.1" {
		return "1.1"
	}
	return "2"
}

func getBaseRequestURL(options *option.V2RayXHTTPBaseOptions, dest M.Socksaddr, tlsConfig tls.Config) (url.URL, error) {
	var requestURL url.URL
	if tlsConfig == nil {
		requestURL.Scheme = "http"
	} else {
		requestURL.Scheme = "https"
	}
	requestURL.Host = options.Host
	if requestURL.Host == "" && tlsConfig != nil {
		requestURL.Host = tlsConfig.ServerName()
	}
	if requestURL.Host == "" {
		requestURL.Host = dest.AddrString()
	}
	requestURL.Path = options.Path
	if err := sHTTP.URLSetPath(&requestURL, options.Path); err != nil {
		return requestURL, E.New(err, "parse path")
	}
	if !strings.HasPrefix(requestURL.Path, "/") {
		requestURL.Path = "/" + requestURL.Path
	}
	requestURL.Path = options.GetNormalizedPath()
	requestURL.RawQuery = options.GetNormalizedQuery()
	return requestURL, nil
}

func isRealityConfig(tlsConfig tls.Config) bool {
	if tlsConfig == nil {
		return false
	}
	return strings.Contains(fmt.Sprintf("%T", tlsConfig), ".RealityClientConfig")
}

func httpVersionFromClient(client DialerClient) string {
	if client == nil {
		return "unknown"
	}
	if defaultClient, ok := client.(*DefaultDialerClient); ok {
		return defaultClient.httpVersion
	}
	return "unknown"
}

func formatDestWithNetwork(client DialerClient, dest M.Socksaddr) string {
	network := "tcp"
	if defaultClient, ok := client.(*DefaultDialerClient); ok && defaultClient.httpVersion == "3" {
		network = "udp"
	}
	return network + ":" + dest.String()
}

func createHTTPClient(ctx context.Context, dest M.Socksaddr, dialer N.Dialer, options *option.V2RayXHTTPBaseOptions, tlsConfig tls.Config) DialerClient {
	httpVersion := decideHTTPVersion(tlsConfig)
	congestionControlFactory, _ := commonCongestion.New(options.CongestionController, options.CWND, ntp.TimeFuncFromContext(ctx))
	rawConns := newRawConnTracker()
	dialContext := func(ctxInner context.Context) (net.Conn, error) {
		conn, err := dialer.DialContext(ctxInner, N.NetworkTCP, dest)
		if err != nil {
			return nil, err
		}
		conn, err = rawConns.Track(conn)
		if err != nil {
			return nil, err
		}
		trackedConn := conn
		needTLS := tlsConfig != nil && httpVersion != "3"
		if needTLS {
			conn, err = tls.ClientHandshake(ctxInner, conn, tlsConfig)
			if err != nil {
				return nil, errors.Join(err, trackedConn.Close())
			}
		}
		return conn, nil
	}
	var transport http.RoundTripper
	var keepAlivePeriod time.Duration
	if options.Xmux != nil {
		keepAlivePeriod = time.Duration(options.Xmux.HKeepAlivePeriod) * time.Second
	}
	switch httpVersion {
	case "3":
		if keepAlivePeriod == 0 {
			keepAlivePeriod = xrnet.QuicgoH3KeepAlivePeriod
		} else if keepAlivePeriod < 0 {
			keepAlivePeriod = 0
		}
		quicConfig := &quic.Config{
			MaxIdleTimeout: xrnet.ConnIdleTimeout,
			// these two are defaults of quic-go/http3. the default of quic-go (no
			// http3) is different, so it is hardcoded here for clarity.
			// https://github.com/quic-go/quic-go/blob/b8ea5c798155950fb5bbfdd06cad1939c9355878/http3/client.go#L36-L39
			MaxIncomingStreams: -1,
			KeepAlivePeriod:    keepAlivePeriod,
		}
		transport = &http3.Transport{
			QUICConfig: quicConfig,
			Dial: func(ctx context.Context, addr string, tlsCfg *gotls.Config, cfg *quic.Config) (*quic.Conn, error) {
				udpConn, dErr := dialer.DialContext(ctx, N.NetworkUDP, dest)
				if dErr != nil {
					return nil, dErr
				}
				quicConn, dErr := qtls.DialEarly(ctx, udpConn, tlsConfig, cfg)
				if dErr != nil {
					_ = udpConn.Close()
					return nil, dErr
				}
				if congestionControlFactory != nil {
					quicConn.SetCongestionControl(congestionControlFactory(quicConn))
				}
				go func() {
					<-quicConn.Context().Done()
					_ = udpConn.Close()
				}()
				return quicConn, nil
			},
		}
	case "2":
		if keepAlivePeriod == 0 {
			keepAlivePeriod = xrnet.ChromeH2KeepAlivePeriod
		} else if keepAlivePeriod < 0 {
			keepAlivePeriod = 0
		}
		transport = &http2.Transport{
			DialTLSContext: func(ctxInner context.Context, network string, addr string, cfg *gotls.Config) (net.Conn, error) {
				return dialContext(ctxInner)
			},
			IdleConnTimeout: xrnet.ConnIdleTimeout,
			ReadIdleTimeout: keepAlivePeriod,
		}
	default:
		httpDialContext := func(ctxInner context.Context, network string, addr string) (net.Conn, error) {
			return dialContext(ctxInner)
		}
		transport = &http.Transport{
			DialTLSContext:  httpDialContext,
			DialContext:     httpDialContext,
			IdleConnTimeout: xrnet.ConnIdleTimeout,
			// chunked transfer download with KeepAlives is buggy with
			// http.Client and our custom dial context.
			DisableKeepAlives: true,
		}
	}
	client := &DefaultDialerClient{
		options: options,
		client: &http.Client{
			Transport: transport,
		},
		httpVersion:    httpVersion,
		rawConns:       rawConns,
		uploadRawPool:  newH1UploadPool(),
		dialUploadConn: dialContext,
	}
	return client
}
