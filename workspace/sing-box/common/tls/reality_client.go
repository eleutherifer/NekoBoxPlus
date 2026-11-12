//go:build with_utls

package tls

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/sha512"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	mRand "math/rand"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/debug"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/ntp"
	aTLS "github.com/sagernet/sing/common/tls"

	"github.com/cloudflare/circl/sign/mldsa/mldsa65"
	utls "github.com/metacubex/utls"
	"golang.org/x/crypto/hkdf"
	"golang.org/x/net/http2"
)

var _ ConfigCompat = (*RealityClientConfig)(nil)

type RealityClientConfig struct {
	ctx            context.Context
	uClient        *UTLSClientConfig
	publicKey      []byte
	shortID        [8]byte
	keySharePolicy string
	mldsa65Verify  *mldsa65.PublicKey
}

func NewRealityClient(ctx context.Context, logger logger.ContextLogger, serverAddress string, options option.OutboundTLSOptions) (Config, error) {
	return newRealityClient(ctx, logger, serverAddress, options, false)
}

func newRealityClient(ctx context.Context, logger logger.ContextLogger, serverAddress string, options option.OutboundTLSOptions, allowEmptyServerName bool) (Config, error) {
	if options.UTLS == nil || !options.UTLS.Enabled {
		return nil, E.New("uTLS is required by reality client")
	}
	if options.Spoof != "" || options.SpoofMethod != "" {
		return nil, E.New("spoof is unsupported in reality")
	}

	uClient, err := newUTLSClient(ctx, logger, serverAddress, options, allowEmptyServerName)
	if err != nil {
		return nil, err
	}

	publicKey, err := base64.RawURLEncoding.DecodeString(options.Reality.PublicKey)
	if err != nil {
		return nil, E.Cause(err, "decode public_key")
	}
	if len(publicKey) != 32 {
		return nil, E.New("invalid public_key")
	}
	var shortID [8]byte
	decodedLen, err := hex.Decode(shortID[:], []byte(options.Reality.ShortID))
	if err != nil {
		return nil, E.Cause(err, "decode short_id")
	}
	if decodedLen > 8 {
		return nil, E.New("invalid short_id")
	}

	policy := options.Reality.KeySharePolicy
	if policy != "" && policy != "preserve" && policy != "ensure_hybrid" {
		return nil, E.New("unknown REALITY key_share_policy: ", policy)
	}
	var verify *mldsa65.PublicKey
	if options.Reality.MLDSA65Verify != "" {
		data, err := base64.RawURLEncoding.DecodeString(options.Reality.MLDSA65Verify)
		if err != nil || len(data) != mldsa65.PublicKeySize {
			return nil, E.New("invalid REALITY mldsa65_verify")
		}
		verify = new(mldsa65.PublicKey)
		if err := verify.UnmarshalBinary(data); err != nil {
			return nil, E.Cause(err, "invalid REALITY mldsa65_verify")
		}
	}
	var config Config = &RealityClientConfig{ctx, uClient.(*UTLSClientConfig), publicKey, shortID, policy, verify}
	if options.KernelRx || options.KernelTx {
		if !C.IsLinux {
			return nil, E.New("kTLS is only supported on Linux")
		}
		config = &KTLSClientConfig{
			Config:   config,
			logger:   logger,
			kernelTx: options.KernelTx,
			kernelRx: options.KernelRx,
		}
	}
	return config, nil
}

func (e *RealityClientConfig) ServerName() string {
	return e.uClient.ServerName()
}

func (e *RealityClientConfig) SetServerName(serverName string) {
	e.uClient.SetServerName(serverName)
}

func (e *RealityClientConfig) NextProtos() []string {
	return e.uClient.NextProtos()
}

func (e *RealityClientConfig) SetNextProtos(nextProto []string) {
	e.uClient.SetNextProtos(nextProto)
}

func (e *RealityClientConfig) HandshakeTimeout() time.Duration {
	return e.uClient.HandshakeTimeout()
}

func (e *RealityClientConfig) SetHandshakeTimeout(timeout time.Duration) {
	e.uClient.SetHandshakeTimeout(timeout)
}

func (e *RealityClientConfig) STDConfig() (*STDConfig, error) {
	return nil, E.New("unsupported usage for reality")
}

func (e *RealityClientConfig) Client(conn net.Conn) (Conn, error) {
	return ClientHandshake(context.Background(), conn, e)
}

