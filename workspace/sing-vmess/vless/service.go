package vless

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"sync"

	"github.com/sagernet/sing-vmess"
	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	"github.com/gofrs/uuid/v5"
)

type Service[T comparable] struct {
	userMap  map[[16]byte]T
	userFlow map[T]string
	logger   logger.Logger
	handler  Handler

	connections  map[T]map[*trackedConnection[T]]struct{}
	userRevision uint64
	userAccess   sync.RWMutex
}

type trackedConnection[T comparable] struct {
	net.Conn
	service *Service[T]
	user    T
	once    sync.Once
}

type Handler interface {
	N.TCPConnectionHandlerEx
	N.UDPConnectionHandlerEx
}

func NewService[T comparable](logger logger.Logger, handler Handler) *Service[T] {
	return &Service[T]{
		logger:      logger,
		handler:     handler,
		connections: make(map[T]map[*trackedConnection[T]]struct{}),
	}
}

func (s *Service[T]) UpdateUsers(userList []T, userUUIDList []string, userFlowList []string) {
	userMap := make(map[[16]byte]T)
	userFlowMap := make(map[T]string)
	for i, userName := range userList {
		userID, err := uuid.FromString(userUUIDList[i])
		if err != nil {
			userID = uuid.NewV5(uuid.Nil, userUUIDList[i])
		}
		userMap[userID] = userName
		userFlowMap[userName] = userFlowList[i]
	}
	s.userAccess.Lock()
	s.userMap = userMap
	s.userFlow = userFlowMap
	s.userRevision++
	var removedConnections []*trackedConnection[T]
	for user, connections := range s.connections {
		if _, loaded := userFlowMap[user]; !loaded {
			for connection := range connections {
				removedConnections = append(removedConnections, connection)
			}
			delete(s.connections, user)
		}
	}
	s.userAccess.Unlock()
	for _, connection := range removedConnections {
		_ = connection.Close()
	}
}

func (s *Service[T]) NewConnection(ctx context.Context, conn net.Conn, source M.Socksaddr, onClose N.CloseHandlerFunc) error {
	request, err := ReadRequest(conn)
	if err != nil {
		return err
	}
	user, userFlow, userRevision, loaded := s.lookupUser(request.UUID)
	if !loaded {
		return E.New("unknown UUID: ", uuid.FromBytesOrNil(request.UUID[:]))
	}
	ctx = auth.ContextWithUser(ctx, user)
	if request.Flow == FlowVision && request.Command == vmess.NetworkUDP {
		return E.New(FlowVision, " flow does not support UDP")
	} else if request.Flow != userFlow {
		return E.New("flow mismatch: expected ", flowName(userFlow), ", but got ", flowName(request.Flow))
	}

	trackedConn, loaded := s.trackConnection(user, userRevision, conn)
	if !loaded {
		return E.New("user configuration changed during handshake")
	}
	handedOff := false
	defer func() {
		if !handedOff {
			trackedConn.remove()
		}
	}()
	conn = trackedConn

	if request.Command == vmess.CommandUDP {
		s.handler.NewPacketConnectionEx(ctx, &serverPacketConn{ExtendedConn: bufio.NewExtendedConn(conn), destination: request.Destination}, source, request.Destination, trackedConn.onClose(onClose))
		handedOff = true
		return nil
	}
	responseConn := &serverConn{ExtendedConn: bufio.NewExtendedConn(conn)}
	switch userFlow {
	case FlowVision:
		conn, err = NewVisionConn(responseConn, conn, request.UUID, s.logger)
		if err != nil {
			return E.Cause(err, "initialize vision")
		}
	case "":
		conn = responseConn
	default:
		return E.New("unknown flow: ", userFlow)
	}
	switch request.Command {
	case vmess.CommandTCP:
		s.handler.NewConnectionEx(ctx, conn, source, request.Destination, trackedConn.onClose(onClose))
		handedOff = true
		return nil
	case vmess.CommandMux:
		return vmess.HandleMuxConnection(ctx, conn, source, s.handler)
	default:
		return E.New("unknown command: ", request.Command)
	}
}

func (s *Service[T]) lookupUser(userID [16]byte) (T, string, uint64, bool) {
	s.userAccess.RLock()
	defer s.userAccess.RUnlock()
	user, loaded := s.userMap[userID]
	if !loaded {
		var zero T
		return zero, "", 0, false
	}
	return user, s.userFlow[user], s.userRevision, true
}

func (s *Service[T]) trackConnection(user T, revision uint64, conn net.Conn) (*trackedConnection[T], bool) {
	s.userAccess.Lock()
	defer s.userAccess.Unlock()
	if revision != s.userRevision {
		return nil, false
	}
	trackedConn := &trackedConnection[T]{
		Conn:    conn,
		service: s,
		user:    user,
	}
	connections := s.connections[user]
	if connections == nil {
		connections = make(map[*trackedConnection[T]]struct{})
		s.connections[user] = connections
	}
	connections[trackedConn] = struct{}{}
	return trackedConn, true
}

