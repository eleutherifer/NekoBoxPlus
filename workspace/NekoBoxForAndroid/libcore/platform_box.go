package libcore

import (
	"context"
	"fmt"
	"libcore/procfs"
	"log"
	"net/netip"
	"strings"
	"sync/atomic"
	"syscall"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
)

var boxPlatformInterfaceInstance adapter.PlatformInterface = &boxPlatformInterfaceWrapper{}
var platformNetworkManager adapter.NetworkManager

type boxPlatformInterfaceWrapper struct {
	myTunAddress  []netip.Addr
	forTest       bool
	strictProtect bool
}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState(context.Context) adapter.WIFIState {
	state := strings.Split(intfBox.WIFIState(), ",")
	if len(state) < 2 {
		state = append(state, "")
	}
	return adapter.WIFIState{
		SSID:  state[0],
		BSSID: state[1],
	}
}

func (w *boxPlatformInterfaceWrapper) Initialize(n adapter.NetworkManager) error {
	platformNetworkManager = n
	ReplayPlatformNetworkState()
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) AutoDetectInterfaceControl(fd int) error {
	// call protect_path
	if !isBgProcess {
		err := sendFdToProtect(fd, protectPath)
		if err != nil && w.forTest && !w.strictProtect {
			return nil
		}
		return err
	}
	// bg process call VPNService
	return intfBox.AutoDetectInterfaceControl(int32(fd))
}

func (w *boxPlatformInterfaceWrapper) UsePlatformInterface() bool {
	// Android must create the TUN device through VpnService and pass the FD
	// back into sing-tun. Falling back to tun.New without a platform interface
	// makes sing-box try /dev/tun directly, which fails on Android.
	return true
}

func (w *boxPlatformInterfaceWrapper) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	if err := rejectUnsupportedTunOptions(options); err != nil {
		return nil, err
	}
	payloadJSON, err := marshalAndroidTunPayload(options)
	if err != nil {
		return nil, err
	}
	// The platform options (HTTP proxy) are intentionally not forwarded: the
	// Android side owns HTTP proxy, per-app routing, metering and the
	// ParcelFileDescriptor lifecycle. Only the versioned TUN payload crosses.
	tunFd, err := intfBox.OpenTun(payloadJSON, "")
	if err != nil {
		return nil, fmt.Errorf("intfBox.OpenTun: %v", err)
	}
	// Do you want to close it?
	tunFd, err = syscall.Dup(tunFd)
	if err != nil {
		return nil, fmt.Errorf("syscall.Dup: %v", err)
	}
	//
	options.FileDescriptor = int(tunFd)
	w.myTunAddress = myInterfaceAddress(options)
	return tun.New(*options)
}

func (w *boxPlatformInterfaceWrapper) ProcessPlatformOptions(option.TunPlatformOptions) error {
	return nil
}

func myInterfaceAddress(options *tun.Options) []netip.Addr {
	addresses := make([]netip.Addr, 0, len(options.Inet4Address)+len(options.Inet6Address))
	for _, prefix := range options.Inet4Address {
		addresses = append(addresses, prefix.Addr())
	}
	for _, prefix := range options.Inet6Address {
		addresses = append(addresses, prefix.Addr())
	}
	return addresses
}

func (w *boxPlatformInterfaceWrapper) UsePlatformDefaultInterfaceMonitor() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) CreateDefaultInterfaceMonitor(l logger.Logger) tun.DefaultInterfaceMonitor {
	return newInterfaceMonitor()
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNetworkInterfaces() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	platformInterfaces, err := readNetworkInterfaces()
	if err != nil {
		return nil, err
	}
	interfaces := make([]adapter.NetworkInterface, 0, len(platformInterfaces))
	for _, networkInterface := range platformInterfaces {
		interfaces = append(interfaces, adapter.NetworkInterface{
			Interface:   networkInterface.build(),
			Type:        C.InterfaceType(networkInterface.Type),
			DNSServers:  networkInterface.DNSServers,
			Expensive:   networkInterface.Expensive,
			Constrained: networkInterface.Constrained,
		})
	}
	return interfaces, nil
}

func (w *boxPlatformInterfaceWrapper) NetworkExtensionIncludeAllNetworks() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) SendNotification(notification *adapter.Notification) error {
	return intfBox.SendNotification(
		notification.Identifier,
		notification.TypeName,
		notification.Title,
		notification.Body,
		notification.OpenURL,
	)
}

func (w *boxPlatformInterfaceWrapper) CancelNotification(identifier string, typeID int32) error {
	return intfBox.CancelNotification(identifier, typeID)
}