func (e *RealityClientConfig) ClientHandshake(ctx context.Context, conn net.Conn) (aTLS.Conn, error) {
	verifier := &realityVerifier{
		serverName:    e.uClient.ServerName(),
		mldsa65Verify: e.mldsa65Verify,
	}
	uConfig := e.uClient.config.Clone()
	uConfig.InsecureSkipVerify = true
	uConfig.SessionTicketsDisabled = true
	uConfig.VerifyPeerCertificate = verifier.VerifyPeerCertificate
	uConn := utls.UClient(conn, uConfig, e.uClient.id)
	verifier.UConn = uConn
	err := uConn.BuildHandshakeState()
	if err != nil {
		return nil, err
	}

	if len(uConfig.NextProtos) > 0 {
		for _, extension := range uConn.Extensions {
			if alpnExtension, isALPN := extension.(*utls.ALPNExtension); isALPN {
				alpnExtension.AlpnProtocols = uConfig.NextProtos
				break
			}
		}
	}

	if e.keySharePolicy == "ensure_hybrid" {
		if err := ensureRealityHybrid(uConn); err != nil {
			return nil, err
		}
	}
	if err := uConn.BuildHandshakeState(); err != nil {
		return nil, err
	}
	hello := uConn.HandshakeState.Hello
	hello.SessionId = make([]byte, 32)
	copy(hello.Raw[39:], hello.SessionId)

	var nowTime time.Time
	if uConfig.Time != nil {
		nowTime = uConfig.Time()
	} else {
		nowTime = time.Now()
	}

	hello.SessionId[0] = 26
	hello.SessionId[1] = 11
	hello.SessionId[2] = 9
	binary.BigEndian.PutUint32(hello.SessionId[4:], uint32(nowTime.Unix()))
	copy(hello.SessionId[8:], e.shortID[:])
	if debug.Enabled {
		fmt.Printf("REALITY hello.sessionId[:16]: %v\n", hello.SessionId[:16])
	}
	publicKey, err := ecdh.X25519().NewPublicKey(e.publicKey)
	if err != nil {
		return nil, err
	}
	keyShareKeys := uConn.HandshakeState.State13.KeyShareKeys
	if keyShareKeys == nil {
		return nil, E.New("nil KeyShareKeys")
	}
	ecdheKey := realityAuthPrivateKey(keyShareKeys)
	if ecdheKey == nil {
		return nil, E.New("fingerprint does not provide an X25519 key share")
	}
	authKey, err := ecdheKey.ECDH(publicKey)
	if err != nil {
		return nil, err
	}
	if authKey == nil {
		return nil, E.New("nil auth_key")
	}
	verifier.authKey = authKey
	_, err = hkdf.New(sha256.New, authKey, hello.Random[:20], []byte("REALITY")).Read(authKey)
	if err != nil {
		return nil, err
	}
	aesBlock, _ := aes.NewCipher(authKey)
	aesGcmCipher, _ := cipher.NewGCM(aesBlock)
	aesGcmCipher.Seal(hello.SessionId[:0], hello.Random[20:], hello.SessionId[:16], hello.Raw)
	copy(hello.Raw[39:], hello.SessionId)
	if debug.Enabled {
		fmt.Printf("REALITY hello.sessionId: %v\n", hello.SessionId)
		fmt.Printf("REALITY uConn.AuthKey: %v\n", authKey)
	}

	err = uConn.HandshakeContext(ctx)
	if err != nil {
		return nil, err
	}

	if debug.Enabled {
		fmt.Printf("REALITY Conn.Verified: %v\n", verifier.verified)
	}

	if !verifier.verified {
		go realityClientFallback(e.ctx, uConn, e.uClient.ServerName(), e.uClient.id)
		return nil, E.New("reality verification failed")
	}

	return &realityClientConnWrapper{uConn}, nil
}

func realityAuthPrivateKey(keys *utls.KeySharePrivateKeys) *ecdh.PrivateKey {
	if keys == nil {
		return nil
	}
	if keys.Ecdhe != nil && keys.Ecdhe.Curve() == ecdh.X25519() {
		return keys.Ecdhe
	}
	if keys.MlkemEcdhe != nil && keys.MlkemEcdhe.Curve() == ecdh.X25519() {
		return keys.MlkemEcdhe
	}
	return nil
}

func realityClientFallback(ctx context.Context, uConn net.Conn, serverName string, fingerprint utls.ClientHelloID) {
	defer uConn.Close()
	client := &http.Client{
		Transport: &http2.Transport{
			DialTLSContext: func(ctx context.Context, network, addr string, config *tls.Config) (net.Conn, error) {
				return uConn, nil
			},
			TLSClientConfig: &tls.Config{
				Time:    ntp.TimeFuncFromContext(ctx),
				RootCAs: adapter.RootPoolFromContext(ctx),
			},
		},
	}
	serverAddress := M.ParseSocksaddrHostPort(serverName, 0)
	host := serverAddress.AddrString()
	if serverAddress.IsIPv6() {
		host = "[" + host + "]"
	}
	request, err := http.NewRequest("GET", "https://"+host, nil)
	if err != nil {
		return
	}
	request.Header.Set("User-Agent", fingerprint.Client)
	request.AddCookie(&http.Cookie{Name: "padding", Value: strings.Repeat("0", mRand.Intn(32)+30)})
	response, err := client.Do(request)
	if err != nil {
		return
	}
	_, _ = io.Copy(io.Discard, response.Body)
	response.Body.Close()
}

