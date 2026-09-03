package main

import (
	"context"
	stdTLS "crypto/tls"
	"encoding/json"
	"flag"
	"io"
	"log"
	"net"
	"os"
	"slices"
	"time"

	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/bufio"
	"github.com/sagernet/sing/common/canceler"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/tls"
	"github.com/sagernet/sing/protocol/socks"

	"libcore/protocol/trusttunnel/sing-trusttunnel"
)

const (
	DefaultTCPTimeout = 15 * time.Second
	DefaultUDPTimeout = 3 * time.Minute
)

var (
	configPath  string
	showVersion bool
)

func main() {
	flag.BoolVar(&showVersion, "v", false, "show version")
	flag.StringVar(&configPath, "c", "config.json", "config file path")
	flag.Usage = printUsage
	flag.Parse()

	if showVersion {
		_, _ = os.Stdout.WriteString("sing-trusttunnel " + trusttunnel.Version + "\n")
		return
	}

	err := runCommand()
	if err != nil {
		log.Fatal(err)
	}
}

func printUsage() {
	_, _ = os.Stderr.WriteString(`Usage: sing-trusttunnel [options] <command> [arguments]

Options:
  -v              Show version
  -c <path>       Config file path (default: config.json)

Commands:
  client          Run as client mode
  server          Run as server mode
  config-to-url   Convert client config to TrustTunnel URL
  url-to-config   Convert TrustTunnel URL to config

Examples:
  # Run client
  sing-trusttunnel -c client.json client

  # Run server
  sing-trusttunnel -c server.json server

  # Convert config to URL
  sing-trusttunnel -c client.json config-to-url

  # Convert URL to client config
  sing-trusttunnel url-to-config client "tt://..."

  # Convert URL to server config
  sing-trusttunnel url-to-config server "tt://..."

`)
}

func runCommand() error {
	args := flag.Args()
	if len(args) == 0 {
		printUsage()
		return E.New("missing command")
	}

	command := args[0]
	switch command {
	case "client":
		if len(args) != 1 {
			return E.New("usage: sing-trusttunnel -c <config> client")
		}
		err := runClient()
		if err != nil {
			return E.Cause(err, "client")
		}
	case "server":
		if len(args) != 1 {
			return E.New("usage: sing-trusttunnel -c <config> server")
		}
		err := runServer()
		if err != nil {
			return E.Cause(err, "server")
		}
	case "config-to-url":
		if len(args) != 1 {
			return E.New("usage: sing-trusttunnel -c <config> config-to-url")
		}
		err := runConfigToURL()
		if err != nil {
			return E.Cause(err, "config-to-url")
		}
	case "url-to-config":
		if len(args) != 2 && len(args) != 3 {
			return E.New("usage: sing-trusttunnel url-to-config <client|server> <url>")
		}
		configType := args[1]
		var url string
		if len(args) == 3 {
			url = args[2]
		}
		if url == "" || url == "-" {
			buffer, err := io.ReadAll(os.Stdin)
			if err != nil {
				return E.Cause(err, "read url from stdin")
			}
			url = string(buffer)
		}
		err := runURLToConfig(configType, url)
		if err != nil {
			return E.Cause(err, "url-to-config")
		}
	case "help", "-h", "--help":
		printUsage()
	default:
		printUsage()
		return E.New("unknown command: ", command)
	}
	return nil
}

func parseConfig[T any](path string) (*T, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, E.Cause(err, "open config file")
	}
	defer file.Close()
	decoder := json.NewDecoder(file)
	decoder.DisallowUnknownFields()
	options := common.DefaultValue[T]()
	err = decoder.Decode(&options)
	if err != nil {
		return nil, err
	}
	return &options, nil
}

var _ tls.ServerConfig = (*TLSConfig)(nil)

type TLSConfig struct {
	config           *stdTLS.Config
	handshakeTimeout time.Duration
}

func (t *TLSConfig) ServerName() string {
	return t.config.ServerName
}

func (t *TLSConfig) SetServerName(serverName string) {
	t.config.ServerName = serverName
}

func (t *TLSConfig) NextProtos() []string {
	return slices.Clone(t.config.NextProtos)
}

func (t *TLSConfig) SetNextProtos(nextProto []string) {
	t.config.NextProtos = slices.Clone(nextProto)
}

func (t *TLSConfig) HandshakeTimeout() time.Duration {
	return t.handshakeTimeout
}

func (t *TLSConfig) SetHandshakeTimeout(timeout time.Duration) {
	t.handshakeTimeout = timeout
}

func (t *TLSConfig) STDConfig() (*tls.STDConfig, error) {
	return t.config, nil
}

func (t *TLSConfig) Client(conn net.Conn) (tls.Conn, error) {
	return stdTLS.Client(conn, t.config), nil
}

func (t *TLSConfig) Clone() tls.Config {
	return &TLSConfig{
		config:           t.config.Clone(),
		handshakeTimeout: t.handshakeTimeout,
	}
}

func (t *TLSConfig) Start() error {
	return nil
}

func (t *TLSConfig) Close() error {
	return nil
}

func (t *TLSConfig) Server(conn net.Conn) (tls.Conn, error) {
	return stdTLS.Server(conn, t.config), nil
}

var (
	_ trusttunnel.HandlerEx = (*Router)(nil)
	_ socks.HandlerEx       = (*Router)(nil)
)

type Router struct {
	dialer N.Dialer
	logger logger.ContextLogger
}

func NewRouter(dialer N.Dialer, logger logger.ContextLogger) *Router {
	return &Router{
		dialer: dialer,
		logger: logger,
	}
}

func (r *Router) NewConnectionEx(ctx context.Context, conn net.Conn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	defer conn.Close()
	destinationConn, err := r.dialer.DialContext(ctx, N.NetworkTCP, destination)
	if err != nil {
		onClose(err)
		r.logger.ErrorContext(ctx, "DialContext: ", err)
		return
	}
	defer destinationConn.Close()
	err = bufio.CopyConn(ctx, conn, destinationConn)
	onClose(err)
	if !E.IsClosedOrCanceled(err) {
		r.logger.ErrorContext(ctx, "connection closed: ", err)
	}
}

func (r *Router) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	defer conn.Close()
	destinationConn, err := r.dialer.ListenPacket(ctx, destination)
	if err != nil {
		onClose(err)
		r.logger.ErrorContext(ctx, "ListenPacket: ", err)
		return
	}
	defer destinationConn.Close()
	ctx, timeoutConn := canceler.NewPacketConn(ctx, bufio.NewPacketConn(destinationConn), DefaultUDPTimeout)
	err = bufio.CopyPacketConn(ctx, conn, timeoutConn)
	onClose(err)
	if !E.IsClosedOrCanceled(err) {
		r.logger.ErrorContext(ctx, "packet connection closed: ", err)
	}
}

func newDefaultDialer() N.Dialer {
	return &N.DefaultDialer{
		Dialer: net.Dialer{
			Timeout: DefaultTCPTimeout,
		},
	}
}
