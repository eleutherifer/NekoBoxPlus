//go:build linux

package process

import (
	"net"
	"net/netip"
	"os"
	"syscall"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestQuerySocketDiagUDPExact(t *testing.T) {
	t.Parallel()
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	require.NoError(t, err)
	defer server.Close()

	client, err := net.DialUDP("udp4", nil, server.LocalAddr().(*net.UDPAddr))
	require.NoError(t, err)
	defer client.Close()

	err = client.SetDeadline(time.Now().Add(time.Second))
	require.NoError(t, err)
	_, err = client.Write([]byte{0})
	require.NoError(t, err)

	err = server.SetReadDeadline(time.Now().Add(time.Second))
	require.NoError(t, err)
	buffer := make([]byte, 1)
	_, _, err = server.ReadFromUDP(buffer)
	require.NoError(t, err)

	source := addrPortFromUDPAddr(t, client.LocalAddr())
	destination := addrPortFromUDPAddr(t, client.RemoteAddr())

	fd, err := openSocketDiag()
	require.NoError(t, err)
	defer syscall.Close(fd)

	inode, uid, err := querySocketDiag(fd, packSocketDiagRequest(syscall.AF_INET, syscall.IPPROTO_UDP, source, destination, false))
	require.NoError(t, err)
	require.NotZero(t, inode)
	require.EqualValues(t, os.Getuid(), uid)
}

func addrPortFromUDPAddr(t *testing.T, addr net.Addr) netip.AddrPort {
	t.Helper()

	udpAddr, ok := addr.(*net.UDPAddr)
	require.True(t, ok)

	ip, ok := netip.AddrFromSlice(udpAddr.IP)
	require.True(t, ok)

	return netip.AddrPortFrom(ip.Unmap(), uint16(udpAddr.Port))
}
