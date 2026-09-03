package libcore

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"slices"
	"strconv"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestUTLSFingerprintValues(t *testing.T) {
	values := []string{
		"chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized",
		"golang", "custom", "randomizedalpn", "randomizednoalpn", "firefox_auto", "firefox_55", "firefox_56",
		"firefox_63", "firefox_65", "firefox_99", "firefox_102", "firefox_105", "firefox_120", "firefox_148",
		"chrome_auto", "chrome_58", "chrome_62", "chrome_70", "chrome_72", "chrome_83", "chrome_87", "chrome_96",
		"chrome_100", "chrome_102", "chrome_100_psk", "chrome_112_psk_shuf", "chrome_114_padding_psk_shuf",
		"chrome_115_pq", "chrome_115_pq_psk", "chrome_120", "chrome_120_pq", "chrome_131", "chrome_133",
		"chrome_141_ta", "chrome_144_ta_pqs", "ios_auto", "ios_12_1", "ios_13", "ios_14",
		"android_okhttp_auto", "android_11_okhttp", "android_16_okhttp", "edge_auto", "edge_85", "edge_106",
		"safari_auto", "safari_16_0", "safari_26_3", "360_auto", "360_7_5", "360_11_0", "qq_auto", "qq_11_1",
	}
	for _, value := range values {
		t.Run(value, func(t *testing.T) {
			_, err := utlsClientHelloID(value)
			require.NoError(t, err)
		})
	}
	_, err := utlsClientHelloID("unsupported")
	require.EqualError(t, err, "unknown uTLS fingerprint: unsupported")
}

func TestHTTPClientWithUTLSEmptyIsNoOp(t *testing.T) {
	client := NewHttpClient().(*httpClient)
	client.WithUTLS("")
	require.Empty(t, client.utlsName)
	require.Nil(t, client.utlsTransport)
	require.Same(t, &client.h1h2Transport, client.h1h2Client.Transport)
}

func TestHTTPClientWithUTLSHTTPProtocols(t *testing.T) {
	for _, testCase := range []struct {
		name        string
		enableHTTP2 bool
		expected    int
	}{
		{name: "http1", expected: 1},
		{name: "http2", enableHTTP2: true, expected: 2},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			server := httptest.NewUnstartedServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
				writer.Header().Set("X-Protocol", strconv.Itoa(request.ProtoMajor))
				_, _ = writer.Write([]byte("ok"))
			}))
			server.EnableHTTP2 = testCase.enableHTTP2
			server.StartTLS()
			defer server.Close()

			client := NewHttpClient()
			client.WithUTLS("chrome")
			client.SetTimeoutMillis(2_000)
			defer client.Close()

			request := client.NewRequest()
			require.NoError(t, request.SetURL(server.URL))
			request.AllowInsecure()
			response, err := request.Execute()
			require.NoError(t, err)
			require.Equal(t, strconv.Itoa(testCase.expected), response.GetHeader("X-Protocol").Value)
		})
	}
}

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
