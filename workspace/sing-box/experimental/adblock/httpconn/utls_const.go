//go:build with_adblock

package httpconn

import (
	"strings"

	"github.com/sagernet/sing-box/experimental/adblock/consts"
	E "github.com/sagernet/sing/common/exceptions"
)

func UTLSFingerprintIDFromString(name string) (consts.UTLSFingerprintID, error) {
	switch strings.ToLower(strings.TrimSpace(name)) {
	case "golang":
		return consts.Golang, nil
	case "custom":
		return consts.Custom, nil
	case "randomizedalpn":
		return consts.RandomizedALPN, nil
	case "randomizednoalpn":
		return consts.RandomizedNoALPN, nil

	case "chrome", "chrome_auto":
		return consts.Chrome, nil
	case "chrome_58":
		return consts.Chrome58, nil
	case "chrome_62":
		return consts.Chrome62, nil
	case "chrome_70":
		return consts.Chrome70, nil
	case "chrome_72":
		return consts.Chrome72, nil
	case "chrome_83":
		return consts.Chrome83, nil
	case "chrome_87":
		return consts.Chrome87, nil
	case "chrome_96":
		return consts.Chrome96, nil
	case "chrome_100":
		return consts.Chrome100, nil
	case "chrome_102":
		return consts.Chrome102, nil
	case "chrome_100_psk", "chrome_psk":
		return consts.Chrome100PSK, nil
	case "chrome_112_psk_shuf", "chrome_psk_shuffle":
		return consts.Chrome112PSKShuf, nil
	case "chrome_114_padding_psk_shuf", "chrome_padding_psk_shuffle":
		return consts.Chrome114PaddingPSKShuf, nil
	case "chrome_115_pq", "chrome_pq":
		return consts.Chrome115PQ, nil
	case "chrome_115_pq_psk", "chrome_pq_psk":
		return consts.Chrome115PQPSK, nil
	case "chrome_120":
		return consts.Chrome120, nil
	case "chrome_120_pq":
		return consts.Chrome120PQ, nil
	case "chrome_131":
		return consts.Chrome131, nil
	case "chrome_133":
		return consts.Chrome133, nil
	case "chrome_141_ta":
		return consts.Chrome141TA, nil
	case "chrome_144_ta_pqs":
		return consts.Chrome144TAPQS, nil

	case "firefox", "firefox_auto":
		return consts.Firefox, nil
	case "firefox_55":
		return consts.Firefox55, nil
	case "firefox_56":
		return consts.Firefox56, nil
	case "firefox_63":
		return consts.Firefox63, nil
	case "firefox_65":
		return consts.Firefox65, nil
	case "firefox_99":
		return consts.Firefox99, nil
	case "firefox_102":
		return consts.Firefox102, nil
	case "firefox_105":
		return consts.Firefox105, nil
	case "firefox_120":
		return consts.Firefox120, nil
	case "firefox_148":
		return consts.Firefox148, nil

	case "edge", "edge_auto":
		return consts.Edge, nil
	case "edge_85":
		return consts.Edge85, nil
	case "edge_106":
		return consts.Edge106, nil

	case "safari", "safari_auto":
		return consts.Safari, nil
	case "safari_16_0":
		return consts.Safari16_0, nil
	case "safari_26_3":
		return consts.Safari26_3, nil

	case "360", "360_auto":
		return consts.Fp360, nil
	case "360_7_5":
		return consts.Fp360_7_5, nil
	case "360_11_0":
		return consts.Fp360_11_0, nil

	case "qq", "qq_auto":
		return consts.QQ, nil
	case "qq_11_1":
		return consts.QQ11_1, nil

	case "ios", "ios_auto":
		return consts.IOS, nil
	case "ios_12_1":
		return consts.IOS12_1, nil
	case "ios_13":
		return consts.IOS13, nil
	case "ios_14":
		return consts.IOS14, nil

	case "android", "android_okhttp_auto":
		return consts.Android, nil
	case "android_11_okhttp":
		return consts.Android11OkHttp, nil
	case "android_16_okhttp":
		return consts.Android16OkHttp, nil

	case "random":
		return consts.Random, nil
	case "randomized":
		return consts.Randomized, nil
	default:
		return consts.Invalid, E.New("unknown uTLS fingerprint: ", name)
	}
}
