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