func (e *RealityClientConfig) Clone() Config {
	return &RealityClientConfig{
		e.ctx,
		e.uClient.Clone().(*UTLSClientConfig),
		e.publicKey,
		e.shortID,
		e.keySharePolicy,
		e.mldsa65Verify,
	}
}

type realityVerifier struct {
	*utls.UConn
	serverName    string
	authKey       []byte
	verified      bool
	mldsa65Verify *mldsa65.PublicKey
}

func (c *realityVerifier) VerifyPeerCertificate(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
	certs := make([]*x509.Certificate, len(rawCerts))
	if len(certs) == 0 {
		return E.New("empty REALITY certificate chain")
	}
	for i, raw := range rawCerts {
		cert, err := x509.ParseCertificate(raw)
		if err != nil {
			return err
		}
		certs[i] = cert
	}
	if pub, ok := certs[0].PublicKey.(ed25519.PublicKey); ok {
		h := hmac.New(sha512.New, c.authKey)
		h.Write(pub)
		if hmac.Equal(h.Sum(nil), certs[0].Signature) {
			if c.mldsa65Verify != nil {
				h.Write(c.HandshakeState.Hello.Raw)
				h.Write(c.HandshakeState.ServerHello.Raw)
				valid := false
				for _, ext := range certs[0].Extensions {
					if ext.Id.Equal([]int{0, 0}) && mldsa65.Verify(c.mldsa65Verify, h.Sum(nil), nil, ext.Value) {
						valid = true
						break
					}
				}
				if !valid {
					return E.New("REALITY ML-DSA-65 verification failed")
				}
			}
			c.verified = true
			return nil
		}
	}
	opts := x509.VerifyOptions{
		DNSName:       c.serverName,
		Intermediates: x509.NewCertPool(),
	}
	for _, cert := range certs[1:] {
		opts.Intermediates.AddCert(cert)
	}
	if _, err := certs[0].Verify(opts); err != nil {
		return err
	}
	return nil
}

type realityClientConnWrapper struct {
	*utls.UConn
}

func (c *realityClientConnWrapper) ConnectionState() tls.ConnectionState {
	state := c.Conn.ConnectionState()
	//nolint:staticcheck
	return tls.ConnectionState{
		Version:                     state.Version,
		HandshakeComplete:           state.HandshakeComplete,
		DidResume:                   state.DidResume,
		CipherSuite:                 state.CipherSuite,
		NegotiatedProtocol:          state.NegotiatedProtocol,
		NegotiatedProtocolIsMutual:  state.NegotiatedProtocolIsMutual,
		ServerName:                  state.ServerName,
		PeerCertificates:            state.PeerCertificates,
		VerifiedChains:              state.VerifiedChains,
		SignedCertificateTimestamps: state.SignedCertificateTimestamps,
		OCSPResponse:                state.OCSPResponse,
		TLSUnique:                   state.TLSUnique,
	}
}

func (c *realityClientConnWrapper) Upstream() any {
	return c.UConn
}

// Due to low implementation quality, the reality server intercepted half close and caused memory leaks.
// We fixed it by calling Close() directly.
func (c *realityClientConnWrapper) CloseWrite() error {
	return c.Close()
}

func (c *realityClientConnWrapper) ReaderReplaceable() bool {
	return true
}

func (c *realityClientConnWrapper) WriterReplaceable() bool {
	return false
}

// ensureRealityHybrid updates only key exchange extensions, before authenticating the hello.
func ensureRealityHybrid(conn *utls.UConn) error {
	var shares *utls.KeyShareExtension
	var groups *utls.SupportedCurvesExtension
	for _, ext := range conn.Extensions {
		switch ext := ext.(type) {
		case *utls.KeyShareExtension:
			shares = ext
		case *utls.SupportedCurvesExtension:
			groups = ext
		}
	}
	if shares == nil || groups == nil {
		return E.New("REALITY hybrid adaptation requires TLS 1.3 key shares and supported groups")
	}
	keys := conn.HandshakeState.State13.KeyShareKeys
	if keys == nil {
		return E.New("missing REALITY key share keys")
	}
	var hybrid utls.KeyShare
	for _, share := range shares.KeyShares {
		if share.Group == utls.X25519MLKEM768 {
			hybrid = share
			break
		}
	}
	if hybrid.Data == nil {
		var err error
		hybrid, err = keys.GenerateRealityHybridKeyShare()
		if err != nil {
			return err
		}
	}
	updated := []utls.KeyShare{hybrid}
	for _, share := range shares.KeyShares {
		if share.Group != utls.X25519MLKEM768 {
			updated = append(updated, share)
		}
	}
	shares.KeyShares = updated
	curves := []utls.CurveID{utls.X25519MLKEM768}
	for _, group := range groups.Curves {
		if group != utls.X25519MLKEM768 {
			curves = append(curves, group)
		}
	}
	groups.Curves = curves
	return nil
}
