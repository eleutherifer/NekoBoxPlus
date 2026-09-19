//go:build with_adblock

package ctx

import (
	"context"
	"net"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	"github.com/sagernet/sing-box/experimental/adblock/consts"
	N "github.com/sagernet/sing/common/network"
)

type Conn struct {
	Ctx        context.Context
	Engine     adblockrust.Engine
	Conn       net.Conn
	PacketConn N.PacketConn
	Metadata   adapter.InboundContext
	Outbound   adapter.Outbound
	UTLS       consts.UTLSFingerprintID
	Cronet     bool
	UseTLS     bool
	UseHTTP2   bool
}

func NewConn(ctx context.Context, engine adblockrust.Engine, conn net.Conn, packetConn N.PacketConn, metadata adapter.InboundContext, outbound adapter.Outbound) *Conn {
	return &Conn{
		Ctx:        ctx,
		Engine:     engine,
		Conn:       conn,
		PacketConn: packetConn,
		Metadata:   metadata,
		Outbound:   outbound,
	}
}
