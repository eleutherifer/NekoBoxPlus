//go:build with_adblock && with_utls

package httpconn

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"math/rand"
	"net"

	utls "github.com/metacubex/utls"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/experimental/adblock/consts"
	"github.com/sagernet/sing-box/experimental/adblock/ctx"
	E "github.com/sagernet/sing/common/exceptions"
	"golang.org/x/net/http2"
)

var (
	randomFingerprint     utls.ClientHelloID
	randomizedFingerprint utls.ClientHelloID
)

const SupportsUTLS = true

func init() {
	modernFingerprints := []utls.ClientHelloID{
		utls.HelloChrome_Auto,
		utls.HelloFirefox_Auto,
		utls.HelloEdge_Auto,
		utls.HelloSafari_Auto,
		utls.HelloIOS_Auto,
	}
	randomFingerprint = modernFingerprints[rand.Intn(len(modernFingerprints))]

	weights := utls.DefaultWeights
	weights.TLSVersMax_Set_VersionTLS13 = 1
	weights.FirstKeyShare_Set_CurveP256 = 0
	randomizedFingerprint = utls.HelloRandomized
	randomizedFingerprint.Seed, _ = utls.NewPRNGSeed()
	randomizedFingerprint.Weights = &weights
}

func uTLSClientHelloID(name consts.UTLSFingerprintID) (utls.ClientHelloID, error) {
	switch name {
	case consts.Golang:
		return utls.HelloGolang, nil
	case consts.Custom:
		return utls.HelloCustom, nil
	case consts.RandomizedALPN:
		return utls.HelloRandomizedALPN, nil
	case consts.RandomizedNoALPN:
		return utls.HelloRandomizedNoALPN, nil

	case consts.Chrome:
		return utls.HelloChrome_Auto, nil
	case consts.Chrome58:
		return utls.HelloChrome_58, nil
	case consts.Chrome62:
		return utls.HelloChrome_62, nil
	case consts.Chrome70:
		return utls.HelloChrome_70, nil
	case consts.Chrome72:
		return utls.HelloChrome_72, nil
	case consts.Chrome83:
		return utls.HelloChrome_83, nil
	case consts.Chrome87:
		return utls.HelloChrome_87, nil
	case consts.Chrome96:
		return utls.HelloChrome_96, nil
	case consts.Chrome100:
		return utls.HelloChrome_100, nil
	case consts.Chrome102:
		return utls.HelloChrome_102, nil
	case consts.Chrome100PSK:
		return utls.HelloChrome_100_PSK, nil
	case consts.Chrome112PSKShuf:
		return utls.HelloChrome_112_PSK_Shuf, nil
	case consts.Chrome114PaddingPSKShuf:
		return utls.HelloChrome_114_Padding_PSK_Shuf, nil
	case consts.Chrome115PQ:
		return utls.HelloChrome_115_PQ, nil
	case consts.Chrome115PQPSK:
		return utls.HelloChrome_115_PQ_PSK, nil
	case consts.Chrome120:
		return utls.HelloChrome_120, nil
	case consts.Chrome120PQ:
		return utls.HelloChrome_120_PQ, nil
	case consts.Chrome131:
		return utls.HelloChrome_131, nil
	case consts.Chrome133:
		return utls.HelloChrome_133, nil
	case consts.Chrome141TA:
		return utls.HelloChrome_141_TA, nil
	case consts.Chrome144TAPQS:
		return utls.HelloChrome_144_TA_PQS, nil

	case consts.Firefox:
		return utls.HelloFirefox_Auto, nil
	case consts.Firefox55:
		return utls.HelloFirefox_55, nil
	case consts.Firefox56:
		return utls.HelloFirefox_56, nil
	case consts.Firefox63:
		return utls.HelloFirefox_63, nil
	case consts.Firefox65:
		return utls.HelloFirefox_65, nil
	case consts.Firefox99:
		return utls.HelloFirefox_99, nil
	case consts.Firefox102:
		return utls.HelloFirefox_102, nil
	case consts.Firefox105:
		return utls.HelloFirefox_105, nil
	case consts.Firefox120:
		return utls.HelloFirefox_120, nil
	case consts.Firefox148:
		return utls.HelloFirefox_148, nil

	case consts.Edge:
		return utls.HelloEdge_Auto, nil
	case consts.Edge85:
		return utls.HelloEdge_85, nil
	case consts.Edge106:
		return utls.HelloEdge_106, nil

	case consts.Safari:
		return utls.HelloSafari_Auto, nil
	case consts.Safari16_0:
		return utls.HelloSafari_16_0, nil
	case consts.Safari26_3:
		return utls.HelloSafari_26_3, nil

	case consts.Fp360:
		return utls.Hello360_Auto, nil
	case consts.Fp360_7_5:
		return utls.Hello360_7_5, nil
	case consts.Fp360_11_0:
		return utls.Hello360_11_0, nil

	case consts.QQ:
		return utls.HelloQQ_Auto, nil
	case consts.QQ11_1:
		return utls.HelloQQ_11_1, nil

	case consts.IOS:
		return utls.HelloIOS_Auto, nil
	case consts.IOS12_1:
		return utls.HelloIOS_12_1, nil
	case consts.IOS13:
		return utls.HelloIOS_13, nil
	case consts.IOS14:
		return utls.HelloIOS_14, nil

	case consts.Android:
		return utls.HelloAndroid_11_OkHttp, nil
	case consts.AndroidOkHttpAuto:
		return utls.HelloAndroid_OkHttp_Auto, nil
	case consts.Android11OkHttp:
		return utls.HelloAndroid_11_OkHttp, nil
	case consts.Android16OkHttp:
		return utls.HelloAndroid_16_OkHttp, nil

	case consts.Random:
		return randomFingerprint, nil
	case consts.Randomized:
		return randomizedFingerprint, nil
	default:
		return utls.ClientHelloID{}, E.New("unknown uTLS fingerprint: ", name)
	}
}

