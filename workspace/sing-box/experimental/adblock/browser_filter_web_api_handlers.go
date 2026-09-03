//go:build with_adblock

package adblock

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-box/common/adblock/adblockrust/resources"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
)

var (
	webAccessibleResourceEndpoint = "/." + runBlockHash() + "/web_accessible_resources"
	webAccessibleResourceSecret   = sync.OnceValues(randomCSPNonce)
	tlsExclusionEndpoint          = "/." + runBlockHash() + "/tls_exclusion"
	tlsExclusionCipher            = sync.OnceValues(func() (cipher.AEAD, error) {
		key := sha256.Sum256([]byte(runBlockID()))
		block, err := aes.NewCipher(key[:])
		if err != nil {
			return nil, err
		}
		return cipher.NewGCM(block)
	})
)

func newTLSExclusionToken(domain string) (string, error) {
	domain = normalizeTLSExclusionDomain(domain)
	if !M.IsDomainName(domain) {
		return "", E.New("invalid TLS exclusion domain")
	}
	aead, err := tlsExclusionCipher()
	if err != nil {
		return "", err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return "", err
	}
	token := aead.Seal(nonce, nonce, []byte(domain), []byte(tlsExclusionEndpoint))
	return base64.RawURLEncoding.EncodeToString(token), nil
}

func decryptTLSExclusionToken(token string) (string, error) {
	encoded, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		return "", E.Cause(err, "decode TLS exclusion token")
	}
	aead, err := tlsExclusionCipher()
	if err != nil {
		return "", err
	}
	if len(encoded) < aead.NonceSize() {
		return "", E.New("invalid TLS exclusion token")
	}
	nonce, ciphertext := encoded[:aead.NonceSize()], encoded[aead.NonceSize():]
	plaintext, err := aead.Open(nil, nonce, ciphertext, []byte(tlsExclusionEndpoint))
	if err != nil {
		return "", E.Cause(err, "decrypt TLS exclusion token")
	}
	domain := normalizeTLSExclusionDomain(string(plaintext))
	if !M.IsDomainName(domain) {
		return "", E.New("invalid TLS exclusion domain")
	}
	return domain, nil
}

func (s *Service) handleTLSExclusionRequest(writer http.ResponseWriter, request *http.Request) bool {
	if request.URL == nil || request.URL.EscapedPath() != tlsExclusionEndpoint {
		return false
	}
	if request.Method != http.MethodGet {
		writer.Header().Set("Allow", http.MethodGet)
		http.Error(writer, "method not allowed", http.StatusMethodNotAllowed)
		return true
	}
	tokens := request.URL.Query()["token"]
	if len(tokens) != 1 || tokens[0] == "" {
		http.Error(writer, "invalid TLS exclusion token", http.StatusBadRequest)
		return true
	}
	domain, err := decryptTLSExclusionToken(tokens[0])
	requestHostURL, hostErr := url.Parse("//" + request.Host)
	if err != nil || hostErr != nil || normalizeTLSExclusionDomain(requestHostURL.Hostname()) != domain {
		http.Error(writer, "invalid TLS exclusion token", http.StatusBadRequest)
		return true
	}
	s.tlsExclusions.Store(domain, time.Now())
	writer.Header().Set("Cache-Control", "no-store")
	writer.Header().Set("Content-Length", "0")
	writer.WriteHeader(http.StatusOK)
	return true
}

func (s *Service) handleWebAccessibleResourceRequest(writer http.ResponseWriter, request *http.Request) bool {
	if request.URL == nil {
		return false
	}
	name, matched := strings.CutPrefix(request.URL.EscapedPath(), webAccessibleResourceEndpoint+"/")
	if !matched {
		return false
	}
	secret, err := webAccessibleResourceSecret()
	if err != nil || !equalSecret(request.URL.Query().Get("secret"), secret) {
		http.NotFound(writer, request)
		return true
	}
	if request.Method != http.MethodGet && request.Method != http.MethodHead {
		writer.Header().Set("Allow", "GET, HEAD")
		http.Error(writer, "method not allowed", http.StatusMethodNotAllowed)
		return true
	}
	name, err = url.PathUnescape(name)
	if err != nil || name == "" || strings.ContainsAny(name, "/\\\x00") {
		http.NotFound(writer, request)
		return true
	}
	content, contentType, loaded := resources.GetWebAccessibleResource(name)
	if !loaded {
		http.NotFound(writer, request)
		return true
	}
	writer.Header().Set("Cache-Control", "no-store")
	writer.Header().Set("Content-Length", strconv.Itoa(len(content)))
	writer.Header().Set("Content-Type", contentType)
	writer.Header().Set("X-Content-Type-Options", "nosniff")
	writer.WriteHeader(http.StatusOK)
	if request.Method == http.MethodGet {
		_, _ = writer.Write(content)
	}
	return true
}

func equalSecret(value string, expected string) bool {
	return len(value) == len(expected) && subtle.ConstantTimeCompare([]byte(value), []byte(expected)) == 1
}
