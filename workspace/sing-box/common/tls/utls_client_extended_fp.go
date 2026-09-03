package tls

import (
	"strings"

	utls "github.com/metacubex/utls"
)

func uTLSClientHelloIDExtended(name string) *utls.ClientHelloID {
	name = strings.TrimPrefix(strings.ToLower(name), "hello")

	switch name {
	case "golang":
		return &utls.HelloGolang
	case "custom":
		return &utls.HelloCustom
	case "randomizedalpn":
		return &utls.HelloRandomizedALPN
	case "randomizednoalpn":
		return &utls.HelloRandomizedNoALPN

	case "firefox_auto":
		return &utls.HelloFirefox_Auto
	case "firefox_55":
		return &utls.HelloFirefox_55
	case "firefox_56":
		return &utls.HelloFirefox_56
	case "firefox_63":
		return &utls.HelloFirefox_63
	case "firefox_65":
		return &utls.HelloFirefox_65
	case "firefox_99":
		return &utls.HelloFirefox_99
	case "firefox_102":
		return &utls.HelloFirefox_102
	case "firefox_105":
		return &utls.HelloFirefox_105
	case "firefox_120":
		return &utls.HelloFirefox_120
	case "firefox_148":
		return &utls.HelloFirefox_148

	case "chrome_auto":
		return &utls.HelloChrome_Auto
	case "chrome_58":
		return &utls.HelloChrome_58
	case "chrome_62":
		return &utls.HelloChrome_62
	case "chrome_70":
		return &utls.HelloChrome_70
	case "chrome_72":
		return &utls.HelloChrome_72
	case "chrome_83":
		return &utls.HelloChrome_83
	case "chrome_87":
		return &utls.HelloChrome_87
	case "chrome_96":
		return &utls.HelloChrome_96
	case "chrome_100":
		return &utls.HelloChrome_100
	case "chrome_102":
		return &utls.HelloChrome_102
	case "chrome_100_psk":
		return &utls.HelloChrome_100_PSK
	case "chrome_112_psk_shuf":
		return &utls.HelloChrome_112_PSK_Shuf
	case "chrome_114_padding_psk_shuf":
		return &utls.HelloChrome_114_Padding_PSK_Shuf
	case "chrome_115_pq":
		return &utls.HelloChrome_115_PQ
	case "chrome_115_pq_psk":
		return &utls.HelloChrome_115_PQ_PSK
	case "chrome_120":
		return &utls.HelloChrome_120
	case "chrome_120_pq":
		return &utls.HelloChrome_120_PQ
	case "chrome_131":
		return &utls.HelloChrome_131
	case "chrome_133":
		return &utls.HelloChrome_133
	case "chrome_141_ta":
		return &utls.HelloChrome_141_TA
	case "chrome_144_ta_pqs":
		return &utls.HelloChrome_144_TA_PQS

	case "ios_auto":
		return &utls.HelloIOS_Auto
	case "ios_12_1":
		return &utls.HelloIOS_12_1
	case "ios_13":
		return &utls.HelloIOS_13
	case "ios_14":
		return &utls.HelloIOS_14

	case "android_okhttp_auto":
		return &utls.HelloAndroid_OkHttp_Auto
	case "android_11_okhttp":
		return &utls.HelloAndroid_11_OkHttp
	case "android_16_okhttp":
		return &utls.HelloAndroid_16_OkHttp

	case "edge_85":
		return &utls.HelloEdge_85
	case "edge_106":
		return &utls.HelloEdge_106
	case "edge_auto":
		return &utls.HelloEdge_Auto

	case "safari_auto":
		return &utls.HelloSafari_Auto
	case "safari_16_0":
		return &utls.HelloSafari_16_0
	case "safari_26_3":
		return &utls.HelloSafari_26_3

	case "360_7_5":
		return &utls.Hello360_7_5
	case "360_11_0":
		return &utls.Hello360_11_0
	case "360_auto":
		return &utls.Hello360_Auto

	case "qq_auto":
		return &utls.HelloQQ_Auto
	case "qq_11_1":
		return &utls.HelloQQ_11_1
	}

	return nil
}
