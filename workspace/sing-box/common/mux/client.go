package mux

import (
	"context"
	"net"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/muxcool"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	mux "github.com/sagernet/sing-mux"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type Client struct {
	mux  *mux.Client
	cool *muxcool.Client
}

func NewClientWithOptions(dialer N.Dialer, logger logger.Logger, options option.OutboundMultiplexOptions) (*Client, error) {
	if !options.Enabled {
		return nil, nil
	}
	if options.Protocol == "mux.cool" {
		cool, err := muxcool.NewClientWithOptions(&clientDialer{dialer}, logger, options)
		if err != nil {
			return nil, err
		}
		return &Client{cool: cool}, nil
	}
	var brutalOptions mux.BrutalOptions
	if options.Brutal != nil && options.Brutal.Enabled {
		brutalOptions = mux.BrutalOptions{
			Enabled:    true,
			SendBPS:    uint64(options.Brutal.UpMbps * C.MbpsToBps),
			ReceiveBPS: uint64(options.Brutal.DownMbps * C.MbpsToBps),
		}
		if brutalOptions.SendBPS < mux.BrutalMinSpeedBPS {
			return nil, E.New("brutal: invalid upload speed")
		}
		if brutalOptions.ReceiveBPS < mux.BrutalMinSpeedBPS {
			return nil, E.New("brutal: invalid download speed")
		}
	}
	inner, err := mux.NewClient(mux.Options{
		Dialer:         &clientDialer{dialer},
		Logger:         logger,
		Protocol:       options.Protocol,
		MaxConnections: options.MaxConnections,
		MinStreams:     options.MinStreams,
		MaxStreams:     options.MaxStreams,
		Padding:        options.Padding,
		Brutal:         brutalOptions,
	})
	if err != nil {
		return nil, err
	}
	return &Client{mux: inner}, nil
}

type clientDialer struct {
	N.Dialer
}

func (d *clientDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return d.Dialer.DialContext(adapter.OverrideContext(ctx), network, destination)
}

func (d *clientDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return d.Dialer.ListenPacket(adapter.OverrideContext(ctx), destination)
}

// DialContext routes to the active multiplex implementation.
func (c *Client) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if c.cool != nil {
		return c.cool.DialContext(ctx, network, destination)
	}
	return c.mux.DialContext(ctx, network, destination)
}

// ListenPacket routes to the active multiplex implementation.
func (c *Client) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if c.cool != nil {
		return c.cool.ListenPacket(ctx, destination)
	}
	return c.mux.ListenPacket(ctx, destination)
}

// Reset drops all underlying connections, forcing re-establishment on the next
// dial. Used on interface/configuration updates.
func (c *Client) Reset() {
	if c == nil {
		return
	}
	if c.cool != nil {
		c.cool.Reset()
		return
	}
	c.mux.Reset()
}

// Close closes the client and all of its underlying connections.
func (c *Client) Close() error {
	if c == nil {
		return nil
	}
	if c.cool != nil {
		return c.cool.Close()
	}
	return c.mux.Close()
}
