//go:build with_adblock

package consts

type UTLSFingerprintID uint8

const (
	Invalid UTLSFingerprintID = iota
	Chrome
	Firefox
	Edge
	Safari
	Fp360
	QQ
	IOS
	Android
	Random
	Randomized

	Chrome58
	Chrome62
	Chrome70
	Chrome72
	Chrome83
	Chrome87
	Chrome96
	Chrome100
	Chrome102
	Chrome100PSK
	Chrome112PSKShuf
	Chrome114PaddingPSKShuf
	Chrome115PQ
	Chrome115PQPSK
	Chrome120
	Chrome120PQ
	Chrome131
	Chrome133
	Chrome141TA
	Chrome144TAPQS

	Firefox55
	Firefox56
	Firefox63
	Firefox65
	Firefox99
	Firefox102
	Firefox105
	Firefox120
	Firefox148

	IOS12_1
	IOS13
	IOS14

	AndroidOkHttpAuto
	Android11OkHttp
	Android16OkHttp

	Edge85
	Edge106

	Safari16_0
	Safari26_3

	Fp360_7_5
	Fp360_11_0

	QQ11_1

	Golang
	Custom
	RandomizedALPN
	RandomizedNoALPN
)