func makeDialTLS(c *ctx.Conn, tlsConfig *tls.Config, nextProtos []string) DialTLSContextFunc {
	dialTLS := makeUTLSDialer(c, tlsConfig, nextProtos)
	if dialTLS == nil {
		return nil
	}
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		return dialTLS(ctx, network, addr)
	}
}

func makeHTTP2DialTLS(c *ctx.Conn, tlsConfig *tls.Config, nextProtos []string) DialHTTP2TLSContextFunc {
	dialTLS := makeUTLSDialer(c, tlsConfig, nextProtos)
	if dialTLS == nil {
		return nil
	}
	return func(ctx context.Context, network, addr string, _ *tls.Config) (net.Conn, error) {
		conn, err := dialTLS(ctx, network, addr)
		if err != nil {
			return nil, err
		}
		state := conn.(interface {
			ConnectionState() tls.ConnectionState
		}).ConnectionState()
		if state.NegotiatedProtocol != http2.NextProtoTLS {
			_ = conn.Close()
			return nil, E.New("unexpected ALPN protocol ", state.NegotiatedProtocol)
		}
		return conn, nil
	}
}

func makeUTLSDialer(c *ctx.Conn, tlsConfig *tls.Config, nextProtos []string) DialTLSContextFunc {
	if c.UTLS == consts.Invalid {
		return nil
	}

	fp, err := uTLSClientHelloID(c.UTLS)
	if err != nil {
		return nil
	}

	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		rawConn, err := dialForwarder(ctx, network, addr, c)
		if err != nil {
			return nil, err
		}

		host, _, _ := net.SplitHostPort(addr)

		ucfg := &utls.Config{
			ServerName:         host,
			RootCAs:            adapter.RootPoolFromContext(ctx),
			NextProtos:         nextProtos,
			Time:               tlsConfig.Time,
			InsecureSkipVerify: tlsConfig.InsecureSkipVerify,
		}
		if tlsConfig.RootCAs != nil {
			ucfg.RootCAs = tlsConfig.RootCAs
		}

		uconn := utls.UClient(rawConn, ucfg, fp)
		if len(nextProtos) > 0 {
			if err = setUTLSALPN(uconn, nextProtos); err != nil {
				rawConn.Close()
				return nil, err
			}
		}

		if err := uconn.HandshakeContext(ctx); err != nil {
			rawConn.Close()
			return nil, err
		}

		return &utlsConnWrapper{UConn: uconn}, nil
	}
}

func setUTLSALPN(conn *utls.UConn, nextProtos []string) error {
	if err := conn.BuildHandshakeState(); err != nil {
		return err
	}
	for _, extension := range conn.Extensions {
		if alpnExtension, isALPN := extension.(*utls.ALPNExtension); isALPN {
			alpnExtension.AlpnProtocols = nextProtos
			return conn.BuildHandshakeState()
		}
	}
	conn.Extensions = append(conn.Extensions, &utls.ALPNExtension{AlpnProtocols: nextProtos})
	return conn.BuildHandshakeState()
}

type utlsConnWrapper struct {
	*utls.UConn
}

func (c *utlsConnWrapper) ConnectionState() tls.ConnectionState {
	state := c.Conn.ConnectionState()
	return tls.ConnectionState{
		Version:                     state.Version,
		HandshakeComplete:           state.HandshakeComplete,
		DidResume:                   state.DidResume,
		CipherSuite:                 state.CipherSuite,
		NegotiatedProtocol:          state.NegotiatedProtocol,
		NegotiatedProtocolIsMutual:  state.NegotiatedProtocolIsMutual,
		ServerName:                  state.ServerName,
		PeerCertificates:            cloneCertificates(state.PeerCertificates),
		VerifiedChains:              cloneCertificateChains(state.VerifiedChains),
		SignedCertificateTimestamps: state.SignedCertificateTimestamps,
		OCSPResponse:                state.OCSPResponse,
		TLSUnique:                   state.TLSUnique,
	}
}

func cloneCertificates(certificates []*x509.Certificate) []*x509.Certificate {
	if len(certificates) == 0 {
		return nil
	}
	cloned := make([]*x509.Certificate, len(certificates))
	copy(cloned, certificates)
	return cloned
}

func cloneCertificateChains(chains [][]*x509.Certificate) [][]*x509.Certificate {
	if len(chains) == 0 {
		return nil
	}
	cloned := make([][]*x509.Certificate, len(chains))
	for index, chain := range chains {
		cloned[index] = cloneCertificates(chain)
	}
	return cloned
}
