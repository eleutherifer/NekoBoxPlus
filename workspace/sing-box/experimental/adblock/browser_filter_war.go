//go:build with_adblock

package adblock

import (
	"crypto/subtle"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/common/adblock/adblockrust/resources"
)

var (
	webAccessibleResourceEndpoint = "/." + runBlockHash() + "/web_accessible_resources"
	webAccessibleResourceSecret   = sync.OnceValues(randomCSPNonce)
)

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
