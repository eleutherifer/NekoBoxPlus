package libcore

import (
	"testing"

	"github.com/sagernet/sing/common/control"
)

func TestInterfaceMonitorDetectsNetworkHandleChange(t *testing.T) {
	monitor := newInterfaceMonitor()
	callbackCount := 0
	monitor.RegisterCallback(func(_ *control.Interface, _ int) {
		callbackCount++
	})

	first := platformDefaultInterface{Name: "wlan0", Index: 10, NetworkHandle: 100}
	monitor.setDefaultInterface(buildDefaultControlInterface(first), first, true)
	monitor.setDefaultInterface(buildDefaultControlInterface(first), first, true)
	second := platformDefaultInterface{Name: "wlan0", Index: 10, NetworkHandle: 101}
	monitor.setDefaultInterface(buildDefaultControlInterface(second), second, true)

	if callbackCount != 2 {
		t.Fatalf("expected callbacks for initial state and handle change, got %d", callbackCount)
	}
}
