//go:build !android || !cgo

package byedpi

import (
	"fmt"
	"net"
)

type bridgeHandle struct{}

func acquireBridge(cli string) (*bridgeHandle, error) {
	return nil, fmt.Errorf("byedpi outbound is only available on android builds")
}

func releaseBridge(handle *bridgeHandle) error {
	return nil
}

func (h *bridgeHandle) openConnection(withUDP bool) (net.Conn, net.Conn, error) {
	return nil, nil, fmt.Errorf("byedpi outbound is only available on android builds")
}
