// Package pausecontrol serializes WireGuard-family device lifecycle operations.
package pausecontrol

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service/pause"
)

type Device interface {
	Up() error
	Down() error
	BindUpdate() error
	SendKeepalivesToPeersWithCurrentKeypair()
	Close()
}

// Controller keeps callbacks nonblocking: pause.Manager invokes them under its
// own lock, while a device operation can wait for network I/O to finish.
type Controller struct {
	manager     pause.Manager
	device      Device
	afterRebind func()
	report      func(error)
	callback    *list.Element[pause.Callback]
	access      sync.Mutex
	rebind      bool
	// forceDown remembers a pause even if wake arrives before the worker runs.
	forceDown bool
	ready     bool
	lastError error
	updated   chan struct{}
	closed    bool
	signal    chan struct{}
	stop      chan struct{}
	done      chan struct{}
	closeOnce sync.Once
}

func New(manager pause.Manager, device Device, afterRebind func(), report func(error)) *Controller {
	c := &Controller{
		manager:     manager,
		device:      device,
		afterRebind: afterRebind,
		report:      report,
		signal:      make(chan struct{}, 1),
		stop:        make(chan struct{}),
		done:        make(chan struct{}),
		updated:     make(chan struct{}),
	}
	if manager != nil {
		c.callback = manager.RegisterCallback(c.changed)
	}
	go c.run()
	c.notify()
	return c
}

func (c *Controller) notify() {
	select {
	case c.signal <- struct{}{}:
	default:
	}
}

func (c *Controller) changed(event int) {
	c.access.Lock()
	if event == pause.EventDevicePaused || event == pause.EventNetworkPause {
		c.forceDown = true
	}
	c.setReadyLocked(false, nil)
	c.access.Unlock()
	c.notify()
}

func (c *Controller) Rebind() {
	c.access.Lock()
	if !c.closed {
		c.rebind = true
		c.setReadyLocked(false, nil)
	}
	c.access.Unlock()
	c.notify()
}

func (c *Controller) setReadyLocked(ready bool, err error) {
	c.ready = ready
	c.lastError = err
	close(c.updated)
	c.updated = make(chan struct{})
}

func (c *Controller) WaitReady(ctx context.Context) error {
	for {
		c.access.Lock()
		if c.closed {
			err := c.lastError
			c.access.Unlock()
			return errors.Join(errors.New("device lifecycle controller is closed"), err)
		}
		if c.ready {
			c.access.Unlock()
			return nil
		}
		updated := c.updated
		lastError := c.lastError
		c.access.Unlock()

		select {
		case <-ctx.Done():
			return errors.Join(context.Cause(ctx), lastError)
		case <-updated:
		}
	}
}

func (c *Controller) paused() bool {
	return c.manager != nil && (c.manager.IsDevicePaused() || c.manager.IsNetworkPaused())
}

func (c *Controller) run() {
	defer close(c.done)
	// All device calls, including Close, belong to this worker.
	defer c.device.Close()
	active := false
	retryDelay := time.Second
	var retry <-chan time.Time
	var timer *time.Timer
	defer func() {
		if timer != nil {
			timer.Stop()
		}
	}()
	for {
		select {
		case <-c.stop:
			return
		case <-c.signal:
		case <-retry:
		}
		if timer != nil {
			timer.Stop()
			timer = nil
		}
		retry = nil
		c.access.Lock()
		if c.closed {
			c.access.Unlock()
			return
		}
		forceDown, rebind := c.forceDown, c.rebind
		c.forceDown, c.rebind = false, false
		c.access.Unlock()

		var err error
		if forceDown || c.paused() {
			err = c.device.Down()
			if err == nil {
				active = false
			}
		}
		if err == nil && !c.paused() {
			if !active {
				err = c.device.Up()
				active = err == nil
			}
			if err == nil && rebind {
				err = c.device.BindUpdate()
				if c.afterRebind != nil {
					c.afterRebind()
				}
			}
			if err == nil && (forceDown || rebind) {
				c.device.SendKeepalivesToPeersWithCurrentKeypair()
			}
		} else if rebind {
			c.access.Lock()
			c.rebind = true
			c.access.Unlock()
		}
		if err != nil {
			c.access.Lock()
			c.forceDown = c.forceDown || forceDown
			c.rebind = c.rebind || rebind
			c.setReadyLocked(false, err)
			c.access.Unlock()
			c.report(errors.Join(errors.New("device lifecycle update failed"), err))
			timer = time.NewTimer(retryDelay)
			retry = timer.C
			retryDelay = min(2*retryDelay, 30*time.Second)
		} else {
			c.access.Lock()
			ready := !c.closed && active && !c.forceDown && !c.rebind && !c.paused()
			c.setReadyLocked(ready, nil)
			c.access.Unlock()
			retryDelay = time.Second
		}
	}
}

func (c *Controller) Close() {
	c.closeOnce.Do(func() {
		if c.callback != nil {
			c.manager.UnregisterCallback(c.callback)
		}
		c.access.Lock()
		c.closed = true
		c.setReadyLocked(false, c.lastError)
		c.access.Unlock()
		close(c.stop)
	})
	<-c.done
}
