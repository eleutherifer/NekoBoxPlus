package vless

import (
	"bytes"
	"context"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-vmess"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	"github.com/gofrs/uuid/v5"
)

func TestServiceUpdateUsersClosesOnlyRemovedUsers(t *testing.T) {
	service := NewService[string](nil, nil)
	service.UpdateUsers(
		[]string{"retained", "removed"},
		[]string{"64c8f005-09d8-4de1-b4ea-f3af2f8c0ca1", "64c8f005-09d8-4de1-b4ea-f3af2f8c0ca2"},
		[]string{"", ""},
	)
	service.userAccess.RLock()
	revision := service.userRevision
	service.userAccess.RUnlock()
	retainedConn := &vlessTestConn{}
	removedConn := &vlessTestConn{}
	if _, loaded := service.trackConnection("retained", revision, retainedConn); !loaded {
		t.Fatal("track retained connection")
	}
	if _, loaded := service.trackConnection("removed", revision, removedConn); !loaded {
		t.Fatal("track removed connection")
	}

	service.UpdateUsers(
		[]string{"retained"},
		[]string{"64c8f005-09d8-4de1-b4ea-f3af2f8c0ca1"},
		[]string{""},
	)
	if got := retainedConn.closeCount.Load(); got != 0 {
		t.Fatalf("retained connection closed %d times", got)
	}
	if got := removedConn.closeCount.Load(); got != 1 {
		t.Fatalf("removed connection closed %d times, want 1", got)
	}
}

func TestServiceEntryPointsTrackAcceptedConnections(t *testing.T) {
	for _, test := range []struct {
		name   string
		accept func(*Service[string], net.Conn) error
	}{
		{
			name: "standard",
			accept: func(service *Service[string], conn net.Conn) error {
				return service.NewConnection(context.Background(), conn, M.Socksaddr{}, nil)
			},
		},
		{
			name: "enhanced",
			accept: func(service *Service[string], conn net.Conn) error {
				return service.NewConnectionWithOptions(context.Background(), conn, M.Socksaddr{}, nil, false)
			},
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			const userID = "64c8f005-09d8-4de1-b4ea-f3af2f8c0ca1"
			handler := &vlessTestHandler{}
			service := NewService[string](nil, handler)
			service.UpdateUsers([]string{"user"}, []string{userID}, []string{""})
			requestConn := newVlessRequestConn(t, userID, vmess.CommandTCP)

			if err := test.accept(service, requestConn); err != nil {
				t.Fatal(err)
			}
			if handler.tcpConn == nil || handler.onClose == nil {
				t.Fatal("connection was not handed to handler")
			}
			service.UpdateUsers(nil, nil, nil)
			if got := requestConn.closeCount.Load(); got != 1 {
				t.Fatalf("removed connection closed %d times, want 1", got)
			}
			handler.onClose(nil)
			service.userAccess.RLock()
			connectionCount := len(service.connections)
			service.userAccess.RUnlock()
			if connectionCount != 0 {
				t.Fatalf("%d tracked user entries remain", connectionCount)
			}
		})
	}
}

func TestServiceRejectsConnectionFromStaleUserSnapshot(t *testing.T) {
	service := NewService[string](nil, nil)
	const userID = "64c8f005-09d8-4de1-b4ea-f3af2f8c0ca1"
	service.UpdateUsers([]string{"user"}, []string{userID}, []string{""})
	id := uuid.Must(uuid.FromString(userID))
	_, _, revision, loaded := service.lookupUser(id)
	if !loaded {
		t.Fatal("lookup user")
	}

	service.UpdateUsers([]string{"user"}, []string{userID}, []string{""})
	if trackedConn, loaded := service.trackConnection("user", revision, &vlessTestConn{}); loaded || trackedConn != nil {
		t.Fatal("stale user snapshot registered a connection")
	}
}