func (w *boxPlatformInterfaceWrapper) MyInterfaceAddress() []netip.Addr {
	return w.myTunAddress
}

// Android not using

func (w *boxPlatformInterfaceWrapper) UnderNetworkExtension() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) ClearDNSCache() {
}

func (w *boxPlatformInterfaceWrapper) RequestPermissionForWIFIState() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformWIFIMonitor() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNeighborResolver() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) StartNeighborMonitor(adapter.NeighborUpdateListener) error {
	return E.New("android: platform neighbor monitor is unsupported")
}

func (w *boxPlatformInterfaceWrapper) CloseNeighborMonitor(adapter.NeighborUpdateListener) error {
	return E.New("android: platform neighbor monitor is unsupported")
}

func (w *boxPlatformInterfaceWrapper) UsePlatformShell() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CheckPlatformShell() error {
	return E.New("android: platform shell is unsupported")
}

func (w *boxPlatformInterfaceWrapper) OpenShellSession(*adapter.PlatformUser, string, []string, string, int32, int32) (adapter.ShellSession, error) {
	return nil, E.New("android: platform shell is unsupported")
}

func (w *boxPlatformInterfaceWrapper) LookupUser(string) (*adapter.PlatformUser, error) {
	return nil, E.New("android: platform user lookup is unsupported")
}

func (w *boxPlatformInterfaceWrapper) LookupSFTPServer() (string, error) {
	return "", E.New("android: platform SFTP server is unsupported")
}

func (w *boxPlatformInterfaceWrapper) ReadSystemSSHHostKey() ([]byte, error) {
	return nil, E.New("android: platform SSH host key is unsupported")
}

func (w *boxPlatformInterfaceWrapper) TailscaleHostname() string {
	return ""
}

func (w *boxPlatformInterfaceWrapper) UsePlatformBridge() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CreateBridge(adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, E.New("android: platform bridge is unsupported")
}

func (w *boxPlatformInterfaceWrapper) UsePlatformConnectionOwnerFinder() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	var uid int32
	if useProcfs {
		sourceAddr, _ := netip.ParseAddr(request.SourceAddress)
		source := netip.AddrPortFrom(sourceAddr, uint16(request.SourcePort))
		destAddr, _ := netip.ParseAddr(request.DestinationAddress)
		destination := netip.AddrPortFrom(destAddr, uint16(request.DestinationPort))
		var network string
		switch request.IpProtocol {
		case int32(syscall.IPPROTO_TCP):
			network = "tcp"
		case int32(syscall.IPPROTO_UDP):
			network = "udp"
		default:
			return nil, E.New("unknown protocol: ", request.IpProtocol)
		}
		uid = procfs.ResolveSocketByProcSearch(network, source, destination)
		if uid == -1 {
			return nil, E.New("procfs: not found")
		}
	} else {
		var err error
		uid, err = intfBox.FindConnectionOwner(request.IpProtocol, request.SourceAddress, request.SourcePort, request.DestinationAddress, request.DestinationPort)
		if err != nil {
			return nil, err
		}
	}
	packageName, _ := intfBox.PackageNameByUid(uid)
	var packageNames []string
	if packageName != "" {
		packageNames = []string{packageName}
	}
	return &adapter.ConnectionOwner{UserId: uid, AndroidPackageNames: packageNames}, nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNotification() bool {
	return true
}

// io.Writer

var disableSingBoxLog = false

func (w *boxPlatformInterfaceWrapper) Write(p []byte) (n int, err error) {
	// use neko_log
	if !disableSingBoxLog {
		log.Print(string(p))
	}
	return len(p), nil
}

// 日志

type boxPlatformLogWriterWrapper struct {
	level atomic.Uint32
}

func newBoxPlatformLogWriter(level sblog.Level) *boxPlatformLogWriterWrapper {
	writer := new(boxPlatformLogWriterWrapper)
	writer.SetLevel(level)
	return writer
}

func (w *boxPlatformLogWriterWrapper) SetLevel(level sblog.Level) {
	w.level.Store(uint32(level))
}

func (w *boxPlatformLogWriterWrapper) allows(level sblog.Level) bool {
	return uint32(level) <= w.level.Load()
}

func (w *boxPlatformLogWriterWrapper) WriteMessage(level uint8, message string) {
	if !w.allows(level) {
		return
	}
	if !strings.HasSuffix(message, "\n") {
		message += "\n"
	}
	neko_log.LogWriter.Write([]byte(message))
}
