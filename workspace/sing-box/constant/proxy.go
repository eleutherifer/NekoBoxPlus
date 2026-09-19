package constant

const (
	TypeTun             = "tun"
	TypeRedirect        = "redirect"
	TypeTProxy          = "tproxy"
	TypeDirect          = "direct"
	TypeFragmentExclave = "fragment-exclave"
	TypeBlock           = "block"
	TypeDNS             = "dns"
	TypeSOCKS           = "socks"
	TypeHTTP            = "http"
	TypeMixed           = "mixed"
	TypeShadowsocks     = "shadowsocks"
	TypeVMess           = "vmess"
	TypeTrojan          = "trojan"
	TypeNaive           = "naive"
	TypeWireGuard       = "wireguard"
	TypeAwg             = "awg"
	TypeHysteria        = "hysteria"
	TypeTor             = "tor"
	TypeSSH             = "ssh"
	TypeShadowTLS       = "shadowtls"
	TypeMieru           = "mieru"
	TypeAnyTLS          = "anytls"
	TypeSnell           = "snell"
	TypeShadowsocksR    = "shadowsocksr"
	TypeVLESS           = "vless"
	TypeTUIC            = "tuic"
	TypeHysteria2       = "hysteria2"
	TypeTailscale       = "tailscale"
	TypeDERP            = "derp"
	TypeResolved        = "resolved"
	TypeSSMAPI          = "ssm-api"
	TypeCCM             = "ccm"
	TypeOCM             = "ocm"
	TypeOOMKiller       = "oom-killer"
	TypeMASQUE          = "masque"
)

const (
	TypeSelector = "selector"
	TypeURLTest  = "urltest"
)

func ProxyDisplayName(proxyType string) string {
	switch proxyType {
	case TypeTun:
		return "TUN"
	case TypeRedirect:
		return "Redirect"
	case TypeTProxy:
		return "TProxy"
	case TypeDirect:
		return "Direct"
	case TypeFragmentExclave:
		return "Fragment Exclave"
	case TypeBlock:
		return "Block"
	case TypeDNS:
		return "DNS"
	case TypeSOCKS:
		return "SOCKS"
	case TypeHTTP:
		return "HTTP"
	case TypeMixed:
		return "Mixed"
	case TypeShadowsocks:
		return "Shadowsocks"
	case TypeVMess:
		return "VMess"
	case TypeTrojan:
		return "Trojan"
	case TypeNaive:
		return "Naive"
	case TypeWireGuard:
		return "WireGuard"
	case TypeAwg:
		return "AmneziaWG"
	case TypeHysteria:
		return "Hysteria"
	case TypeTor:
		return "Tor"
	case TypeSSH:
		return "SSH"
	case TypeShadowTLS:
		return "ShadowTLS"
	case TypeMieru:
		return "Mieru"
	case TypeShadowsocksR:
		return "ShadowsocksR"
	case TypeVLESS:
		return "VLESS"
	case TypeTUIC:
		return "TUIC"
	case TypeHysteria2:
		return "Hysteria2"
	case TypeAnyTLS:
		return "AnyTLS"
	case TypeTailscale:
		return "Tailscale"
	case TypeSnell:
		return "Snell"
	case TypeSelector:
		return "Selector"
	case TypeURLTest:
		return "URLTest"
	default:
		return "Unknown"
	}
}
