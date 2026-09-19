package xhttp

import (
	"bytes"
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/common/xray/buf"
	xnet "github.com/sagernet/sing-box/common/xray/net"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	qtls "github.com/sagernet/sing-quic"

	"github.com/sagernet/sing-box/common/xray/signal/done"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	aTLS "github.com/sagernet/sing/common/tls"
	sHttp "github.com/sagernet/sing/protocol/http"
)

func decodePayloadFromHeaders(request *http.Request, uplinkDataKey string) ([]byte, error) {
	var chunks []string
	for i := 0; ; i++ {
		chunk := request.Header.Get(fmt.Sprintf("%s-%d", uplinkDataKey, i))
		if chunk == "" {
			break
		}
		chunks = append(chunks, chunk)
	}
	return base64.RawURLEncoding.DecodeString(strings.Join(chunks, ""))
}

func decodePayloadFromCookies(request *http.Request, uplinkDataKey string) ([]byte, error) {
	var chunks []string
	for i := 0; ; i++ {
		cookieName := fmt.Sprintf("%s_%d", uplinkDataKey, i)
		if c, err := request.Cookie(cookieName); err == nil {
			chunks = append(chunks, c.Value)
		} else {
			break
		}
	}
	return base64.RawURLEncoding.DecodeString(strings.Join(chunks, ""))
}

func readPacketBody(request *http.Request, scMaxEachPostBytes int) ([]byte, error) {
	if request.ContentLength > 0 {
		bodyPayload := make([]byte, request.ContentLength)
		_, err := io.ReadFull(request.Body, bodyPayload)
		return bodyPayload, err
	}
	return buf.ReadAllToBytes(io.LimitReader(request.Body, int64(scMaxEachPostBytes)+1))
}

func extractPacketPayload(request *http.Request, dataPlacement string, uplinkDataKey string, scMaxEachPostBytes int) ([]byte, []byte, error) {
	switch dataPlacement {
	case option.PlacementHeader:
		payload, err := decodePayloadFromHeaders(request, uplinkDataKey)
		return payload, nil, err
	case option.PlacementCookie:
		payload, err := decodePayloadFromCookies(request, uplinkDataKey)
		return payload, nil, err
	case option.PlacementAuto:
		headerPayload, err := decodePayloadFromHeaders(request, uplinkDataKey)
		if err != nil {
			return nil, nil, err
		}
		cookiePayload, err := decodePayloadFromCookies(request, uplinkDataKey)
		if err != nil {
			return nil, nil, err
		}
		bodyPayload, err := readPacketBody(request, scMaxEachPostBytes)
		if err != nil {
			return nil, nil, err
		}
		return slices.Concat(headerPayload, cookiePayload, bodyPayload), bodyPayload, nil
	default:
		bodyPayload, err := readPacketBody(request, scMaxEachPostBytes)
		return bodyPayload, bodyPayload, err
	}
}

var _ adapter.V2RayServerTransport = (*Server)(nil)

type Server struct {
	ctx         context.Context
	logger      logger.ContextLogger
	tlsConfig   tls.ServerConfig
	quicConfig  *quic.Config
	handler     adapter.V2RayServerTransportHandler
	httpServer  *http.Server
	http3Server *http3.Server
	localAddr   net.Addr
	options     *option.V2RayXHTTPOptions
	host        string
	path        string
	sessionMu   sync.Mutex
	sessions    sync.Map
	enableTCP   bool
	enableH3    bool
}

