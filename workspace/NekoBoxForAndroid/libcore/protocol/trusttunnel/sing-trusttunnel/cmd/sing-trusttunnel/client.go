package main

import (
	std_bufio "bufio"
	"context"
	stdTLS "crypto/tls"
	"net"
	"net/netip"
	"os"
	"os/signal"

	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/auth"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/protocol/socks"

	trusttunnel "libcore/protocol/trusttunnel/sing-trusttunnel"

	"golang.org/x/net/http2"
)

func parseClientConfig(path string) (*ClientOptions, error) {
	return parseConfig[ClientOptions](path)
}

func runClient() error {
	options, err := parseClientConfig(configPath)
	if err != nil {
		return E.Cause(err, "parse client config")
	}

	myLogger := NewMyLogger(os.Stdout, "sing-trusttunnel client")

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt)
	defer cancel()

	server := M.ParseSocksaddrHostPort(options.Server, options.ServerPort)
	if !server.IsValid() {
		return E.New("server: ", options.Server, ", port: ", options.ServerPort, " is invalid")
	}
	var alpn []string
	if options.QUIC {
		alpn = []string{http3.NextProtoH3}
	} else {
		alpn = []string{http2.NextProtoTLS}
	}
	tlsConfig := &TLSConfig{
		config: &stdTLS.Config{
			NextProtos:         alpn,
			ServerName:         options.ServerName,
			InsecureSkipVerify: options.AllowInsecure,
		},
	}
	client, err := trusttunnel.NewClient(trusttunnel.ClientOptions{
		Ctx:    ctx,
		Logger: myLogger,
		Detour: newDefaultDialer(),
		Server: server,
		Auth: auth.User{
			Username: options.Username,
			Password: options.Password,
		},
		TLSConfig:             tlsConfig,
		QUIC:                  options.QUIC,
		ForceQUIC:             options.ForceQUIC,
		UseCronetQUIC:         options.UseCronetQUIC,
		UseCronetHTTPS:        options.UseCronetHTTPS,
		QUICCongestionControl: "bbr",
		ClientRandomPrefix:    options.ClientRandomPrefix,
		HealthCheck:           options.HealthCheck,
		ResolveFunc: func(fqdn string) (netip.Addr, error) {
			addresses, err := net.LookupIP(fqdn)
			if err != nil {
				return netip.Addr{}, err
			}
			if len(addresses) == 0 {
				return netip.Addr{}, E.New("no address found for ", fqdn)
			}
			address := addresses[0]
			netipAddr, loaded := netip.AddrFromSlice(address)
			if !loaded {
				return netip.Addr{}, E.New("got address: ", address, " but failed to turn to netip")
			}
			return netipAddr, nil
		},
	})
	if err != nil {
		return E.Cause(err, "create client")
	}
	err = client.Start()
	if err != nil {
		return E.Cause(err, "start client")
	}
	defer client.Close()

	listenAddress := M.ParseSocksaddrHostPort(options.Listen, options.ListenPort)
	if !listenAddress.IsValid() {
		return E.New("invalid socks listen address: ", options.Listen, " port: ", options.ListenPort)
	}
	var listenConfig net.ListenConfig
	listener, err := listenConfig.Listen(ctx, N.NetworkTCP, listenAddress.String())
	if err != nil {
		return E.Cause(err, "listen socks")
	}
	defer listener.Close()
	go func() {
		<-ctx.Done()
		_ = listener.Close()
	}()
	authenticator := auth.NewAuthenticator(options.Auth)
	router := NewRouter(TrustDialer{client}, myLogger)

	for {
		conn, err := listener.Accept()
		if err != nil {
			if E.IsClosed(err) {
				myLogger.Debug("client closed: ", err)
				return nil
			}
			myLogger.Error("accept connection: ", err)
			return err
		}
		go handleClient(ctx, conn, authenticator, router, myLogger)
	}
}

func handleClient(
	ctx context.Context,
	conn net.Conn,
	authenticator *auth.Authenticator,
	handler *Router,
	logger logger.ContextLogger,
) {
	defer conn.Close()

	err := socks.HandleConnectionEx(ctx, conn, std_bufio.NewReader(conn), authenticator, handler, PacketListener{}, DefaultUDPTimeout, M.SocksaddrFromNet(conn.RemoteAddr()), func(it error) {})
	if err != nil {
		logger.ErrorContext(ctx, "handle connection: ", err)
	}
}

var _ N.Dialer = TrustDialer{}

type TrustDialer struct {
	*trusttunnel.Client
}

func (t TrustDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return t.Dial(ctx, destination)
}

func (t TrustDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return t.Client.ListenPacket(ctx)
}

var _ socks.PacketListener = PacketListener{}

type PacketListener struct{}

func (p PacketListener) ListenPacket(listenConfig net.ListenConfig, ctx context.Context, network string, address string) (net.PacketConn, error) {
	return listenConfig.ListenPacket(ctx, network, address)
}
