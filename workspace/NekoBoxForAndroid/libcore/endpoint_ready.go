package libcore

import (
	"context"
	"errors"
	"fmt"

	"github.com/sagernet/sing-box/adapter"
)

func waitURLTestOutboundReady(ctx context.Context, outboundManager adapter.OutboundManager, detour adapter.Outbound) error {
	visited := make(map[string]bool)
	for detour != nil {
		switch endpoint := detour.(type) {
		case adapter.OpenVPNEndpoint:
			return waitOpenVPNReady(ctx, endpoint)
		case adapter.OpenConnectEndpoint:
			return waitOpenConnectReady(ctx, endpoint)
		case adapter.OutboundGroup:
			tag := endpoint.Now()
			if tag == "" || visited[tag] {
				return nil
			}
			visited[tag] = true
			selected, loaded := outboundManager.Outbound(tag)
			if !loaded {
				return fmt.Errorf("selected URLTest outbound %q is not found", tag)
			}
			detour = selected
		default:
			return nil
		}
	}
	return nil
}

func waitOpenVPNReady(ctx context.Context, endpoint adapter.OpenVPNEndpoint) error {
	for {
		statusUpdated := endpoint.StatusUpdated()
		status := endpoint.OpenVPNStatus()
		switch status.State {
		case adapter.OpenVPNStateConnected:
			return nil
		case adapter.OpenVPNStateAuthPending:
			return errors.New("OpenVPN authentication is required")
		case adapter.OpenVPNStateError:
			if status.Error != "" {
				return fmt.Errorf("OpenVPN client failed: %s", status.Error)
			}
			return errors.New("OpenVPN client failed")
		}
		select {
		case <-ctx.Done():
			return context.Cause(ctx)
		case <-statusUpdated:
		}
	}
}

func waitOpenConnectReady(ctx context.Context, endpoint adapter.OpenConnectEndpoint) error {
	for {
		statusUpdated := endpoint.StatusUpdated()
		status := endpoint.OpenConnectStatus()
		switch status.State {
		case adapter.OpenConnectStateConnected:
			return nil
		case adapter.OpenConnectStateAuthPending:
			return errors.New("OpenConnect authentication is required")
		case adapter.OpenConnectStateError:
			if status.Error != "" {
				return fmt.Errorf("OpenConnect client failed: %s", status.Error)
			}
			return errors.New("OpenConnect client failed")
		}
		select {
		case <-ctx.Done():
			return context.Cause(ctx)
		case <-statusUpdated:
		}
	}
}
