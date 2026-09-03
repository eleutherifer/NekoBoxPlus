package libcore

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"math/rand"
	"net"
	"net/http"
	"slices"
	"strings"
	"sync"

	utls "github.com/metacubex/utls"
	"golang.org/x/net/http2"
)

const http11 = "http/1.1"

var (
	randomFingerprint     utls.ClientHelloID
	randomizedFingerprint utls.ClientHelloID
)

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

type utlsRoundTripper struct {
	http2       *http2.Transport
	http1       *http.Transport
	originCache sync.Map
}

func newUTLSRoundTripper(client *httpClient) *utlsRoundTripper {
	client.h1h2Transport.ForceAttemptHTTP2 = false
	http1Dialer := client.makeUTLSDialer([]string{http11}, false)
	client.h1h2Transport.DialTLSContext = func(ctx context.Context, network, address string) (net.Conn, error) {
		return http1Dialer(ctx, network, address, nil)
	}
	client.h1h2Transport.TLSNextProto = map[string]func(string, *tls.Conn) http.RoundTripper{}

	return &utlsRoundTripper{
		http1: &client.h1h2Transport,
		http2: &http2.Transport{
			DialTLSContext:  client.makeUTLSDialer([]string{http2.NextProtoTLS, http11}, true),
			TLSClientConfig: &client.tls,
		},
	}
}

func (t *utlsRoundTripper) RoundTrip(request *http.Request) (*http.Response, error) {
	if request.URL == nil || request.URL.Scheme != "https" || !http2Capable(request) {
		return t.http1.RoundTrip(request)
	}
	origin := strings.ToLower(request.URL.Scheme) + "://" + strings.ToLower(request.URL.Host)
	if _, loaded := t.originCache.Load(origin); loaded {
		return t.http1.RoundTrip(request)
	}
	response, err := t.http2.RoundTrip(request)
	if err == nil || !isHTTP2Unavailable(err) {
		return response, err
	}
	t.originCache.Store(origin, struct{}{})
	return t.http1.RoundTrip(request)
}

func (t *utlsRoundTripper) Close() {
	t.http2.CloseIdleConnections()
	t.http1.CloseIdleConnections()
}

func (c *httpClient) makeUTLSDialer(nextProtos []string, requireHTTP2 bool) func(context.Context, string, string, *tls.Config) (net.Conn, error) {
	return func(ctx context.Context, network, address string, _ *tls.Config) (net.Conn, error) {
		rawConn, err := c.dialContext(ctx, network, address)
		if err != nil {
			return nil, err
		}

		host, _, err := net.SplitHostPort(address)
		if err != nil {
			rawConn.Close()
			return nil, err
		}
		fingerprint, err := utlsClientHelloID(c.utlsName)
		if err != nil {
			rawConn.Close()
			return nil, err
		}
		config := utlsConfigFromSTD(&c.tls, host, nextProtos)
		conn := utls.UClient(rawConn, config, fingerprint)
		if err = setUTLSALPN(conn, nextProtos); err != nil {
			rawConn.Close()
			return nil, err
		}
		if err = conn.HandshakeContext(ctx); err != nil {
			rawConn.Close()
			return nil, err
		}
		wrapped := &utlsConnWrapper{UConn: conn}
		if requireHTTP2 && wrapped.ConnectionState().NegotiatedProtocol != http2.NextProtoTLS {
			wrapped.Close()
			return nil, fmt.Errorf("unexpected ALPN protocol %q", wrapped.ConnectionState().NegotiatedProtocol)
		}
		return wrapped, nil
	}
}

func (c *httpClient) dialContext(ctx context.Context, network, address string) (net.Conn, error) {
	if c.h1h2Transport.DialContext != nil {
		return c.h1h2Transport.DialContext(ctx, network, address)
	}
	var dialer net.Dialer
	return dialer.DialContext(ctx, network, address)
}