func NewServer(ctx context.Context, logger logger.ContextLogger, options option.V2RayXHTTPOptions, tlsConfig tls.ServerConfig, handler adapter.V2RayServerTransportHandler) (*Server, error) {
	mode, err := option.NormalizeXHTTPMode(options.Mode)
	if err != nil {
		return nil, err
	}
	options.Mode = mode
	server := &Server{
		ctx:       ctx,
		logger:    logger,
		tlsConfig: tlsConfig,
		handler:   handler,
		options:   &options,
		host:      options.Host,
		path:      options.GetNormalizedPath(),
	}
	hasNonH3 := true
	if tlsConfig != nil {
		hasNonH3 = false
		for _, proto := range tlsConfig.NextProtos() {
			if proto == "h3" {
				server.enableH3 = true
			} else if proto != "" {
				hasNonH3 = true
			}
		}
		if len(tlsConfig.NextProtos()) == 0 {
			hasNonH3 = true
		}
	} else {
		server.enableH3 = false
	}
	server.enableTCP = hasNonH3
	if server.enableTCP {
		protocols := new(http.Protocols)
		protocols.SetHTTP1(true)
		protocols.SetUnencryptedHTTP2(true)
		server.httpServer = &http.Server{
			Handler:           server,
			ReadHeaderTimeout: time.Second * 4,
			MaxHeaderBytes:    options.GetNormalizedServerMaxHeaderBytes(),
			Protocols:         protocols,
			BaseContext: func(net.Listener) context.Context {
				return ctx
			},
			ConnContext: func(ctx context.Context, c net.Conn) context.Context {
				return log.ContextWithNewID(ctx)
			},
		}
	}
	if server.enableH3 {
		server.quicConfig = &quic.Config{
			DisablePathMTUDiscovery: !C.IsLinux && !C.IsWindows,
		}
		server.http3Server = &http3.Server{
			Handler: server,
		}
	}
	return server, nil
}

