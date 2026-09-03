package neko_common

import (
	"context"
	"net"
	"net/http"
)

var Version_v2ray = "N/A"
var Version_neko = "N/A"

var Debug bool

// platform

var RunMode int

const (
	RunMode_Other = iota
	RunMode_NekoRay_Core
	RunMode_NekoBox_Core
	RunMode_NekoBoxForAndroid
)

// proxy (if specifiedInstance==nil, access without proxy)

var GetCurrentInstance func() interface{}

var DialContext func(ctx context.Context, specifiedInstance interface{}, network, addr string) (net.Conn, error)

// DialUDP core bug?
var DialUDP func(ctx context.Context, specifiedInstance interface{}) (net.PacketConn, error)

var CreateProxyHttpClient func(specifiedInstance interface{}) *http.Client

// no proxy

var NetDialer = &net.Dialer{}

func DialContextSystem(ctx context.Context, network, addr string) (net.Conn, error) {
	return NetDialer.DialContext(ctx, network, addr)
}

func DialUDPSystem(ctx context.Context) (net.PacketConn, error) {
	return net.ListenUDP("udp", &net.UDPAddr{})
}
