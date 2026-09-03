package trusttunnel

import (
	std_bufio "bufio"
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/stretchr/testify/require"
)

func TestServiceAuthFailureDefaultsTo407(t *testing.T) {
	t.Parallel()

	service := NewService(ServiceOptions{Ctx: context.Background(), Logger: logger.NOP()})
	request := httptest.NewRequest(http.MethodConnect, "https://example.com", nil)
	request.Host = "example.com:443"
	recorder := httptest.NewRecorder()

	service.ServeHTTP(recorder, request)

	require.Equal(t, http.StatusProxyAuthRequired, recorder.Code)
	require.Equal(t, "Basic realm=Authorization Required", recorder.Header().Get("Proxy-Authenticate"))
}

func TestServiceAuthFailureUsesConfiguredStatus(t *testing.T) {
	t.Parallel()

	service := NewService(ServiceOptions{
		Ctx:                   context.Background(),
		Logger:                logger.NOP(),
		AuthFailureStatusCode: http.StatusForbidden,
	})
	request := httptest.NewRequest(http.MethodConnect, "https://example.com", nil)
	request.Host = "example.com:443"
	recorder := httptest.NewRecorder()

	service.ServeHTTP(recorder, request)

	require.Equal(t, http.StatusForbidden, recorder.Code)
	require.Empty(t, recorder.Header().Get("Proxy-Authenticate"))
}

func TestServiceAuthFailureUsesNonConnectOverride(t *testing.T) {
	t.Parallel()

	service := NewService(ServiceOptions{
		Ctx:                             context.Background(),
		Logger:                          logger.NOP(),
		AuthFailureStatusCode:           http.StatusProxyAuthRequired,
		NonConnectAuthFailureStatusCode: http.StatusNotFound,
	})
	request := httptest.NewRequest(http.MethodGet, "https://example.com/path", nil)
	request.Host = "example.com"
	recorder := httptest.NewRecorder()

	service.ServeHTTP(recorder, request)

	require.Equal(t, http.StatusNotFound, recorder.Code)
	require.Empty(t, recorder.Header().Get("Proxy-Authenticate"))
}

type forwardingTestHandler struct {
	t *testing.T
}

func (h forwardingTestHandler) NewConnectionEx(ctx context.Context, conn net.Conn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	go func() {
		defer onClose(nil)
		defer conn.Close()
		request, err := http.ReadRequest(std_bufio.NewReader(conn))
		require.NoError(h.t, err)
		require.Equal(h.t, "GET", request.Method)
		require.Equal(h.t, "/path?q=1", request.URL.RequestURI())
		require.Equal(h.t, "example.com", request.Host)
		require.Empty(h.t, request.Header.Get("Proxy-Authorization"))
		require.Empty(h.t, request.Header.Get("Proxy-Connection"))
		_, err = io.WriteString(conn, "HTTP/1.1 201 Created\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok")
		require.NoError(h.t, err)
	}()
}

func (h forwardingTestHandler) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	onClose(nil)
}

func TestServiceForwardsAuthenticatedNonConnectRequest(t *testing.T) {
	t.Parallel()

	service := NewService(ServiceOptions{
		Ctx:     context.Background(),
		Logger:  logger.NOP(),
		Handler: forwardingTestHandler{t: t},
	})
	service.UpdateUsers([]auth.User{{Username: "user", Password: "pass"}})

	request := httptest.NewRequest(http.MethodGet, "http://example.com/path?q=1", nil)
	request.Host = "example.com"
	request.Header.Set("Proxy-Authorization", buildAuth(auth.User{Username: "user", Password: "pass"}))
	request.Header.Set("Proxy-Connection", "keep-alive")
	recorder := httptest.NewRecorder()

	service.ServeHTTP(recorder, request)

	require.Equal(t, http.StatusCreated, recorder.Code)
	require.Equal(t, "text/plain", recorder.Header().Get("Content-Type"))
	require.Equal(t, "ok", recorder.Body.String())
}
