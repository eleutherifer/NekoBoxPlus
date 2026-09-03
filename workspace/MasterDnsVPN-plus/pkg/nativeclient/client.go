package nativeclient

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"masterdnsvpn-go/internal/client"
	"masterdnsvpn-go/internal/config"
)

type ProtectFunc func(fd int32) bool
type ResolverProgressCallback func(found int, total int, ready bool)
type FatalErrorCallback func(error)

var ErrNoWorkingDNS = client.ErrNoWorkingDNS

type Client struct {
	cl                *client.Client
	ctx               context.Context
	cancel            context.CancelFunc
	done              chan struct{}
	requireLocalProxy bool
	closing           atomic.Bool
	runErrMu          sync.RWMutex
	runErr            error
}

type Options struct {
	ConfigText        string
	ResolversText     string
	ProfileDir        string
	Protect           ProtectFunc
	DisableLocalProxy bool
	LogWriter         io.Writer
	ResolverProgress  ResolverProgressCallback
	FatalError        FatalErrorCallback
}

func Start(ctx context.Context, options Options) (*Client, error) {
	if strings.TrimSpace(options.ProfileDir) == "" {
		return nil, errors.New("profile dir is required")
	}
	if err := os.MkdirAll(options.ProfileDir, 0o750); err != nil {
		return nil, err
	}
	configPath := filepath.Join(options.ProfileDir, "client_config.toml")
	resolversPath := filepath.Join(options.ProfileDir, "client_resolvers.txt")
	if err := os.WriteFile(configPath, []byte(options.ConfigText), 0o640); err != nil {
		return nil, err
	}
	if err := os.WriteFile(resolversPath, []byte(options.ResolversText), 0o640); err != nil {
		return nil, err
	}

	cfg, err := config.LoadClientConfigWithOverrides(configPath, config.ClientConfigOverrides{
		ResolversFilePath: &resolversPath,
	})
	if err != nil {
		return nil, err
	}
	cfg.DisableLocalProxy = options.DisableLocalProxy
	cfg.ProtocolType = "SOCKS5"

	if options.Protect != nil {
		client.DialUDPFunc = protectedDialUDP(options.Protect)
		client.ListenUDPFunc = protectedListenUDP(options.Protect)
	}

	cl, err := client.BootstrapLoadedConfigWithOptions(cfg, filepath.Join(options.ProfileDir, "client.log"), client.BootstrapOptions{
		LogWriter: options.LogWriter,
	})
	if err != nil {
		return nil, err
	}
	if options.ResolverProgress != nil {
		cl.SetResolverProgressCallback(func(progress client.ResolverProgress) {
			options.ResolverProgress(progress.Found, progress.Total, progress.Stage == client.ResolverProgressReady)
		})
	}
	runCtx, cancel := context.WithCancel(ctx)
	c := &Client{
		cl:                cl,
		ctx:               runCtx,
		cancel:            cancel,
		done:              make(chan struct{}),
		requireLocalProxy: !cfg.DisableLocalProxy,
	}
	go func() {
		defer close(c.done)
		err := cl.Run(runCtx)
		c.runErrMu.Lock()
		c.runErr = err
		c.runErrMu.Unlock()
		if err != nil && options.FatalError != nil && !c.closing.Load() && runCtx.Err() == nil {
			options.FatalError(err)
		}
	}()
	return c, nil
}

func (c *Client) Close() error {
	if c == nil {
		return nil
	}
	c.closing.Store(true)
	c.cancel()
	<-c.done
	return nil
}

func (c *Client) ActiveSocksPort() int {
	if c == nil || c.cl == nil {
		return 0
	}
	return c.cl.ActiveListenPort()
}

func (c *Client) TotalResolverCount() int {
	if c == nil || c.cl == nil || c.cl.Balancer() == nil {
		return 0
	}
	return c.cl.Balancer().TotalResolverCount()
}

func (c *Client) DialContext(ctx context.Context, host string, port uint16) (net.Conn, error) {
	if c == nil || c.cl == nil {
		return nil, net.ErrClosed
	}
	if err := c.WaitReady(ctx); err != nil {
		return nil, err
	}
	local, remote := net.Pipe()
	atyp := byte(client.SOCKS5_ATYP_DOMAIN)
	addr := host
	if ip := net.ParseIP(host); ip != nil {
		if ip.To4() != nil {
			atyp = client.SOCKS5_ATYP_IPV4
			addr = ip.String()
		} else {
			atyp = client.SOCKS5_ATYP_IPV6
			addr = ip.String()
		}
	}
	c.cl.OpenNativeSOCKSStream(ctx, remote, addr, port, atyp)
	return local, nil
}

