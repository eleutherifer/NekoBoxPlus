package libcore

import (
	"testing"

	"github.com/sagernet/sing/common/control"
)

func TestInterfaceMonitorReplaysDefaultInterfaceOnReload(t *testing.T) {
	currentPlatformNetworkState.access.Lock()
	originalDefaultJSON := currentPlatformNetworkState.defaultJSON
	originalInterfacesJSON := currentPlatformNetworkState.interfacesJSON
	originalDefaultInterface := currentPlatformNetworkState.defaultInterface
	currentPlatformNetworkState.access.Unlock()
	t.Cleanup(func() {
		currentPlatformNetworkState.access.Lock()
		currentPlatformNetworkState.defaultJSON = originalDefaultJSON
		currentPlatformNetworkState.interfacesJSON = originalInterfacesJSON
		currentPlatformNetworkState.defaultInterface = originalDefaultInterface
		currentPlatformNetworkState.access.Unlock()
	})

	const defaultInterfaceJSON = `{"name":"wlan0","index":7}`
	UpdatePlatformNetworkState(defaultInterfaceJSON, `[]`)

	startAndVerify := func() {
		t.Helper()
		monitor := newInterfaceMonitor()
		var updates []*control.Interface
		monitor.RegisterCallback(func(current *control.Interface, _ int) {
			updates = append(updates, current)
		})
		if err := monitor.Start(); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := monitor.Close(); err != nil {
				t.Error(err)
			}
		})
		if len(updates) != 1 {
			t.Fatalf("initial updates = %d, want 1", len(updates))
		}
		if updates[0] == nil || updates[0].Name != "wlan0" || updates[0].Index != 7 {
			t.Fatalf("initial default interface = %#v", updates[0])
		}
	}

	startAndVerify()
	startAndVerify()
}

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