func TestServiceMuxReturnRemovesTrackedConnection(t *testing.T) {
	for _, test := range []struct {
		name   string
		accept func(*Service[string], net.Conn) error
	}{
		{
			name: "standard",
			accept: func(service *Service[string], conn net.Conn) error {
				return service.NewConnection(context.Background(), conn, M.Socksaddr{}, nil)
			},
		},
		{
			name: "enhanced",
			accept: func(service *Service[string], conn net.Conn) error {
				return service.NewConnectionWithOptions(context.Background(), conn, M.Socksaddr{}, nil, false)
			},
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			const userID = "64c8f005-09d8-4de1-b4ea-f3af2f8c0ca1"
			service := NewService[string](nil, &vlessTestHandler{})
			service.UpdateUsers([]string{"user"}, []string{userID}, []string{""})

			err := test.accept(service, newVlessRequestConn(t, userID, vmess.CommandMux))
			if err == nil {
				t.Fatal("expected mux EOF")
			}
			service.userAccess.RLock()
			connectionCount := len(service.connections)
			service.userAccess.RUnlock()
			if connectionCount != 0 {
				t.Fatalf("%d tracked user entries remain", connectionCount)
			}
		})
	}
}

func TestServiceConcurrentUserUpdatesAndConnectionTracking(t *testing.T) {
	service := NewService[string](nil, nil)
	const userID = "64c8f005-09d8-4de1-b4ea-f3af2f8c0ca1"
	service.UpdateUsers([]string{"user"}, []string{userID}, []string{""})
	id := uuid.Must(uuid.FromString(userID))

	var workers sync.WaitGroup
	workers.Add(2)
	go func() {
		defer workers.Done()
		for i := 0; i < 100; i++ {
			if i%2 == 0 {
				service.UpdateUsers([]string{"user"}, []string{userID}, []string{""})
			} else {
				service.UpdateUsers(nil, nil, nil)
			}
		}
	}()
	go func() {
		defer workers.Done()
		for i := 0; i < 100; i++ {
			user, _, revision, loaded := service.lookupUser(id)
			if !loaded {
				continue
			}
			if connection, tracked := service.trackConnection(user, revision, &vlessTestConn{}); tracked {
				_ = connection.Close()
			}
		}
	}()
	workers.Wait()
	service.UpdateUsers(nil, nil, nil)
}

func newVlessRequestConn(t *testing.T, userID string, command byte) *vlessTestConn {
	t.Helper()
	id := uuid.Must(uuid.FromString(userID))
	var request bytes.Buffer
	err := WriteRequest(&request, Request{
		UUID:        id,
		Command:     command,
		Destination: M.ParseSocksaddr("example.com:443"),
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	return &vlessTestConn{reader: bytes.NewReader(request.Bytes())}
}

type vlessTestHandler struct {
	tcpConn net.Conn
	onClose N.CloseHandlerFunc
}

func (h *vlessTestHandler) NewConnectionEx(_ context.Context, conn net.Conn, _, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	h.tcpConn = conn
	h.onClose = onClose
}

func (h *vlessTestHandler) NewPacketConnectionEx(_ context.Context, _ N.PacketConn, _, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	h.onClose = onClose
}

type vlessTestConn struct {
	access     sync.Mutex
	reader     *bytes.Reader
	writes     bytes.Buffer
	closeCount atomic.Int32
}

func (c *vlessTestConn) Read(p []byte) (int, error) {
	c.access.Lock()
	defer c.access.Unlock()
	if c.reader == nil {
		return 0, io.EOF
	}
	return c.reader.Read(p)
}

func (c *vlessTestConn) Write(p []byte) (int, error) {
	c.access.Lock()
	defer c.access.Unlock()
	return c.writes.Write(p)
}

func (c *vlessTestConn) Close() error                     { c.closeCount.Add(1); return nil }
func (c *vlessTestConn) LocalAddr() net.Addr              { return nil }
func (c *vlessTestConn) RemoteAddr() net.Addr             { return nil }
func (c *vlessTestConn) SetDeadline(time.Time) error      { return nil }
func (c *vlessTestConn) SetReadDeadline(time.Time) error  { return nil }
func (c *vlessTestConn) SetWriteDeadline(time.Time) error { return nil }
