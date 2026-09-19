package libcore

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"net/http"
	"slices"
	"strconv"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestHTTPClientTrySocks5UsesConfiguredListener(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	defer listener.Close()

	host, portString, err := net.SplitHostPort(listener.Addr().String())
	require.NoError(t, err)
	port, err := strconv.Atoi(portString)
	require.NoError(t, err)

	serverResult := make(chan error, 1)
	go func() {
		serverResult <- serveSingleSOCKS5HTTPResponse(listener)
	}()

	client := NewHttpClient()
	client.SetTimeoutMillis(2_000)
	client.TrySocks5(host, int32(port), "", "")
	defer client.Close()

	request := client.NewRequest()
	require.NoError(t, request.SetURL("http://192.0.2.1/ip"))
	response, err := request.Execute()
	require.NoError(t, err)
	content, err := response.GetContentString()
	require.NoError(t, err)
	require.Equal(t, "ok", content.Value)
	require.NoError(t, <-serverResult)
}

func serveSingleSOCKS5HTTPResponse(listener net.Listener) error {
	conn, err := listener.Accept()
	if err != nil {
		return err
	}
	defer conn.Close()
	if err = conn.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		return err
	}

	var greeting [2]byte
	if _, err = io.ReadFull(conn, greeting[:]); err != nil {
		return err
	}
	if greeting[0] != 5 {
		return fmt.Errorf("unexpected SOCKS version: %d", greeting[0])
	}
	methods := make([]byte, greeting[1])
	if _, err = io.ReadFull(conn, methods); err != nil {
		return err
	}
	if !slices.Contains(methods, byte(0)) {
		return fmt.Errorf("SOCKS client did not offer no-auth method: %v", methods)
	}
	if _, err = conn.Write([]byte{5, 0}); err != nil {
		return err
	}

	var requestHeader [4]byte
	if _, err = io.ReadFull(conn, requestHeader[:]); err != nil {
		return err
	}
	if requestHeader[0] != 5 || requestHeader[1] != 1 {
		return fmt.Errorf("unexpected SOCKS request header: %v", requestHeader)
	}
	addressLength := 0
	switch requestHeader[3] {
	case 1:
		addressLength = net.IPv4len
	case 3:
		var length [1]byte
		if _, err = io.ReadFull(conn, length[:]); err != nil {
			return err
		}
		addressLength = int(length[0])
	case 4:
		addressLength = net.IPv6len
	default:
		return fmt.Errorf("unexpected SOCKS address type: %d", requestHeader[3])
	}
	if _, err = io.CopyN(io.Discard, conn, int64(addressLength+2)); err != nil {
		return err
	}
	if _, err = conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}); err != nil {
		return err
	}

	httpRequest, err := http.ReadRequest(bufio.NewReader(conn))
	if err != nil {
		return err
	}
	if err = httpRequest.Body.Close(); err != nil {
		return err
	}
	_, err = io.WriteString(conn, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
	return err
}