func (s *Server) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	if len(s.host) > 0 && !isValidHTTPHost(request.Host, s.host) {
		s.logger.ErrorContext(request.Context(), "failed to validate host, request:", request.Host, ", config:", s.host)
		writer.WriteHeader(http.StatusNotFound)
		return
	}
	if !strings.HasPrefix(request.URL.Path, s.path) {
		s.logger.ErrorContext(request.Context(), "failed to validate path, request:", request.URL.Path, ", config:", s.path)
		writer.WriteHeader(http.StatusNotFound)
		return
	}
	WriteResponseHeader(writer, request.Method, request.Header, s.options)
	length := int(s.options.GetNormalizedXPaddingBytes().Rand())
	config := XPaddingConfig{Length: length}
	if s.options.XPaddingObfsMode {
		config.Placement = XPaddingPlacement{
			Placement: s.options.XPaddingPlacement,
			Key:       s.options.XPaddingKey,
			Header:    s.options.XPaddingHeader,
		}
		config.Method = PaddingMethod(s.options.XPaddingMethod)
	} else {
		config.Placement = XPaddingPlacement{
			Placement: option.PlacementHeader,
			Header:    "X-Padding",
		}
	}
	ApplyXPaddingToResponse(writer, config)
	if request.Method == "OPTIONS" {
		writer.WriteHeader(http.StatusOK)
		return
	}
	validRange := s.options.GetNormalizedXPaddingBytes()
	paddingValue, paddingPlacement := ExtractXPaddingFromRequest(&s.options.V2RayXHTTPBaseOptions, request, s.options.XPaddingObfsMode)
	if !IsPaddingValid(&s.options.V2RayXHTTPBaseOptions, paddingValue, validRange.From, validRange.To, PaddingMethod(s.options.XPaddingMethod)) {
		s.logger.ErrorContext(request.Context(), "invalid padding ("+paddingPlacement+") length:", int32(len(paddingValue)))
		writer.WriteHeader(http.StatusBadRequest)
		return
	}
	sessionId, seqStr := ExtractMetaFromRequest(s.options, request, s.path)
	if sessionId == "" && s.options.Mode != "" && s.options.Mode != "auto" && s.options.Mode != "stream-one" && s.options.Mode != "stream-up" {
		s.logger.ErrorContext(request.Context(), "stream-one mode is not allowed")
		writer.WriteHeader(http.StatusBadRequest)
		return
	}
	var forwardedAddrs []xnet.Address
	if len(s.options.TrustedXForwardedFor) > 0 {
		for _, key := range s.options.TrustedXForwardedFor {
			if len(request.Header.Values(key)) > 0 {
				forwardedAddrs = parseXForwardedFor(request.Header)
				break
			}
		}
	} else {
		forwardedAddrs = parseXForwardedFor(request.Header)
	}
	var remoteAddr net.Addr
	var err error
	remoteAddr, err = net.ResolveTCPAddr("tcp", request.RemoteAddr)
	if err != nil {
		remoteAddr = &net.TCPAddr{
			IP:   []byte{0, 0, 0, 0},
			Port: 0,
		}
	}
	if request.ProtoMajor == 3 {
		tcpAddr, ok := remoteAddr.(*net.TCPAddr)
		if !ok {
			s.logger.ErrorContext(request.Context(), "unexpected remote address type: ", remoteAddr)
			writer.WriteHeader(http.StatusInternalServerError)
			return
		}
		remoteAddr = &net.UDPAddr{
			IP:   tcpAddr.IP,
			Port: tcpAddr.Port,
		}
	}
	if len(forwardedAddrs) > 0 && forwardedAddrs[0].Family().IsIP() {
		remoteAddr = &net.TCPAddr{
			IP:   forwardedAddrs[0].IP(),
			Port: 0,
		}
	}
	var currentSession *httpSession
	if sessionId != "" {
		currentSession, err = s.upsertSession(sessionId)
		if err != nil {
			s.logger.ErrorContext(request.Context(), err)
			writer.WriteHeader(http.StatusInternalServerError)
			return
		}
	}
	scMaxEachPostBytes := int(s.options.GetNormalizedScMaxEachPostBytes().To)
	uplinkHTTPMethod := s.options.GetNormalizedUplinkHTTPMethod()
	uplinkDataPlacement := s.options.GetNormalizedUplinkDataPlacement()
	uplinkDataKey := s.options.GetNormalizedUplinkDataKey()
	isUplinkRequest := request.Method == uplinkHTTPMethod
	if request.Method == "GET" {
		isUplinkRequest = seqStr != ""
	}
	if !isUplinkRequest {
		switch uplinkDataPlacement {
		case option.PlacementHeader:
			isUplinkRequest = request.Header.Get(uplinkDataKey+"-Upstream") == "1"
		case option.PlacementCookie:
			if cookie, err := request.Cookie(uplinkDataKey + "_upstream"); err == nil {
				isUplinkRequest = cookie.Value == "1"
			}
		}
	}
	if isUplinkRequest && sessionId != "" {
		if seqStr == "" {
			if s.options.Mode != "" && s.options.Mode != "auto" && s.options.Mode != "stream-up" {
				s.logger.ErrorContext(request.Context(), "stream-up mode is not allowed")
				writer.WriteHeader(http.StatusBadRequest)
				return
			}
			httpSC := &httpServerConn{
				Instance:       done.New(),
				Reader:         request.Body,
				ResponseWriter: writer,
			}
			err = currentSession.uploadQueue.Push(Packet{
				Reader: httpSC,
			})
			if err != nil {
				s.logger.InfoContext(request.Context(), err, "failed to upload (PushReader)")
				writer.WriteHeader(http.StatusConflict)
			} else {
				writer.Header().Set("X-Accel-Buffering", "no")
				writer.Header().Set("Cache-Control", "no-store")
				writer.WriteHeader(http.StatusOK)
				scStreamUpServerSecs := s.options.GetNormalizedScStreamUpServerSecs()
				referrer := request.Header.Get("Referer")
				if referrer != "" && scStreamUpServerSecs.To > 0 {
					go func() {
						for {
							_, err := httpSC.Write(bytes.Repeat([]byte{'X'}, int(s.options.GetNormalizedXPaddingBytes().Rand())))
							if err != nil {
								break
							}
							time.Sleep(time.Duration(scStreamUpServerSecs.Rand()) * time.Second)
						}
					}()
				}
				select {
				case <-request.Context().Done():
				case <-httpSC.Wait():
				}
			}
			closeSilently(httpSC)
			return
		}
		if s.options.Mode != "" && s.options.Mode != "auto" && s.options.Mode != "packet-up" {
			s.logger.ErrorContext(request.Context(), "packet-up mode is not allowed")
			writer.WriteHeader(http.StatusBadRequest)
			return
		}
		var headerPayload []byte
		if uplinkDataPlacement == option.PlacementAuto || uplinkDataPlacement == option.PlacementHeader {
			var headerPayloadChunks []string
			for i := 0; true; i++ {
				chunk := request.Header.Get(fmt.Sprintf("%s-%d", uplinkDataKey, i))
				if chunk == "" {
					break
				}
				headerPayloadChunks = append(headerPayloadChunks, chunk)
			}
			headerPayloadEncoded := strings.Join(headerPayloadChunks, "")
			headerPayload, err = base64.RawURLEncoding.DecodeString(headerPayloadEncoded)
			if err != nil {
				s.logger.InfoContext(request.Context(), err, "Invalid base64 in header's payload")
				writer.WriteHeader(http.StatusBadRequest)
				return
			}
		}
		var cookiePayload []byte
		if uplinkDataPlacement == option.PlacementAuto || uplinkDataPlacement == option.PlacementCookie {
			var cookiePayloadChunks []string
			for i := 0; true; i++ {
				cookieName := fmt.Sprintf("%s_%d", uplinkDataKey, i)
				if c, err := request.Cookie(cookieName); err == nil {
					cookiePayloadChunks = append(cookiePayloadChunks, c.Value)
				} else {
					break
				}
			}
			cookiePayloadEncoded := strings.Join(cookiePayloadChunks, "")
			cookiePayload, err = base64.RawURLEncoding.DecodeString(cookiePayloadEncoded)
			if err != nil {
				s.logger.InfoContext(request.Context(), err, "Invalid base64 in cookies' payload")
				writer.WriteHeader(http.StatusBadRequest)
				return
			}
		}
		var bodyPayload []byte
		if uplinkDataPlacement == option.PlacementAuto || uplinkDataPlacement == option.PlacementBody {
			var readErr error
			if request.ContentLength > int64(scMaxEachPostBytes) {
				s.logger.ErrorContext(request.Context(), "Too large upload. scMaxEachPostBytes is set to ", scMaxEachPostBytes, "but request size exceed it. Adjust scMaxEachPostBytes on the server to be at least as large as client.")
				writer.WriteHeader(http.StatusRequestEntityTooLarge)
				return
			}
			if request.ContentLength > 0 {
				bodyPayload = make([]byte, request.ContentLength)
				_, readErr = io.ReadFull(request.Body, bodyPayload)
			} else {
				bodyPayload, readErr = buf.ReadAllToBytes(io.LimitReader(request.Body, int64(scMaxEachPostBytes)+1))
			}
			if readErr != nil {
				s.logger.InfoContext(request.Context(), readErr, "failed to read body payload")
				writer.WriteHeader(http.StatusBadRequest)
				return
			}
		}
		var payload []byte
		switch uplinkDataPlacement {
		case option.PlacementHeader:
			payload = headerPayload
		case option.PlacementCookie:
			payload = cookiePayload
		case option.PlacementBody:
			payload = bodyPayload
		case option.PlacementAuto:
			payload = slices.Concat(headerPayload, cookiePayload, bodyPayload)
		}
		if len(payload) > scMaxEachPostBytes {
			s.logger.ErrorContext(request.Context(), "Too large upload. scMaxEachPostBytes is set to ", scMaxEachPostBytes, "but request size exceed it. Adjust scMaxEachPostBytes on the server to be at least as large as client.")
			writer.WriteHeader(http.StatusRequestEntityTooLarge)
			return
		}
		seq, err := strconv.ParseUint(seqStr, 10, 64)
		if err != nil {
			s.logger.InfoContext(request.Context(), err, "failed to upload (ParseUint)")
			writer.WriteHeader(http.StatusInternalServerError)
			return
		}
		err = currentSession.uploadQueue.Push(Packet{
			Payload: payload,
			Seq:     seq,
		})
		if err != nil {
			s.logger.InfoContext(request.Context(), err, "failed to upload (PushPayload)")
			writer.WriteHeader(http.StatusInternalServerError)
			return
		}
		if len(bodyPayload) == 0 {
			writer.Header().Set("Cache-Control", "no-store")
		}
		writer.WriteHeader(http.StatusOK)
	} else if request.Method == "GET" || sessionId == "" {
		if sessionId != "" {
			closeSilently(currentSession.isFullyConnected)
			defer s.sessions.Delete(sessionId)
		}
		writer.Header().Set("X-Accel-Buffering", "no")
		writer.Header().Set("Cache-Control", "no-store")
		if !s.options.NoSSEHeader {
			writer.Header().Set("Content-Type", "text/event-stream")
		}
		writer.WriteHeader(http.StatusOK)
		flusher, ok := writer.(http.Flusher)
		if !ok {
			s.logger.ErrorContext(request.Context(), "response writer does not support flushing")
			return
		}
		flusher.Flush()
		httpSC := &httpServerConn{
			Instance:       done.New(),
			Reader:         request.Body,
			ResponseWriter: writer,
		}
		conn := splitConn{
			writer:     httpSC,
			reader:     httpSC,
			remoteAddr: remoteAddr,
			localAddr:  s.localAddr,
		}
		if sessionId != "" {
			conn.reader = currentSession.uploadQueue
		}
		s.handler.NewConnectionEx(request.Context(), &conn, sHttp.SourceAddress(request), M.Socksaddr{}, func(it error) {})
		select {
		case <-request.Context().Done():
		case <-httpSC.Wait():
		}
		closeSilently(&conn)
	} else {
		s.logger.ErrorContext(request.Context(), "unsupported method: ", request.Method)
		writer.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (s *Server) Network() []string {
	var networks []string
	if s.enableTCP {
		networks = append(networks, N.NetworkTCP)
	}
	if s.enableH3 {
		networks = append(networks, N.NetworkUDP)
	}
	return networks
}

func (s *Server) Serve(listener net.Listener) error {
	if !s.enableTCP {
		return os.ErrInvalid
	}
	if s.tlsConfig != nil {
		listener = aTLS.NewListener(listener, s.tlsConfig)
	}
	s.localAddr = listener.Addr()
	return s.httpServer.Serve(listener)
}

func (s *Server) ServePacket(listener net.PacketConn) error {
	if !s.enableH3 {
		return os.ErrInvalid
	}
	quicListener, err := qtls.ListenEarly(listener, s.tlsConfig, s.quicConfig)
	if err != nil {
		return err
	}
	s.localAddr = quicListener.Addr()
	return s.http3Server.ServeListener(quicListener)
}

func (s *Server) Close() error {
	var closers []any
	if s.enableTCP {
		closers = append(closers, s.httpServer)
	}
	if s.enableH3 {
		closers = append(closers, s.http3Server)
	}
	return common.Close(closers...)
}

func (s *Server) upsertSession(sessionId string) (*httpSession, error) {
	currentSessionAny, ok := s.sessions.Load(sessionId)
	if ok {
		return httpSessionFromAny(currentSessionAny)
	}
	s.sessionMu.Lock()
	defer s.sessionMu.Unlock()
	currentSessionAny, ok = s.sessions.Load(sessionId)
	if ok {
		return httpSessionFromAny(currentSessionAny)
	}
	session := &httpSession{
		uploadQueue:      NewUploadQueue(s.options.GetNormalizedScMaxBufferedPosts()),
		isFullyConnected: done.New(),
	}
	s.sessions.Store(sessionId, session)
	go func() {
		reapTimer := time.NewTimer(30 * time.Second)
		defer reapTimer.Stop()
		select {
		case <-reapTimer.C:
			s.sessions.Delete(sessionId)
			closeSilently(session.uploadQueue)
		case <-session.isFullyConnected.Wait():
		}
	}()
	return session, nil
}

func httpSessionFromAny(session any) (*httpSession, error) {
	httpSession, ok := session.(*httpSession)
	if !ok {
		return nil, fmt.Errorf("xhttp session has unexpected type %T", session)
	}
	return httpSession, nil
}

func ExtractMetaFromRequest(options *option.V2RayXHTTPOptions, req *http.Request, path string) (sessionId string, seqStr string) {
	sessionPlacement := options.GetNormalizedSessionPlacement()
	seqPlacement := options.GetNormalizedSeqPlacement()
	sessionKey := options.GetNormalizedSessionKey()
	seqKey := options.GetNormalizedSeqKey()
	var subpath []string
	pathPart := 0
	if sessionPlacement == option.PlacementPath || seqPlacement == option.PlacementPath {
		subpath = strings.Split(req.URL.Path[len(path):], "/")
	}
	switch sessionPlacement {
	case option.PlacementPath:
		if len(subpath) > pathPart {
			sessionId = subpath[pathPart]
			pathPart += 1
		}
	case option.PlacementQuery:
		sessionId = req.URL.Query().Get(sessionKey)
	case option.PlacementHeader:
		sessionId = req.Header.Get(sessionKey)
	case option.PlacementCookie:
		if cookie, e := req.Cookie(sessionKey); e == nil {
			sessionId = cookie.Value
		}
	}
	switch seqPlacement {
	case option.PlacementPath:
		if len(subpath) > pathPart {
			seqStr = subpath[pathPart]
			pathPart += 1
		}
	case option.PlacementQuery:
		seqStr = req.URL.Query().Get(seqKey)
	case option.PlacementHeader:
		seqStr = req.Header.Get(seqKey)
	case option.PlacementCookie:
		if cookie, e := req.Cookie(seqKey); e == nil {
			seqStr = cookie.Value
		}
	}
	return sessionId, seqStr
}
