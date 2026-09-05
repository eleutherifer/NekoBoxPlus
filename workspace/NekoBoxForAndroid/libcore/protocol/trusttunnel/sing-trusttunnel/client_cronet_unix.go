//go:build with_trusttunnel_cronet && unix

package trusttunnel

import (
	"net"
	"os"
	"syscall"

	E "github.com/sagernet/sing/common/exceptions"
)

func createSocketPair() (int, net.Conn, error) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_STREAM, 0)
	if err != nil {
		return -1, nil, E.Cause(err, "create socketpair")
	}
	syscall.CloseOnExec(fds[0])
	file := os.NewFile(uintptr(fds[1]), "trusttunnel-cronet-socketpair")
	conn, err := net.FileConn(file)
	_ = file.Close()
	if err != nil {
		syscall.Close(fds[0])
		return -1, nil, E.Cause(err, "create net conn from socketpair")
	}
	return fds[0], conn, nil
}

func createPacketSocketPair(forceUDPLoopback bool) (int, net.PacketConn, error) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_DGRAM, 0)
	if err != nil {
		return -1, nil, E.Cause(err, "create dgram socketpair")
	}
	syscall.CloseOnExec(fds[0])
	file := os.NewFile(uintptr(fds[1]), "trusttunnel-cronet-dgram-socketpair")
	conn, err := net.FilePacketConn(file)
	_ = file.Close()
	if err != nil {
		syscall.Close(fds[0])
		return -1, nil, E.Cause(err, "create packet conn from socketpair")
	}
	return fds[0], conn, nil
}
