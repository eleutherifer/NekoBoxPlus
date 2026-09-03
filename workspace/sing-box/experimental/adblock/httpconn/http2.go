//go:build with_adblock

package httpconn

import (
	"errors"
	"net/http"
	"strings"
)

var (
	upgradeHeader          = "Upgrade"
	transferEncodingHeader = "Transfer-Encoding"
	connectionHeader       = "Connection"
)

var (
	upgradeValueChunked      = "chunked"
	connectionValueClose     = "close"
	connectionValueKeepAlive = "keep-alive"
)

// http2Capable reports whether req can be sent over an HTTP/2 transport.
//
// HTTP/2 forbids connection-level control headers that are valid in HTTP/1.1,
// most notably the WebSocket Upgrade handshake ("Connection: Upgrade" +
// "Upgrade: websocket"). This mirrors the connection-level header validation
// in golang.org/x/net/http2 (httpcommon.checkConnHeaders); when in doubt we
// prefer HTTP/1.1, since routing an HTTP/2-compatible request there is harmless
// while the reverse wastes a handshake and breaks the request.
func http2Capable(req *http.Request) bool {
	if vv := req.Header[upgradeHeader]; len(vv) > 0 && (vv[0] != "" && vv[0] != upgradeValueChunked) {
		return false
	}
	if vv := req.Header[transferEncodingHeader]; len(vv) > 0 && (len(vv) > 1 || vv[0] != "" && vv[0] != upgradeValueChunked) {
		return false
	}
	if vv := req.Header[connectionHeader]; len(vv) > 0 && (len(vv) > 1 || vv[0] != "" && !strings.EqualFold(vv[0], connectionValueClose) && !strings.EqualFold(vv[0], connectionValueKeepAlive)) {
		return false
	}
	return true
}

// isHTTP2Unavailable reports whether err means HTTP/2 is unavailable or
// inapplicable and that an HTTP/1.1 fallback should be attempted. It covers:
//   - server-side ALPN downgrade (the peer negotiated HTTP/1.1 or no protocol);
//   - the HTTP/1.1-looking-frame error, which fires when the dedicated http2
//     transport cannot verify ALPN for our uTLS connection wrapper and reads an
//     HTTP/1.1 response as HTTP/2 frames;
//   - the connection-level header rejections also handled proactively by
//     http2Capable, kept here as a safety net.
func isHTTP2Unavailable(err error) bool {
	for err != nil {
		errText := err.Error()
		if isHTTP2OriginUnavailableText(errText) ||
			strings.Contains(errText, "invalid Upgrade request header") ||
			strings.Contains(errText, "invalid Connection request header") ||
			strings.Contains(errText, "invalid Transfer-Encoding request header") {
			return true
		}
		err = errors.Unwrap(err)
	}
	return false
}

func isHTTP2OriginUnavailable(err error) bool {
	for err != nil {
		if isHTTP2OriginUnavailableText(err.Error()) {
			return true
		}
		err = errors.Unwrap(err)
	}
	return false
}

func isHTTP2OriginUnavailableText(errText string) bool {
	return strings.Contains(errText, "unexpected ALPN protocol") ||
		strings.Contains(errText, "no application protocol") ||
		strings.Contains(errText, "unsupported application protocols") ||
		strings.Contains(errText, "looked like an HTTP/1.1 header")
}