func (c *Client) QueryDNS(ctx context.Context, query []byte) ([]byte, error) {
	if c == nil || c.cl == nil {
		return nil, net.ErrClosed
	}
	if err := c.WaitReady(ctx); err != nil {
		return nil, err
	}
	responseCh := make(chan []byte, 1)
	respond := func(response []byte) {
		select {
		case responseCh <- append([]byte(nil), response...):
		default:
		}
	}
	if c.cl.ProcessDNSQuery(query, nil, respond) {
		return c.waitDNSResponse(ctx, responseCh)
	}
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case response := <-responseCh:
			return response, nil
		case <-ticker.C:
			if c.cl.ProcessDNSQuery(query, nil, respond) {
				return c.waitDNSResponse(ctx, responseCh)
			}
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
}

func (c *Client) WaitReady(ctx context.Context) error {
	if c == nil || c.cl == nil {
		return net.ErrClosed
	}
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	for {
		if c.cl.SessionReady() {
			if !c.requireLocalProxy || c.cl.ActiveListenPort() > 0 {
				return nil
			}
		}
		select {
		case <-ticker.C:
		case <-c.done:
			if err := c.RunError(); err != nil {
				return err
			}
			return net.ErrClosed
		case <-ctx.Done():
			if err := c.RunError(); err != nil {
				return err
			}
			if c.cl.SessionReady() && c.requireLocalProxy {
				return fmt.Errorf("masterdnsvpn local proxy not ready: %w", ctx.Err())
			}
			return fmt.Errorf("masterdnsvpn session not ready: %w", ctx.Err())
		}
	}
}

func (c *Client) RunError() error {
	if c == nil {
		return nil
	}
	c.runErrMu.RLock()
	defer c.runErrMu.RUnlock()
	return c.runErr
}

func (c *Client) waitDNSResponse(ctx context.Context, responseCh <-chan []byte) ([]byte, error) {
	select {
	case response := <-responseCh:
		return response, nil
	case <-c.done:
		return nil, net.ErrClosed
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

type dnsPacketConn struct {
	client   *Client
	ctx      context.Context
	cancel   context.CancelFunc
	deadline time.Time
	mu       sync.Mutex
	readCh   chan []byte
}

func NewDNSPacketConn(ctx context.Context, client *Client) net.PacketConn {
	pcCtx, cancel := context.WithCancel(ctx)
	return &dnsPacketConn{client: client, ctx: pcCtx, cancel: cancel, readCh: make(chan []byte, 8)}
}

func (p *dnsPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	select {
	case response := <-p.readCh:
		return copy(b, response), &net.UDPAddr{IP: net.IPv4zero, Port: 53}, nil
	case <-p.ctx.Done():
		return 0, nil, net.ErrClosed
	}
}

func (p *dnsPacketConn) WriteTo(b []byte, _ net.Addr) (int, error) {
	query := append([]byte(nil), b...)
	go func() {
		ctx := p.ctx
		p.mu.Lock()
		if !p.deadline.IsZero() {
			var cancel context.CancelFunc
			ctx, cancel = context.WithDeadline(ctx, p.deadline)
			defer cancel()
		}
		p.mu.Unlock()
		response, err := p.client.QueryDNS(ctx, query)
		if err != nil {
			return
		}
		select {
		case p.readCh <- response:
		case <-p.ctx.Done():
		}
	}()
	return len(b), nil
}

func (p *dnsPacketConn) Close() error        { p.cancel(); return nil }
func (p *dnsPacketConn) LocalAddr() net.Addr { return &net.UDPAddr{IP: net.IPv4zero, Port: 0} }
func (p *dnsPacketConn) SetDeadline(t time.Time) error {
	p.mu.Lock()
	p.deadline = t
	p.mu.Unlock()
	return nil
}
func (p *dnsPacketConn) SetReadDeadline(t time.Time) error  { return p.SetDeadline(t) }
func (p *dnsPacketConn) SetWriteDeadline(t time.Time) error { return p.SetDeadline(t) }
