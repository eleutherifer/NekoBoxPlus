//go:build with_adblock

package adblock

import (
	"errors"
	"mime"
	"net"
	"net/http"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

type adblockResourceType uint8

const (
	adblockResourceTypeBeacon adblockResourceType = iota
	adblockResourceTypeCSP
	adblockResourceTypeDocument
	adblockResourceTypeDTD
	adblockResourceTypeFetch
	adblockResourceTypeFont
	adblockResourceTypeImage
	adblockResourceTypeMedia
	adblockResourceTypeObject
	adblockResourceTypeOther
	adblockResourceTypePing
	adblockResourceTypeScript
	adblockResourceTypeStylesheet
	adblockResourceTypeSubdocument
	adblockResourceTypeWebSocket
	adblockResourceTypeXSLT
	adblockResourceTypeXHR
)

func (t adblockResourceType) engineValue() string {
	switch t {
	case adblockResourceTypeBeacon:
		return "beacon"
	case adblockResourceTypeCSP:
		return "csp_report"
	case adblockResourceTypeDocument:
		return "document"
	case adblockResourceTypeDTD, adblockResourceTypeFetch, adblockResourceTypeXSLT:
		// adblock-rust applies the same network-filter mask to these types as "other".
		return "other"
	case adblockResourceTypeFont:
		return "font"
	case adblockResourceTypeImage:
		return "image"
	case adblockResourceTypeMedia:
		return "media"
	case adblockResourceTypeObject:
		return "object"
	case adblockResourceTypePing:
		return "ping"
	case adblockResourceTypeScript:
		return "script"
	case adblockResourceTypeStylesheet:
		return "stylesheet"
	case adblockResourceTypeSubdocument:
		return "subdocument"
	case adblockResourceTypeWebSocket:
		return "websocket"
	case adblockResourceTypeXHR:
		return "xmlhttprequest"
	default:
		return "other"
	}
}

func httpRequestURL(scheme string, request *http.Request) *url.URL {
	if request.URL.IsAbs() {
		u := *request.URL
		return &u
	}
	host := request.Host
	if host == "" {
		host = request.URL.Host
	}
	u := url.URL{
		Scheme:   scheme,
		Host:     host,
		Path:     request.URL.Path,
		RawPath:  request.URL.RawPath,
		RawQuery: request.URL.RawQuery,
		Fragment: request.URL.Fragment,
	}
	if u.Path == "" {
		u.Path = "/"
	}
	return &u
}

func adblockHTTPFilterURL(scheme string, request *http.Request, requestType string) *url.URL {
	requestURL := httpRequestURL(scheme, request)
	if requestURL == nil {
		return nil
	}
	if requestType != "websocket" {
		return requestURL
	}
	if requestURL.Scheme == "https" {
		requestURL.Scheme = "wss"
	} else {
		requestURL.Scheme = "ws"
	}
	return requestURL
}

func normalizeFakeIPRequestURL(requestURL *url.URL, request *http.Request, metadata adapter.InboundContext) bool {
	if requestURL == nil || !metadata.FakeIP {
		return false
	}
	originHost := fakeIPOriginHost(metadata)
	if originHost == "" {
		return false
	}
	host := requestURL.Host
	if host == "" && request != nil {
		host = request.Host
	}
	if !fakeIPRequestHost(host, metadata) {
		return false
	}
	requestURL.Host = replaceURLHostName(requestURL.Host, originHost, metadata.Destination.Port)
	return true
}

func fakeIPOriginHost(metadata adapter.InboundContext) string {
	if M.IsDomainName(metadata.Domain) {
		return metadata.Domain
	}
	if M.IsDomainName(metadata.Destination.Fqdn) {
		return metadata.Destination.Fqdn
	}
	return ""
}

func fakeIPRequestHost(host string, metadata adapter.InboundContext) bool {
	if host == "" {
		return true
	}
	hostName := host
	if parsedHost, _, err := net.SplitHostPort(host); err == nil {
		hostName = parsedHost
	}
	hostName = strings.Trim(hostName, "[]")
	if metadata.OriginDestination.Addr.IsValid() && hostName == metadata.OriginDestination.Addr.String() {
		return true
	}
	return metadata.Destination.Addr.IsValid() && hostName == metadata.Destination.Addr.String()
}

func replaceURLHostName(host string, hostName string, destinationPort uint16) string {
	if host == "" {
		if destinationPort != 0 && destinationPort != 80 && destinationPort != 443 {
			return net.JoinHostPort(hostName, strconv.Itoa(int(destinationPort)))
		}
		return hostName
	}
	_, port, err := net.SplitHostPort(host)
	if err != nil || port == "" {
		return hostName
	}
	return net.JoinHostPort(hostName, port)
}

func adblockHTTPForwardURL(u *url.URL) error {
	if u == nil {
		return errors.New("url is nil")
	}
	switch strings.ToLower(u.Scheme) {
	case "wss":
		u.Scheme = "https"
	case "ws":
		u.Scheme = "http"
	}

	return nil
}

func writeHTTPResponse(clientConn net.Conn, response *http.Response) error {
	response.Close = true
	response.Header.Del("Transfer-Encoding")
	response.Header.Del("Trailer")
	response.Header.Set("Connection", "close")
	return response.Write(clientConn)
}

func copyHTTPHeader(destination http.Header, source http.Header) {
	for key, values := range source {
		for _, value := range values {
			destination.Add(key, value)
		}
	}
}

func removeHopByHopHeaders(header http.Header) {
	for _, key := range []string{
		"Connection",
		"Keep-Alive",
		"Proxy-Authenticate",
		"Proxy-Authorization",
		"Te",
		"Trailer",
		"Transfer-Encoding",
		"Upgrade",
	} {
		header.Del(key)
	}
}

func adblockRequestType(request *http.Request) string {
	return classifyAdblockRequest(request).engineValue()
}

func classifyAdblockRequest(request *http.Request) adblockResourceType {
	if strings.EqualFold(request.Header.Get("Upgrade"), "websocket") {
		return adblockResourceTypeWebSocket
	}
	requestMediaType := mediaType(request.Header.Get("Content-Type"))
	if requestMediaType == "application/csp-report" {
		return adblockResourceTypeCSP
	}
	if request.Header.Get("Ping-To") != "" || request.Header.Get("Ping-From") != "" {
		return adblockResourceTypePing
	}
	switch strings.ToLower(request.Header.Get("Sec-Fetch-Dest")) {
	case "beacon":
		return adblockResourceTypeBeacon
	case "report":
		return adblockResourceTypeCSP
	case "document":
		return adblockResourceTypeDocument
	case "dtd":
		return adblockResourceTypeDTD
	case "fetch":
		return adblockResourceTypeFetch
	case "font":
		return adblockResourceTypeFont
	case "image", "imageset":
		return adblockResourceTypeImage
	case "audio", "video", "track", "media":
		return adblockResourceTypeMedia
	case "embed", "object":
		return adblockResourceTypeObject
	case "ping":
		return adblockResourceTypePing
	case "script", "serviceworker", "sharedworker", "worker", "audioworklet", "paintworklet":
		return adblockResourceTypeScript
	case "style":
		return adblockResourceTypeStylesheet
	case "fencedframe", "iframe", "frame":
		return adblockResourceTypeSubdocument
	case "websocket":
		return adblockResourceTypeWebSocket
	case "xslt":
		return adblockResourceTypeXSLT
	case "empty":
		return adblockResourceTypeXHR
	}
	accept := strings.ToLower(request.Header.Get("Accept"))
	switch {
	case strings.Contains(accept, "text/event-stream"):
		return adblockResourceTypeXHR
	case strings.Contains(accept, "application/xml-dtd"):
		return adblockResourceTypeDTD
	case strings.Contains(accept, "application/xslt+xml"), strings.Contains(accept, "text/xsl"):
		return adblockResourceTypeXSLT
	case strings.Contains(accept, "text/html"):
		return adblockResourceTypeDocument
	case strings.Contains(accept, "text/css"):
		return adblockResourceTypeStylesheet
	case strings.Contains(accept, "image/"):
		return adblockResourceTypeImage
	case strings.Contains(accept, "audio/"), strings.Contains(accept, "video/"):
		return adblockResourceTypeMedia
	case strings.Contains(accept, "font/"), strings.Contains(accept, "application/font"):
		return adblockResourceTypeFont
	}
	if request.Method == http.MethodOptions {
		return adblockResourceTypeOther
	}
	if inferredType := adblockRequestTypeFromPath(request.URL.Path); inferredType != adblockResourceTypeOther {
		return inferredType
	}
	if request.Method != http.MethodGet && request.Method != http.MethodHead {
		return adblockResourceTypeXHR
	}
	return adblockResourceTypeOther
}

func adblockRequestTypeFromPath(path string) adblockResourceType {
	extension := strings.ToLower(filepath.Ext(path))
	switch extension {
	case ".js", ".mjs":
		return adblockResourceTypeScript
	case ".css":
		return adblockResourceTypeStylesheet
	case ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".avif":
		return adblockResourceTypeImage
	case ".woff", ".woff2", ".ttf", ".otf":
		return adblockResourceTypeFont
	case ".mp3", ".m4a", ".aac", ".ogg", ".wav", ".flac", ".mp4", ".m4v", ".webm", ".mov":
		return adblockResourceTypeMedia
	case ".dtd":
		return adblockResourceTypeDTD
	case ".xsl", ".xslt":
		return adblockResourceTypeXSLT
	}
	if extension == "" && strings.EqualFold(filepath.Base(path), "pagead") {
		return adblockResourceTypeScript
	}
	return adblockResourceTypeOther
}

func adblockRequestTypeFromURL(requestURL string) string {
	parsedURL, err := url.Parse(requestURL)
	if err != nil {
		return "other"
	}
	return adblockRequestTypeFromPath(parsedURL.Path).engineValue()
}

func adblockSourceURL(request *http.Request, fallback *url.URL, requestType string) (*url.URL, error) {
	if referer := request.Referer(); referer != "" {
		return url.Parse(referer)
	}
	if origin := request.Header.Get("Origin"); origin != "" && !strings.EqualFold(origin, "null") {
		return url.Parse(origin)
	}
	return fallback, nil
}

func mediaType(value string) string {
	parsed, _, err := mime.ParseMediaType(value)
	if err != nil {
		return strings.ToLower(strings.TrimSpace(strings.SplitN(value, ";", 2)[0]))
	}
	return strings.ToLower(parsed)
}