func (c *trackedConnection[T]) remove() {
	c.once.Do(func() {
		c.service.userAccess.Lock()
		connections := c.service.connections[c.user]
		delete(connections, c)
		if len(connections) == 0 {
			delete(c.service.connections, c.user)
		}
		c.service.userAccess.Unlock()
	})
}

func (c *trackedConnection[T]) onClose(parent N.CloseHandlerFunc) N.CloseHandlerFunc {
	return N.OnceClose(func(err error) {
		c.remove()
		if parent != nil {
			parent(err)
		}
	})
}

func (c *trackedConnection[T]) Close() error {
	c.remove()
	return c.Conn.Close()
}

func flowName(value string) string {
	if value == "" {
		return "none"
	}
	return value
}

type serverConn struct {
	N.ExtendedConn
	responseWritten bool
}

func (c *serverConn) Read(b []byte) (n int, err error) {
	return c.ExtendedConn.Read(b)
}

func (c *serverConn) Write(b []byte) (n int, err error) {
	if !c.responseWritten {
		buffer := buf.NewSize(2 + len(b))
		buffer.WriteByte(Version)
		buffer.WriteByte(0)
		buffer.Write(b)
		_, err = c.ExtendedConn.Write(buffer.Bytes())
		buffer.Release()
		if err == nil {
			n = len(b)
		}
		c.responseWritten = true
		return
	}
	return c.ExtendedConn.Write(b)
}

func (c *serverConn) WriteBuffer(buffer *buf.Buffer) error {
	if !c.responseWritten {
		header := buffer.ExtendHeader(2)
		header[0] = Version
		header[1] = 0
		c.responseWritten = true
	}
	return c.ExtendedConn.WriteBuffer(buffer)
}

func (c *serverConn) FrontHeadroom() int {
	if c.responseWritten {
		return 0
	}
	return 2
}

func (c *serverConn) ReaderReplaceable() bool {
	return true
}

func (c *serverConn) WriterReplaceable() bool {
	return c.responseWritten
}

func (c *serverConn) NeedAdditionalReadDeadline() bool {
	return true
}

func (c *serverConn) Upstream() any {
	return c.ExtendedConn
}

type serverPacketConn struct {
	N.ExtendedConn
	responseWritten bool
	destination     M.Socksaddr
}

func (c *serverPacketConn) ReadFrom(p []byte) (n int, addr net.Addr, err error) {
	var packetLen uint16
	err = binary.Read(c.ExtendedConn, binary.BigEndian, &packetLen)
	if err != nil {
		return
	}
	if len(p) < int(packetLen) {
		err = io.ErrShortBuffer
		return
	}
	n, err = io.ReadFull(c.ExtendedConn, p[:packetLen])
	if err != nil {
		return
	}
	if c.destination.IsFqdn() {
		addr = c.destination
	} else {
		addr = c.destination.UDPAddr()
	}
	return
}

func (c *serverPacketConn) WriteTo(p []byte, addr net.Addr) (n int, err error) {
	if !c.responseWritten {
		_, err = c.ExtendedConn.Write([]byte{Version, 0})
		if err != nil {
			return
		}
		c.responseWritten = true
	}
	err = binary.Write(c.ExtendedConn, binary.BigEndian, uint16(len(p)))
	if err != nil {
		return
	}
	return c.ExtendedConn.Write(p)
}

func (c *serverPacketConn) ReadPacket(buffer *buf.Buffer) (destination M.Socksaddr, err error) {
	var packetLen uint16
	err = binary.Read(c.ExtendedConn, binary.BigEndian, &packetLen)
	if err != nil {
		return
	}

	_, err = buffer.ReadFullFrom(c.ExtendedConn, int(packetLen))
	if err != nil {
		return
	}

	destination = c.destination
	return
}

func (c *serverPacketConn) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	if !c.responseWritten {
		_, err := c.ExtendedConn.Write([]byte{Version, 0})
		if err != nil {
			return err
		}
		c.responseWritten = true
	}
	packetLen := buffer.Len()
	binary.BigEndian.PutUint16(buffer.ExtendHeader(2), uint16(packetLen))
	return c.ExtendedConn.WriteBuffer(buffer)
}

func (c *serverPacketConn) FrontHeadroom() int {
	return 2
}

func (c *serverPacketConn) NeedAdditionalReadDeadline() bool {
	return true
}

func (c *serverPacketConn) Upstream() any {
	return c.ExtendedConn
}
