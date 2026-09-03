package libcore

import (
	"os"
	"path/filepath"
	"sync"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

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

var (
	certificateOptionsAccess sync.RWMutex
	certificateOptions       = option.CertificateOptions{Store: C.CertificateStoreMozilla}
)

// UpdateRootCACerts records the certificate store used by subsequently created boxes.
// sing-box 1.14 owns certificate pools per box, so this intentionally avoids the old
// crypto/x509 and sing-box private-symbol linknames.
func UpdateRootCACerts(certOption int32, certFromJava StringIterator) {
	options := option.CertificateOptions{}
	switch certOption {
	case CertGoOrigin:
		options.Store = C.CertificateStoreSystem
	case CertWithUserTrust:
		options.Store = C.CertificateStoreNone
		if certFromJava != nil {
			for certFromJava.HasNext() {
				options.Certificate = append(options.Certificate, certFromJava.Next())
			}
		}
	case CertMozilla:
		options.Store = C.CertificateStoreMozilla
	case CertChrome:
		options.Store = C.CertificateStoreChrome
	default:
		panic("unknown cert option")
	}
	customCAPath := filepath.Join(externalAssetsPath, customCaFile)
	if fileInfo, err := os.Stat(customCAPath); err == nil && !fileInfo.IsDir() {
		options.CertificatePath = append(options.CertificatePath, customCAPath)
	}
	certificateOptionsAccess.Lock()
	certificateOptions = options
	certificateOptionsAccess.Unlock()
}

func currentCertificateOptions() *option.CertificateOptions {
	certificateOptionsAccess.RLock()
	defer certificateOptionsAccess.RUnlock()
	options := certificateOptions
	options.Certificate = append([]string(nil), options.Certificate...)
	options.CertificatePath = append([]string(nil), options.CertificatePath...)
	return &options
}
