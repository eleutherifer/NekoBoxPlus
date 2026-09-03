package vless

import (
	"net"

	"github.com/sagernet/sing-vmess"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
)

func (c *Client) prepareEnhancedConn(conn net.Conn, baseConn net.Conn, canSplice bool) (net.Conn, error) {
	if c.flow == FlowVision {
		protocolConn, err := newEnhancedVisionConn(conn, baseConn, c.key, c.logger, canSplice)
		if err != nil {
			return nil, E.Cause(err, "initialize vision")
		}
		conn = protocolConn
	}
	return conn, nil
}

func (c *Client) DialEarlyConnWithBase(conn net.Conn, baseConn net.Conn, destination M.Socksaddr) (net.Conn, error) {
	return c.DialEarlyConnWithOptions(conn, baseConn, destination, true)
}

func (c *Client) DialEarlyConnWithOptions(conn net.Conn, baseConn net.Conn, destination M.Socksaddr, canSplice bool) (net.Conn, error) {
	return c.prepareEnhancedConn(NewConn(conn, c.key, vmess.CommandTCP, destination, c.flow), baseConn, canSplice)
}

func (c *Client) DialEarlyXUDPPacketConnWithOptions(conn net.Conn, baseConn net.Conn, destination M.Socksaddr, canSplice bool) (vmess.PacketConn, error) {
	remoteConn := NewConn(conn, c.key, vmess.CommandMux, destination, c.flow)
	protocolConn, err := c.prepareEnhancedConn(remoteConn, baseConn, canSplice)
	if err != nil {
		return nil, err
	}
	return vmess.NewXUDPConn(protocolConn, destination), common.Error(remoteConn.Write(nil))
}
