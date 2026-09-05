package mieru

import (
	"testing"

	mierutp "github.com/enfein/mieru/v3/apis/trafficpattern"
	mierupb "github.com/enfein/mieru/v3/pkg/appctl/appctlpb"
	"github.com/sagernet/sing-box/option"
	"google.golang.org/protobuf/proto"
)

func TestBuildMieruServerConfigPreservesTrafficPatternPadding(t *testing.T) {
	trafficPattern := mierutp.Encode(&mierupb.TrafficPattern{
		Padding: &mierupb.PaddingPattern{
			MaxMiddlePaddingLen: proto.Int32(32),
			MaxEndPaddingLen:    proto.Int32(96),
		},
	})

	config, _, err := buildMieruServerConfig(t.Context(), option.MieruInboundOptions{
		ListenOptions: option.ListenOptions{
			ListenPort: 25565,
		},
		Transport: "TCP",
		Users: []option.MieruUser{
			{
				Name:     "minecraft",
				Password: "password",
			},
		},
		TrafficPattern: trafficPattern,
	})
	if err != nil {
		t.Fatal(err)
	}

	padding := config.Config.GetTrafficPattern().GetPadding()
	if padding.GetMaxMiddlePaddingLen() != 32 {
		t.Fatalf("max middle padding length = %d, want 32", padding.GetMaxMiddlePaddingLen())
	}
	if padding.GetMaxEndPaddingLen() != 96 {
		t.Fatalf("max end padding length = %d, want 96", padding.GetMaxEndPaddingLen())
	}
}

func TestBuildMieruServerConfigLowEntropyOptions(t *testing.T) {
	config, _, err := buildMieruServerConfig(t.Context(), option.MieruInboundOptions{
		ListenOptions: option.ListenOptions{
			ListenPort: 25565,
		},
		Transport: "TCP",
		Users: []option.MieruUser{
			{
				Name:     "minecraft",
				Password: "password",
			},
		},
		LowEntropyMode:         "LOW_ENTROPY_MODE_40",
		LowEntropyMaskRotation: "LOW_ENTROPY_MASK_ROTATE_LEFT_3",
	})
	if err != nil {
		t.Fatal(err)
	}

	lowEntropy := config.Config.GetTrafficPattern().GetLowEntropy()
	if lowEntropy.GetMode() != mierupb.LowEntropyMode_LOW_ENTROPY_MODE_40 {
		t.Fatalf("low entropy mode = %s, want LOW_ENTROPY_MODE_40", lowEntropy.GetMode())
	}
	if lowEntropy.GetMaskRotation() != mierupb.LowEntropyMaskRotation_LOW_ENTROPY_MASK_ROTATE_LEFT_3 {
		t.Fatalf("low entropy mask rotation = %s, want LOW_ENTROPY_MASK_ROTATE_LEFT_3", lowEntropy.GetMaskRotation())
	}
}

func TestBuildMieruTrafficPatternRejectsInvalidLowEntropyOptions(t *testing.T) {
	if _, err := buildMieruTrafficPattern("", "INVALID", ""); err == nil {
		t.Fatal("expected invalid low entropy mode error")
	}
	if _, err := buildMieruTrafficPattern("", "", "INVALID"); err == nil {
		t.Fatal("expected invalid low entropy mask rotation error")
	}
}

func TestBuildMieruTrafficPatternPreservesEncodedLowEntropyFields(t *testing.T) {
	encoded := mierutp.Encode(&mierupb.TrafficPattern{
		LowEntropy: &mierupb.LowEntropyPattern{
			Mode:         mierupb.LowEntropyMode_LOW_ENTROPY_MODE_56.Enum(),
			MaskRotation: mierupb.LowEntropyMaskRotation_LOW_ENTROPY_MASK_ROTATE_RIGHT_2.Enum(),
		},
	})
	trafficPattern, err := buildMieruTrafficPattern(encoded, "", "LOW_ENTROPY_MASK_ROTATE_LEFT_4")
	if err != nil {
		t.Fatal(err)
	}

	lowEntropy := trafficPattern.GetLowEntropy()
	if lowEntropy.GetMode() != mierupb.LowEntropyMode_LOW_ENTROPY_MODE_56 {
		t.Fatalf("low entropy mode = %s, want LOW_ENTROPY_MODE_56", lowEntropy.GetMode())
	}
	if lowEntropy.GetMaskRotation() != mierupb.LowEntropyMaskRotation_LOW_ENTROPY_MASK_ROTATE_LEFT_4 {
		t.Fatalf("low entropy mask rotation = %s, want LOW_ENTROPY_MASK_ROTATE_LEFT_4", lowEntropy.GetMaskRotation())
	}
}
