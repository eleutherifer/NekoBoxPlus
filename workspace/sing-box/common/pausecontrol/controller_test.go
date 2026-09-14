package pausecontrol

import (
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type fakeDevice struct {
	access sync.Mutex
	events chan string
	failUp bool
}

func (d *fakeDevice) event(s string) { d.events <- s }

func (d *fakeDevice) Up() error {
	d.access.Lock()
	fail := d.failUp
	d.failUp = false
	d.access.Unlock()
	if fail {
		d.event("up-failed")
		return errors.New("network unavailable")
	}
	d.event("up")
	return nil
}
func (d *fakeDevice) Down() error {
	d.event("down")
	return nil
}

func (d *fakeDevice) BindUpdate() error {
	d.event("bind")
	return nil
}

func (d *fakeDevice) SendKeepalivesToPeersWithCurrentKeypair() { d.event("keepalive") }
func (d *fakeDevice) Close()                                   { d.event("close") }

func expect(t *testing.T, d *fakeDevice, event string) {
	t.Helper()
	select {
	case got := <-d.events:
		if got != event {
			t.Fatalf("got %s, want %s", got, event)
		}
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for %s", event)
	}
}

func manager(t *testing.T) pause.Manager {
	return service.FromContext[pause.Manager](pause.WithDefaultManager(service.ContextWithDefaultRegistry(t.Context())))
}

func TestOverlappingPauses(t *testing.T) {
	for _, deviceFirst := range []bool{true, false} {
		t.Run(map[bool]string{true: "device-first", false: "network-first"}[deviceFirst], func(t *testing.T) {
			m := manager(t)
			d := &fakeDevice{events: make(chan string, 32)}
			c := New(m, d, nil, func(err error) { t.Error(err) })
			defer c.Close()
			expect(t, d, "up")
			m.DevicePause()
			expect(t, d, "down")
			m.NetworkPause()
			expect(t, d, "down")
			if deviceFirst {
				m.DeviceWake()
			} else {
				m.NetworkWake()
			}
			expect(t, d, "down") // must not go up while the other reason remains paused
			c.Rebind()
			expect(t, d, "down")
			if deviceFirst {
				m.NetworkWake()
			} else {
				m.DeviceWake()
			}
			expect(t, d, "up")
			expect(t, d, "bind")
			expect(t, d, "keepalive")
		})
	}
}

func TestStartsPausedAndCannotReopenAfterClose(t *testing.T) {
	m := manager(t)
	m.DevicePause()
	d := &fakeDevice{events: make(chan string, 32)}
	c := New(m, d, nil, func(err error) { t.Error(err) })
	expect(t, d, "down")
	c.Close()
	expect(t, d, "close")
	m.DeviceWake()
	c.Rebind()
	c.Close()
	select {
	case event := <-d.events:
		t.Fatalf("operation after close: %s", event)
	default:
	}
}

func TestFailedResumeRetriesWithoutNetworkEvent(t *testing.T) {
	d := &fakeDevice{events: make(chan string, 32), failUp: true}
	reported := make(chan error, 1)
	c := New(nil, d, nil, func(err error) { reported <- err })
	defer c.Close()
	expect(t, d, "up-failed")
	expect(t, d, "up")
	select {
	case <-reported:
	default:
		t.Fatal("failure not reported")
	}
}

func TestPauseCallbackDoesNotWaitForDevice(t *testing.T) {
	m := manager(t)
	d := &blockingDevice{
		fakeDevice: fakeDevice{events: make(chan string, 32)},
		release:    make(chan struct{}),
	}
	c := New(m, d, nil, func(err error) { t.Error(err) })
	expect(t, &d.fakeDevice, "up")
	m.DevicePause()
	expect(t, &d.fakeDevice, "down-blocked")
	returned := make(chan struct{})
	go func() {
		m.DeviceWake()
		close(returned)
	}()
	select {
	case <-returned:
	case <-time.After(time.Second):
		t.Error("pause manager callback blocked")
	}
	close(d.release)
	c.Close()
}

type blockingDevice struct {
	fakeDevice
	release chan struct{}
}

func (d *blockingDevice) Down() error {
	d.event("down-blocked")
	<-d.release
	return nil
}
