package masterdnsvpn

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"libcore/masterdnsvpnbridge"
	native "masterdnsvpn-go/pkg/nativeclient"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

const TypeMasterDnsVPN = "masterdnsvpn"

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[OutboundOptions](registry, TypeMasterDnsVPN, NewOutbound)
}

type Outbound struct {
	outbound.Adapter
	ctx     context.Context
	logger  logger.ContextLogger
	options OutboundOptions
	once    sync.Once
	client  *native.Client
	err     error
}

func NewOutbound(ctx context.Context, _ adapter.Router, logger log.ContextLogger, tag string, options OutboundOptions) (adapter.Outbound, error) {
	return &Outbound{
		Adapter: outbound.NewAdapter(TypeMasterDnsVPN, tag, []string{N.NetworkTCP, N.NetworkUDP}, nil),
		ctx:     ctx,
		logger:  logger,
		options: options,
	}, nil
}

func (o *Outbound) Close() error {
	if o.client != nil {
		return o.client.Close()
	}
	return nil
}

func (o *Outbound) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	client, err := o.getClient()
	if err == nil && client != nil {
		masterdnsvpnbridge.Report(0, int32(client.TotalResolverCount()), false)
	}
	return err
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		client, err := o.getClient()
		if err != nil {
			return nil, err
		}
		o.logger.InfoContext(ctx, "outbound connection to ", destination)
		return client.DialContext(ctx, destination.AddrString(), destination.Port)
	case N.NetworkUDP:
		conn, err := o.ListenPacket(ctx, destination)
		if err != nil {
			return nil, err
		}
		return bufio.NewBindPacketConn(conn, destination), nil
	default:
		return nil, E.New("unsupported network: ", network)
	}
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if destination.Port != 53 {
		return nil, E.New("masterdnsvpn supports UDP only for DNS")
	}
	client, err := o.getClient()
	if err != nil {
		return nil, err
	}
	o.logger.InfoContext(ctx, "outbound DNS packet connection to ", destination)
	return native.NewDNSPacketConn(ctx, client), nil
}

func (o *Outbound) getClient() (*native.Client, error) {
	o.once.Do(func() {
		o.logger.Info("starting MasterDnsVPN native client")
		profileDir := o.options.ProfileDir
		if profileDir == "" {
			profileDir = filepath.Join(os.TempDir(), "masterdnsvpn-"+o.Tag())
		}
		platformInterface := service.FromContext[adapter.PlatformInterface](o.ctx)
		o.client, o.err = native.Start(o.ctx, native.Options{
			ConfigText:    o.options.ConfigText,
			ResolversText: strings.Join(o.options.Resolvers, "\n"),
			ProfileDir:    profileDir,
			Protect: func(fd int32) bool {
				if platformInterface == nil {
					return true
				}
				return platformInterface.AutoDetectInterfaceControl(int(fd)) == nil
			},
			DisableLocalProxy: true,
			LogWriter:         masterDnsVPNSingBoxLogWriter{logger: o.logger},
			ResolverProgress: func(found int, total int, ready bool) {
				masterdnsvpnbridge.Report(int32(found), int32(total), ready)
			},
			FatalError: func(err error) {
				masterdnsvpnbridge.ReportFailure(errors.Is(err, native.ErrNoWorkingDNS), err.Error())
			},
		})
		if o.err == nil && o.client != nil && o.client.ActiveSocksPort() != 0 {
			port := o.client.ActiveSocksPort()
			_ = o.client.Close()
			o.client = nil
			o.err = fmt.Errorf("MasterDnsVPN unexpectedly exposed a local proxy on port %d", port)
		}
	})
	return o.client, o.err
}
