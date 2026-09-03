package vless

import (
	"context"
	"net"

	"github.com/sagernet/sing-vmess"
	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	"github.com/gofrs/uuid/v5"
)

// NewConnectionWithOptions uses the enhanced Vision implementation. Callers
// should use NewConnection when encryption and transport-aware splice control
// are not required.
func (s *Service[T]) NewConnectionWithOptions(ctx context.Context, conn net.Conn, source M.Socksaddr, onClose N.CloseHandlerFunc, canSplice bool) error {
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
		conn, err = newEnhancedVisionConn(responseConn, conn, request.UUID, s.logger, canSplice)
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
