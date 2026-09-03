//go:build with_adblock && with_adblock_cronet && unix

package httpconn

import (
	"net"
	"os"
	"syscall"

	E "github.com/sagernet/sing/common/exceptions"
)

func createCronetSocketPair() (int, net.Conn, error) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_STREAM, 0)
	if err != nil {
		return -1, nil, E.Cause(err, "create socketpair")
	}
	syscall.CloseOnExec(fds[0])
	file := os.NewFile(uintptr(fds[1]), "adblock-cronet-socketpair")
	conn, err := net.FileConn(file)
	_ = file.Close()
	if err != nil {
		_ = syscall.Close(fds[0])
		return -1, nil, E.Cause(err, "create net conn from socketpair")
	}
	return fds[0], conn, nil
}

func createCronetTCPBridge() (int, net.Conn, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return -1, nil, E.Cause(err, "listen tcp bridge")
	}
	defer listener.Close()

	acceptChan := make(chan struct {
		conn net.Conn
		err  error
	}, 1)
	go func() {
		conn, acceptErr := listener.Accept()
		acceptChan <- struct {
			conn net.Conn
			err  error
		}{conn: conn, err: acceptErr}
	}()

	clientConn, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		return -1, nil, E.Cause(err, "dial tcp bridge")
	}
	accepted := <-acceptChan
	if accepted.err != nil {
		_ = clientConn.Close()
		return -1, nil, E.Cause(accepted.err, "accept tcp bridge")
	}
	syscallConn, ok := clientConn.(syscall.Conn)
	if !ok {
		_ = clientConn.Close()
		_ = accepted.conn.Close()
		return -1, nil, E.New("tcp bridge client connection does not expose syscall conn")
	}
	fd, err := duplicateSocketFD(syscallConn)
	_ = clientConn.Close()
	if err != nil {
		_ = accepted.conn.Close()
		return -1, nil, err
	}
	return fd, accepted.conn, nil
}

func createCronetPacketSocketPair() (int, net.PacketConn, error) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_DGRAM, 0)
	if err != nil {
		return -1, nil, E.Cause(err, "create dgram socketpair")
	}
	syscall.CloseOnExec(fds[0])
	file := os.NewFile(uintptr(fds[1]), "adblock-cronet-dgram-socketpair")
	conn, err := net.FilePacketConn(file)
	_ = file.Close()
	if err != nil {
		_ = syscall.Close(fds[0])
		return -1, nil, E.Cause(err, "create packet conn from socketpair")
	}
	return fds[0], conn, nil
}
