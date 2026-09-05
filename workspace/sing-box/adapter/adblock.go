package adapter

import (
	"context"
	"net"
	"time"

	"github.com/miekg/dns"
	N "github.com/sagernet/sing/common/network"
)

type AdblockService interface {
	LifecycleService
	HasProcessConstraints() bool
	Stats() AdblockStats
	GetFilterMetadata(uri string) (AdblockFilterMetadata, error)
	PreCacheFilter(uri string, databasePath string) (string, error)
	DeleteCachedFilter(uri string, databasePath string) error
	CheckDNSResponse(ctx context.Context, message *dns.Msg, response *dns.Msg)
	HandleTCP(ctx context.Context, conn net.Conn, metadata InboundContext, outbound Outbound, onClose N.CloseHandlerFunc) (bool, error)
	HandleUDP(ctx context.Context, conn N.PacketConn, metadata InboundContext, outbound Outbound, onClose N.CloseHandlerFunc) (bool, error)
}

type AdblockDatabase interface {
	Start(stage StartStage) error
	Close() error

	LoadFilterList(tag string) *SavedBinary
	SaveFilterList(tag string, set *SavedBinary) error
	DeleteFilterList(tag string) error
	LoadAdblockStats() (total uint64, blocked uint64, loaded bool)
	StoreAdblockStats(total uint64, blocked uint64) error
}

type AdblockStats interface {
	TotalRequests() uint64
	BlockedRequests() uint64
}

type AdblockFilterMetadata struct {
	URI             string
	Title           string
	Description     string
	LastModified    string
	Expires         string
	ExpiresInterval time.Duration
	License         string
	Homepage        string
	Forums          string
	RuleCount       int
	LastUpdated     time.Time
}
