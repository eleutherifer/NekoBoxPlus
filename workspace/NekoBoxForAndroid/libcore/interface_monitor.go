package libcore

import (
	"encoding/json"
	"net"
	"net/netip"
	"sync"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/x/list"
)

const netFlagUp = 1

type platformDefaultInterface struct {
	Name        string `json:"name"`
	Index       int    `json:"index"`
	Expensive   bool   `json:"expensive"`
	Constrained bool   `json:"constrained"`
}

type platformNetworkInterface struct {
	Index       int      `json:"index"`
	MTU         int      `json:"mtu"`
	Name        string   `json:"name"`
	Flags       int      `json:"flags"`
	Addresses   []string `json:"addresses"`
	Type        int      `json:"type"`
	DNSServers  []string `json:"dns_servers"`
	Expensive   bool     `json:"expensive"`
	Constrained bool     `json:"constrained"`
}

type interfaceMonitor struct {
	access           sync.Mutex
	callbacks        list.List[tun.DefaultInterfaceUpdateCallback]
	defaultInterface *control.Interface
	myInterfaces     []string
}

type platformNetworkState struct {
	access           sync.Mutex
	defaultJSON      string
	interfacesJSON   string
	defaultInterface platformDefaultInterface
	monitors         map[*interfaceMonitor]struct{}
}

var currentPlatformNetworkState = &platformNetworkState{
	monitors: make(map[*interfaceMonitor]struct{}),
}

func newInterfaceMonitor() *interfaceMonitor {
	return &interfaceMonitor{}
}

func (m *interfaceMonitor) Start() error {
	currentPlatformNetworkState.access.Lock()
	currentPlatformNetworkState.monitors[m] = struct{}{}
	current := buildDefaultControlInterface(currentPlatformNetworkState.defaultInterface)
	currentPlatformNetworkState.access.Unlock()
	m.setDefaultInterface(current, true)
	return nil
}

func (m *interfaceMonitor) Close() error {
	currentPlatformNetworkState.access.Lock()
	delete(currentPlatformNetworkState.monitors, m)
	currentPlatformNetworkState.access.Unlock()
	return nil
}

func (m *interfaceMonitor) DefaultInterface() *control.Interface {
	m.access.Lock()
	defer m.access.Unlock()
	return m.defaultInterface
}

func (m *interfaceMonitor) OverrideAndroidVPN() bool {
	return false
}

func (m *interfaceMonitor) AndroidVPNEnabled() bool {
	return false
}

func (m *interfaceMonitor) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	m.access.Lock()
	defer m.access.Unlock()
	return m.callbacks.PushBack(callback)
}

func (m *interfaceMonitor) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	m.access.Lock()
	defer m.access.Unlock()
	m.callbacks.Remove(element)
}

func (m *interfaceMonitor) RegisterMyInterface(interfaceName string) {
	m.access.Lock()
	defer m.access.Unlock()
	m.myInterfaces = append(m.myInterfaces, interfaceName)
}

func (m *interfaceMonitor) MyInterfaces() []string {
	m.access.Lock()
	defer m.access.Unlock()
	return m.myInterfaces
}

func (m *interfaceMonitor) setDefaultInterface(current *control.Interface, notify bool) {
	m.access.Lock()
	old := m.defaultInterface
	m.defaultInterface = current
	callbacks := m.callbacks.Array()
	m.access.Unlock()
	if !notify {
		return
	}
	if sameControlInterface(old, current) {
		return
	}
	for _, callback := range callbacks {
		callback(current, 0)
	}
}

func buildDefaultControlInterface(state platformDefaultInterface) *control.Interface {
	if state.Name == "" && state.Index == 0 {
		return nil
	}
	return &control.Interface{
		Index: state.Index,
		Name:  state.Name,
		Flags: net.Flags(netFlagUp),
	}
}

func sameControlInterface(left *control.Interface, right *control.Interface) bool {
	if left == nil || right == nil {
		return left == right
	}
	return left.Index == right.Index && left.Name == right.Name
}

func UpdatePlatformNetworkState(defaultInterfaceJSON string, interfacesJSON string) {
	state, err := parseDefaultInterface(defaultInterfaceJSON)
	if err != nil {
		return
	}
	currentPlatformNetworkState.access.Lock()
	if currentPlatformNetworkState.defaultJSON == defaultInterfaceJSON &&
		currentPlatformNetworkState.interfacesJSON == interfacesJSON {
		currentPlatformNetworkState.access.Unlock()
		return
	}
	currentPlatformNetworkState.defaultJSON = defaultInterfaceJSON
	currentPlatformNetworkState.interfacesJSON = interfacesJSON
	currentPlatformNetworkState.defaultInterface = state
	monitors := make([]*interfaceMonitor, 0, len(currentPlatformNetworkState.monitors))
	for monitor := range currentPlatformNetworkState.monitors {
		monitors = append(monitors, monitor)
	}
	current := buildDefaultControlInterface(state)
	currentPlatformNetworkState.access.Unlock()
	if platformNetworkManager != nil {
		_ = platformNetworkManager.UpdateInterfaces()
	}
	for _, monitor := range monitors {
		monitor.setDefaultInterface(current, true)
	}
}

func ReplayPlatformNetworkState() {
	if platformNetworkManager != nil {
		_ = platformNetworkManager.UpdateInterfaces()
	}
}

func UpdatePlatformWIFIState() {
	if platformNetworkManager != nil {
		platformNetworkManager.UpdateWIFIState()
	}
}

func parseDefaultInterface(content string) (platformDefaultInterface, error) {
	var state platformDefaultInterface
	if content == "" {
		return state, nil
	}
	err := json.Unmarshal([]byte(content), &state)
	return state, err
}

func readNetworkInterfaces() ([]platformNetworkInterface, error) {
	currentPlatformNetworkState.access.Lock()
	content := currentPlatformNetworkState.interfacesJSON
	currentPlatformNetworkState.access.Unlock()
	if content == "" {
		return nil, nil
	}
	var interfaces []platformNetworkInterface
	err := json.Unmarshal([]byte(content), &interfaces)
	return interfaces, err
}

func (i platformNetworkInterface) build() control.Interface {
	addresses := make([]netip.Prefix, 0, len(i.Addresses))
	for _, prefix := range i.Addresses {
		address, err := netip.ParsePrefix(prefix)
		if err != nil {
			continue
		}
		addresses = append(addresses, address)
	}
	return control.Interface{
		Index:     i.Index,
		MTU:       i.MTU,
		Name:      i.Name,
		Flags:     net.Flags(i.Flags),
		Addresses: addresses,
	}
}