func utlsConfigFromSTD(config *tls.Config, serverName string, nextProtos []string) *utls.Config {
	return &utls.Config{
		Rand:                  config.Rand,
		Time:                  config.Time,
		RootCAs:               config.RootCAs,
		NextProtos:            nextProtos,
		ServerName:            serverName,
		InsecureSkipVerify:    config.InsecureSkipVerify,
		VerifyPeerCertificate: config.VerifyPeerCertificate,
		MinVersion:            config.MinVersion,
		MaxVersion:            config.MaxVersion,
		CipherSuites:          config.CipherSuites,
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
	return slices.Clone(certificates)
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

func http2Capable(request *http.Request) bool {
	if request.Header.Get("Upgrade") != "" {
		return false
	}
	connection := request.Header.Get("Connection")
	return connection == "" || strings.EqualFold(connection, "close") || strings.EqualFold(connection, "keep-alive")
}

func isHTTP2Unavailable(err error) bool {
	for err != nil {
		message := err.Error()
		if strings.Contains(message, "unexpected ALPN protocol") ||
			strings.Contains(message, "no application protocol") ||
			strings.Contains(message, "unsupported application protocols") ||
			strings.Contains(message, "looked like an HTTP/1.1 header") ||
			strings.Contains(message, "invalid Upgrade request header") ||
			strings.Contains(message, "invalid Connection request header") ||
			strings.Contains(message, "invalid Transfer-Encoding request header") {
			return true
		}
		err = errors.Unwrap(err)
	}
	return false
}

func utlsClientHelloID(name string) (utls.ClientHelloID, error) {
	switch strings.TrimPrefix(strings.ToLower(name), "hello") {
	case "chrome", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk":
		return utls.HelloChrome_Auto, nil
	case "firefox":
		return utls.HelloFirefox_Auto, nil
	case "edge":
		return utls.HelloEdge_Auto, nil
	case "safari":
		return utls.HelloSafari_Auto, nil
	case "360":
		return utls.Hello360_Auto, nil
	case "qq":
		return utls.HelloQQ_Auto, nil
	case "ios":
		return utls.HelloIOS_Auto, nil
	case "android":
		return utls.HelloAndroid_11_OkHttp, nil
	case "random":
		return randomFingerprint, nil
	case "randomized":
		return randomizedFingerprint, nil
	case "golang":
		return utls.HelloGolang, nil
	case "custom":
		return utls.HelloCustom, nil
	case "randomizedalpn":
		return utls.HelloRandomizedALPN, nil
	case "randomizednoalpn":
		return utls.HelloRandomizedNoALPN, nil
	case "firefox_auto":
		return utls.HelloFirefox_Auto, nil
	case "firefox_55":
		return utls.HelloFirefox_55, nil
	case "firefox_56":
		return utls.HelloFirefox_56, nil
	case "firefox_63":
		return utls.HelloFirefox_63, nil
	case "firefox_65":
		return utls.HelloFirefox_65, nil
	case "firefox_99":
		return utls.HelloFirefox_99, nil
	case "firefox_102":
		return utls.HelloFirefox_102, nil
	case "firefox_105":
		return utls.HelloFirefox_105, nil
	case "firefox_120":
		return utls.HelloFirefox_120, nil
	case "firefox_148":
		return utls.HelloFirefox_148, nil
	case "chrome_auto":
		return utls.HelloChrome_Auto, nil
	case "chrome_58":
		return utls.HelloChrome_58, nil
	case "chrome_62":
		return utls.HelloChrome_62, nil
	case "chrome_70":
		return utls.HelloChrome_70, nil
	case "chrome_72":
		return utls.HelloChrome_72, nil
	case "chrome_83":
		return utls.HelloChrome_83, nil
	case "chrome_87":
		return utls.HelloChrome_87, nil
	case "chrome_96":
		return utls.HelloChrome_96, nil
	case "chrome_100":
		return utls.HelloChrome_100, nil
	case "chrome_102":
		return utls.HelloChrome_102, nil
	case "chrome_100_psk":
		return utls.HelloChrome_100_PSK, nil
	case "chrome_112_psk_shuf":
		return utls.HelloChrome_112_PSK_Shuf, nil
	case "chrome_114_padding_psk_shuf":
		return utls.HelloChrome_114_Padding_PSK_Shuf, nil
	case "chrome_115_pq":
		return utls.HelloChrome_115_PQ, nil
	case "chrome_115_pq_psk":
		return utls.HelloChrome_115_PQ_PSK, nil
	case "chrome_120":
		return utls.HelloChrome_120, nil
	case "chrome_120_pq":
		return utls.HelloChrome_120_PQ, nil
	case "chrome_131":
		return utls.HelloChrome_131, nil
	case "chrome_133":
		return utls.HelloChrome_133, nil
	case "chrome_141_ta":
		return utls.HelloChrome_141_TA, nil
	case "chrome_144_ta_pqs":
		return utls.HelloChrome_144_TA_PQS, nil
	case "ios_auto":
		return utls.HelloIOS_Auto, nil
	case "ios_12_1":
		return utls.HelloIOS_12_1, nil
	case "ios_13":
		return utls.HelloIOS_13, nil
	case "ios_14":
		return utls.HelloIOS_14, nil
	case "android_okhttp_auto":
		return utls.HelloAndroid_OkHttp_Auto, nil
	case "android_11_okhttp":
		return utls.HelloAndroid_11_OkHttp, nil
	case "android_16_okhttp":
		return utls.HelloAndroid_16_OkHttp, nil
	case "edge_auto":
		return utls.HelloEdge_Auto, nil
	case "edge_85":
		return utls.HelloEdge_85, nil
	case "edge_106":
		return utls.HelloEdge_106, nil
	case "safari_auto":
		return utls.HelloSafari_Auto, nil
	case "safari_16_0":
		return utls.HelloSafari_16_0, nil
	case "safari_26_3":
		return utls.HelloSafari_26_3, nil
	case "360_auto":
		return utls.Hello360_Auto, nil
	case "360_7_5":
		return utls.Hello360_7_5, nil
	case "360_11_0":
		return utls.Hello360_11_0, nil
	case "qq_auto":
		return utls.HelloQQ_Auto, nil
	case "qq_11_1":
		return utls.HelloQQ_11_1, nil
	default:
		return utls.ClientHelloID{}, fmt.Errorf("unknown uTLS fingerprint: %s", name)
	}
}
