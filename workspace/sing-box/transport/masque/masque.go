// Package masque implements Cloudflare WARP CONNECT-IP over HTTP/3 and HTTP/2.
package masque

import (
	"context"
	"fmt"
	"net/url"
	"strings"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing-box/transport/masque/connectip"
	E "github.com/sagernet/sing/common/exceptions"
)

// ConnectResponseError reports a non-success CONNECT response without hiding
// the status class from automatic transport fallback.
type ConnectResponseError struct {
	StatusCode int
}

func (e *ConnectResponseError) Error() string {
	return fmt.Sprintf("connect-ip: server responded with %d", e.StatusCode)
}

// Profile contains the small set of wire quirks selected by a MASQUE server.
type Profile struct {
	RequestProtocol       string
	H2ConnectProto        string
	IgnoreExtendedConnect bool
}

// CloudflareProfile describes Cloudflare WARP's CONNECT-IP behavior.
var CloudflareProfile = Profile{
	RequestProtocol:       "cf-connect-ip",
	H2ConnectProto:        "cf-connect-ip",
	IgnoreExtendedConnect: true,
}

// IpConn is the transport-independent packet connection used by the tunnel pumps.
type IpConn interface {
	ReadPacket() ([]byte, error)
	WritePacket([]byte) ([]byte, error)
	Close() error
}

// ConnectTunnelH3 establishes CONNECT-IP over an existing QUIC connection.
func ConnectTunnelH3(ctx context.Context, profile Profile, quicConn *quic.Conn, connectURI string) (*http3.Transport, IpConn, error) {
	tr := &http3.Transport{
		EnableDatagrams: true,
		AdditionalSettings: map[uint64]uint64{
			// WARP still expects the legacy SETTINGS_H3_DATAGRAM_00 value.
			0x276: 1,
		},
		DisableCompression: true,
	}
	hconn := tr.NewClientConn(quicConn)
	ipConn, err := dialCONNECTIP(ctx, profile, hconn, connectURI)
	if err != nil {
		_ = tr.Close()
		if strings.Contains(err.Error(), "tls: access denied") {
			return nil, nil, E.New("masque: login failed; verify the TLS key and certificate enrollment")
		}
		return nil, nil, E.Cause(err, "masque: dial connect-ip")
	}
	if err = advertiseDefaultRoute(ctx, ipConn); err != nil {
		_ = ipConn.Close()
		_ = tr.Close()
		return nil, nil, err
	}
	return tr, ipConn, nil
}

func dialCONNECTIP(ctx context.Context, profile Profile, conn *http3.ClientConn, connectURI string) (*connectip.Conn, error) {
	u, err := url.Parse(connectURI)
	if err != nil {
		return nil, E.Cause(err, "parse connect uri")
	}
	select {
	case <-ctx.Done():
		return nil, context.Cause(ctx)
	case <-conn.Context().Done():
		return nil, context.Cause(conn.Context())
	case <-conn.ReceivedSettings():
	}
	settings := conn.Settings()
	if !profile.IgnoreExtendedConnect && !settings.EnableExtendedConnect {
		return nil, E.New("connect-ip: server didn't enable Extended CONNECT")
	}
	if !settings.EnableDatagrams {
		return nil, E.New("connect-ip: server didn't enable datagrams")
	}
	rstr, err := conn.OpenRequestStream(ctx)
	if err != nil {
		return nil, E.Cause(err, "open request stream")
	}
	if err = rstr.SendRequestHeader(buildConnectIPRequest(profile, u)); err != nil {
		return nil, E.Cause(err, "send request header")
	}
	rsp, err := rstr.ReadResponse()
	if err != nil {
		return nil, E.Cause(err, "read response")
	}
	if rsp.StatusCode < 200 || rsp.StatusCode > 299 {
		return nil, &ConnectResponseError{StatusCode: rsp.StatusCode}
	}
	return connectip.NewProxiedConn(rstr), nil
}
