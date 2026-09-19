package libcore

import (
	"crypto/x509"
	"os"
	"path/filepath"
	_ "unsafe" // for go:linkname

	_ "github.com/sagernet/sing-box/common/certificate"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
)

//go:linkname systemRoots crypto/x509.systemRoots
var systemRoots *x509.CertPool

//go:linkname newChromeIncluded github.com/sagernet/sing-box/common/certificate.newChromeIncluded
func newChromeIncluded() *x509.CertPool

//go:linkname newMozillaIncluded github.com/sagernet/sing-box/common/certificate.newMozillaIncluded
func newMozillaIncluded() *x509.CertPool

const (
	CertGoOrigin int32 = iota
	CertWithUserTrust
	CertMozilla
	CertChrome
)

const customCaFile = "ca.pem"

type StringIterator interface {
	HasNext() bool
	Next() string
	Length() int32
}

func UpdateRootCACerts(certOption int32, certFromJava StringIterator) {
	systemRoots = nil
	sysRoots, _ := x509.SystemCertPool()

	var roots *x509.CertPool
	switch certOption {
	case CertGoOrigin:
		roots = sysRoots
	case CertWithUserTrust:
		roots = x509.NewCertPool()
		if certFromJava != nil {
			for certFromJava.HasNext() {
				cert := certFromJava.Next()
				if !tryAddCert(roots, []byte(cert)) {
					log.Warn("failed to load java cert: ", cert)
				}
			}
		}
	case CertMozilla:
		roots = newMozillaIncluded()
		if roots == nil {
			log.Error("failed to load Mozilla cert")
			roots = sysRoots
		}
	case CertChrome:
		roots = newChromeIncluded()
		if roots == nil {
			log.Error("failed to load Chrome cert")
			roots = sysRoots
		}
	default:
		panic("unknown cert option")
	}

	if C.IsAndroid {
		externalPem, _ := os.ReadFile(filepath.Join(externalAssetsPath, customCaFile))
		if len(externalPem) > 0 {
			if tryAddCert(roots, externalPem) {
				log.Info("loaded external cert")
			} else {
				log.Warn("failed to load external cert")
			}
		}
	}

	systemRoots = roots
}

func tryAddCert(pool *x509.CertPool, raw []byte) bool {
	if pool.AppendCertsFromPEM(raw) {
		return true
	}
	certs, err := x509.ParseCertificates(raw)
	if err != nil {
		return false
	}
	for _, cert := range certs {
		pool.AddCert(cert)
	}
	return true
}

//go:linkname initSystemRoots crypto/x509.initSystemRoots
func initSystemRoots()
