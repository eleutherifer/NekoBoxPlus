package trusttunnel

import (
	"net"
	"net/http"
	"reflect"
	"sync"
	"unsafe"

	"github.com/sagernet/sing/common"

	"golang.org/x/net/http2"
)

type connectionResetter interface {
	ResetConnections()
}

type closingConnectionResetter interface {
	connectionResetter
	Close() error
}

func forceCloseAllConnections(roundTripper RoundTripper) {
	if roundTripper == nil {
		return
	}
	if resetter, isResetter := roundTripper.(closingConnectionResetter); isResetter {
		_ = resetter.Close()
		return
	}
	resetRoundTripperConnections(roundTripper)
	_ = common.Close(roundTripper) // Can close http3 connections.
}

func resetRoundTripperConnections(roundTripper RoundTripper) {
	if roundTripper == nil {
		return
	}
	if resetter, isResetter := roundTripper.(connectionResetter); isResetter {
		resetter.ResetConnections()
		return
	}
	roundTripper.CloseIdleConnections()
}

func forceCloseAllH2ServerConnections(server *http2.Server) {
	if server == nil {
		return
	}
	state := h2ServerState(server)
	if state == nil {
		return
	}
	state.mu.Lock()
	serverConns := make([]*h2ServerConn, 0, len(state.activeConns))
	for serverConn := range state.activeConns {
		serverConns = append(serverConns, serverConn)
	}
	state.mu.Unlock()
	for _, serverConn := range serverConns {
		if serverConn != nil && serverConn.conn != nil {
			_ = serverConn.conn.Close()
		}
	}
}

type h2ServerInternalState struct {
	mu          sync.Mutex
	activeConns map[*h2ServerConn]struct{}
}

type h2ServerConn struct {
	srv  *http2.Server
	hs   *http.Server
	conn net.Conn
}

func h2ServerState(server *http2.Server) *h2ServerInternalState {
	stateField, loaded := reflect.TypeFor[http2.Server]().FieldByName("state")
	if !loaded || stateField.Type.Kind() != reflect.Pointer {
		return nil
	}
	return *(**h2ServerInternalState)(unsafe.Add(unsafe.Pointer(server), stateField.Offset))
}
