package libcore

var intfBox BoxPlatformInterface
var intfNB4A NB4AInterface

var useProcfs bool
var isBgProcess bool

type NB4AInterface interface {
	UseOfficialAssets() bool
	Selector_OnProxySelected(selectorTag string, tag string)
	MasterDnsVPNResolverProgress(found int32, total int32, ready bool)
	MasterDnsVPNStartupFailed(noWorkingDNS bool, message string)
	EndpointAuthenticationRequired(protocol string, detail string)
}

type BoxPlatformInterface interface {
	AutoDetectInterfaceControl(fd int32) error
	OpenTun(singTunOptionsJson, tunPlatformOptionsJson string) (int, error)
	UseProcFS() bool
	FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (int32, error)
	PackageNameByUid(uid int32) (string, error)
	UIDByPackageName(packageName string) (int32, error)
	WIFIState() string
	DefaultInterface() string
	NetworkInterfaces() string
	SendNotification(identifier, typeName, title, body, openURL string) error
	CancelNotification(identifier string, typeID int32) error
}
