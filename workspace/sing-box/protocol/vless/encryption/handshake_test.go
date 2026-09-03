package encryption

import (
	"bytes"
	"crypto/mlkem"
	"io"
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/xray/cpuid"
)

func TestMLKEMHandshakeCompletesPartialWrites(t *testing.T) {
	decapsulationKey, err := mlkem.GenerateKey768()
	if err != nil {
		t.Fatal(err)
	}
	const padding = "100-35-35"
	var client ClientInstance
	if err := client.Init([][]byte{decapsulationKey.EncapsulationKey().Bytes()}, 0, 0, padding); err != nil {
		t.Fatal(err)
	}
	var server ServerInstance
	if err := server.Init([][]byte{decapsulationKey.Bytes()}, 0, 0, 0, padding); err != nil {
		t.Fatal(err)
	}
	defer server.Close()

	previousAESGCM := cpuid.HasAESGCM
	cpuid.HasAESGCM = false
	defer func() {
		cpuid.HasAESGCM = previousAESGCM
	}()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	serverConnection := make(chan net.Conn, 1)
	serverAcceptError := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			serverAcceptError <- err
			return
		}
		serverConnection <- conn
	}()
	clientSide, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	var serverSide net.Conn
	select {
	case serverSide = <-serverConnection:
	case err := <-serverAcceptError:
		t.Fatal(err)
	}
	defer clientSide.Close()
	defer serverSide.Close()
	deadline := time.Now().Add(5 * time.Second)
	if err := clientSide.SetDeadline(deadline); err != nil {
		t.Fatal(err)
	}
	if err := serverSide.SetDeadline(deadline); err != nil {
		t.Fatal(err)
	}

	type handshakeResult struct {
		conn *CommonConn
		err  error
	}
	serverResult := make(chan handshakeResult, 1)
	go func() {
		conn, err := server.Handshake(&partialWriteConn{Conn: serverSide, maxWrite: 23}, nil)
		serverResult <- handshakeResult{conn, err}
	}()
	clientConn, err := client.Handshake(&partialWriteConn{Conn: clientSide, maxWrite: 29})
	if err != nil {
		t.Fatal(err)
	}
	serverHandshake := <-serverResult
	if serverHandshake.err != nil {
		t.Fatal(serverHandshake.err)
	}

	payload := bytes.Repeat([]byte("post-handshake payload"), 500)
	writeResult := make(chan error, 1)
	go func() {
		_, err := clientConn.Write(payload)
		writeResult <- err
	}()
	received := make([]byte, len(payload))
	if _, err := io.ReadFull(serverHandshake.conn, received); err != nil {
		t.Fatal(err)
	}
	if err := <-writeResult; err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(received, payload) {
		t.Fatal("received payload differs from sent payload")
	}
}

type partialWriteConn struct {
	net.Conn
	maxWrite int
}

func (c *partialWriteConn) Write(data []byte) (int, error) {
	return c.Conn.Write(data[:min(len(data), c.maxWrite)])
}
