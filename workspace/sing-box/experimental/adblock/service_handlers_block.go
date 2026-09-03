//go:build with_adblock

package adblock

import (
	"io"
	"net"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	N "github.com/sagernet/sing/common/network"
)

func (s *Service) blockTCP(conn net.Conn, metadata adapter.InboundContext) error {
	if s.options.Filtering.Mode == option.AdblockModeEmptyResponse {
		return s.blockHTTP(conn, metadata)
	}
	s.debug("TCP block: ", metadata.Destination, ", domain=", metadata.Domain)
	return s.rejectTCP(conn, metadata)
}

func (s *Service) blockUDP(conn N.PacketConn, metadata adapter.InboundContext) error {
	s.debug("UDP block: ", metadata.Destination, ", domain=", metadata.Domain)
	_ = conn.Close()
	return &blockedError{metadata: metadata}
}

func (s *Service) rejectTCP(conn net.Conn, metadata adapter.InboundContext) error {
	s.debug("TCP reject: ", metadata.Destination, ", domain=", metadata.Domain)
	_ = conn.Close()
	return &blockedError{metadata: metadata}
}

func (s *Service) blockHTTP(conn net.Conn, metadata adapter.InboundContext) error {
	s.debug("HTTP block response: ", metadata.Destination, ", domain=", metadata.Domain)
	_, _ = io.WriteString(conn, "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\nX-Adblocked: 1\r\n\r\n")
	return &blockedError{metadata: metadata}
}
