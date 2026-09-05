package congestion

import (
	"time"

	"github.com/sagernet/quic-go"
	qcongestion "github.com/sagernet/quic-go/congestion"
	congestion_meta1 "github.com/sagernet/sing-quic/congestion_meta1"
	congestion_meta2 "github.com/sagernet/sing-quic/congestion_meta2"
	E "github.com/sagernet/sing/common/exceptions"
)

const DefaultCWND = 32

type Factory func(conn *quic.Conn) qcongestion.CongestionControl

func New(name string, cwnd int, timeFunc func() time.Time) (Factory, error) {
	if cwnd < 0 {
		return nil, E.New("cwnd must be non-negative")
	}
	if timeFunc == nil {
		timeFunc = time.Now
	}
	if cwnd == 0 {
		cwnd = DefaultCWND
	}
	switch name {
	case "", "bbr":
		return func(conn *quic.Conn) qcongestion.CongestionControl {
			return congestion_meta2.NewBbrSenderWithProfile(
				qcongestion.ByteCount(conn.Config().InitialPacketSize),
				congestion_meta2.ProfileStandard,
			)
		}, nil
	case "cubic":
		return func(conn *quic.Conn) qcongestion.CongestionControl {
			return congestion_meta1.NewCubicSender(
				congestion_meta1.DefaultClock{TimeFunc: timeFunc},
				qcongestion.ByteCount(conn.Config().InitialPacketSize),
				false,
			)
		}, nil
	case "reno":
		return func(conn *quic.Conn) qcongestion.CongestionControl {
			return congestion_meta1.NewCubicSender(
				congestion_meta1.DefaultClock{TimeFunc: timeFunc},
				qcongestion.ByteCount(conn.Config().InitialPacketSize),
				true,
			)
		}, nil
	default:
		return nil, E.New("unknown congestion control: ", name)
	}
}
