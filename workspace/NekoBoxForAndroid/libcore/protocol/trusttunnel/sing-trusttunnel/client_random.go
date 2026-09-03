package trusttunnel

import (
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"io"
	"strings"

	utls "github.com/refraction-networking/utls"
	E "github.com/sagernet/sing/common/exceptions"
)

const clientRandomLength = 32

type clientRandomSpec struct {
	prefix []byte
	mask   []byte
}

func parseClientRandomSpec(value string) (*clientRandomSpec, error) {
	if value == "" {
		return nil, nil
	}
	prefixHex, maskHex, hasMask := strings.Cut(value, "/")
	if prefixHex == "" {
		return nil, E.New("client random prefix is empty")
	}
	prefix, err := hex.DecodeString(prefixHex)
	if err != nil {
		return nil, E.Cause(err, "decode client random prefix")
	}
	if len(prefix) > clientRandomLength {
		return nil, E.New("client random prefix is too long: ", len(prefix))
	}
	spec := &clientRandomSpec{prefix: prefix}
	if !hasMask {
		return spec, nil
	}
	if maskHex == "" {
		return nil, E.New("client random mask is empty")
	}
	mask, err := hex.DecodeString(maskHex)
	if err != nil {
		return nil, E.Cause(err, "decode client random mask")
	}
	if len(mask) > clientRandomLength {
		return nil, E.New("client random mask is too long: ", len(mask))
	}
	if len(mask) != len(prefix) {
		return nil, E.New("client random prefix and mask length mismatch: ", len(prefix), " != ", len(mask))
	}
	spec.mask = mask
	return spec, nil
}

func (s *clientRandomSpec) Generate() ([]byte, error) {
	if s == nil {
		return nil, nil
	}
	clientRandom := make([]byte, clientRandomLength)
	_, err := rand.Read(clientRandom)
	if err != nil {
		return nil, err
	}
	if len(s.mask) == 0 {
		copy(clientRandom, s.prefix)
		return clientRandom, nil
	}
	for i, prefixByte := range s.prefix {
		maskByte := s.mask[i]
		clientRandom[i] = (clientRandom[i] &^ maskByte) | (prefixByte & maskByte)
	}
	return clientRandom, nil
}

func (s *clientRandomSpec) RandReader() (io.Reader, error) {
	clientRandom, err := s.Generate()
	if err != nil {
		return nil, err
	}
	return &clientRandomReader{first: clientRandom}, nil
}

type clientRandomReader struct {
	first []byte
}

func (r *clientRandomReader) Read(p []byte) (int, error) {
	total := 0
	if len(r.first) > 0 {
		n := copy(p, r.first)
		r.first = r.first[n:]
		p = p[n:]
		total += n
		if len(p) == 0 {
			return total, nil
		}
	}
	n, err := rand.Read(p)
	return total + n, err
}

func utlsConfigFromSTD(config *tls.Config) *utls.Config {
	certificates := make([]utls.Certificate, 0, len(config.Certificates))
	for _, certificate := range config.Certificates {
		signatureAlgorithms := make([]utls.SignatureScheme, 0, len(certificate.SupportedSignatureAlgorithms))
		for _, signatureAlgorithm := range certificate.SupportedSignatureAlgorithms {
			signatureAlgorithms = append(signatureAlgorithms, utls.SignatureScheme(signatureAlgorithm))
		}
		certificates = append(certificates, utls.Certificate{
			Certificate:                  certificate.Certificate,
			PrivateKey:                   certificate.PrivateKey,
			SupportedSignatureAlgorithms: signatureAlgorithms,
			OCSPStaple:                   certificate.OCSPStaple,
			SignedCertificateTimestamps:  certificate.SignedCertificateTimestamps,
			Leaf:                         certificate.Leaf,
		})
	}
	curvePreferences := make([]utls.CurveID, 0, len(config.CurvePreferences))
	for _, curveID := range config.CurvePreferences {
		curvePreferences = append(curvePreferences, utls.CurveID(curveID))
	}
	return &utls.Config{
		Rand:                        config.Rand,
		Time:                        config.Time,
		Certificates:                certificates,
		VerifyPeerCertificate:       config.VerifyPeerCertificate,
		RootCAs:                     config.RootCAs,
		NextProtos:                  config.NextProtos,
		ServerName:                  config.ServerName,
		InsecureSkipVerify:          config.InsecureSkipVerify,
		CipherSuites:                config.CipherSuites,
		SessionTicketsDisabled:      config.SessionTicketsDisabled,
		MinVersion:                  config.MinVersion,
		MaxVersion:                  config.MaxVersion,
		CurvePreferences:            curvePreferences,
		DynamicRecordSizingDisabled: config.DynamicRecordSizingDisabled,
		Renegotiation:               utls.RenegotiationSupport(config.Renegotiation),
		KeyLogWriter:                config.KeyLogWriter,
	}
}
