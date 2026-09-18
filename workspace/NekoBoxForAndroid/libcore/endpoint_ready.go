package libcore

import (
	"context"
	"fmt"
	"time"

	"github.com/sagernet/sing-box/adapter"
)

func waitURLTestOutboundReady(ctx context.Context, outboundManager adapter.OutboundManager, detour adapter.Outbound) error {
	return waitURLTestOutboundTreeReady(ctx, outboundManager, detour, make(map[string]bool))
}

func waitURLTestOutboundTreeReady(
	ctx context.Context,
	outboundManager adapter.OutboundManager,
	detour adapter.Outbound,
	visited map[string]bool,
) error {
	if detour == nil {
		return nil
	}
	tag := detour.Tag()
	if tag != "" {
		if visited[tag] {
			return nil
		}
		visited[tag] = true
	}
	if group, isGroup := detour.(adapter.OutboundGroup); isGroup {
		selectedTag := group.Now()
		if selectedTag == "" {
			return nil
		}
		selected, loaded := outboundManager.Outbound(selectedTag)
		if !loaded {
			return fmt.Errorf("selected URLTest outbound %q is not found", selectedTag)
		}
		return waitURLTestOutboundTreeReady(ctx, outboundManager, selected, visited)
	}
	for _, dependencyTag := range detour.Dependencies() {
		dependency, loaded := outboundManager.Outbound(dependencyTag)
		if !loaded {
			return fmt.Errorf("URLTest outbound dependency %q is not found", dependencyTag)
		}
		if err := waitURLTestOutboundTreeReady(ctx, outboundManager, dependency, visited); err != nil {
			return err
		}
	}
	if readiness, hasReadiness := detour.(adapter.OutboundWithReadiness); hasReadiness {
		if err := readiness.WaitReady(ctx); err != nil {
			return fmt.Errorf("wait for URLTest outbound %q readiness: %w", detour.Tag(), err)
		}
	}
	return nil
}

func waitURLTestOutboundReadyTimeout(
	ctx context.Context,
	outboundManager adapter.OutboundManager,
	detour adapter.Outbound,
	timeout time.Duration,
) error {
	readyCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	return waitURLTestOutboundReady(readyCtx, outboundManager, detour)
}

func runURLTestAfterOutboundReady(
	ctx context.Context,
	outboundManager adapter.OutboundManager,
	detour adapter.Outbound,
	timeoutMillis int32,
	attempts int32,
	pauseMillis int32,
	test func(context.Context) (int32, error),
) (int32, error) {
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if detour != nil {
		if err := waitURLTestOutboundReadyTimeout(ctx, outboundManager, detour, timeout); err != nil {
			return -1, err
		}
	}
	return runURLTestAttempts(ctx, timeoutMillis, attempts, pauseMillis, test)
}
