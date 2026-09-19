//go:build with_adblock

package adblock

import (
	"context"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"syscall"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/dns"
)

const (
	dnsRcodeServerFailure  dns.RcodeError = mDNS.RcodeServerFailure
	dnsRcodeNotImplemented dns.RcodeError = mDNS.RcodeNotImplemented
)

type roundTripErrorTexts struct {
	Heading            string
	TitleHumanReadable string
	Description        string
}

func textsForRoundTripError(err error) roundTripErrorTexts {
	if err == nil {
		return roundTripErrorTexts{
			Heading:            "Proxy error",
			TitleHumanReadable: "Request failed",
			Description:        "The proxy could not complete the request.",
		}
	}

	// http.Client / Transport commonly wraps failures in *url.Error.
	var urlErr *url.Error
	if errors.As(err, &urlErr) {
		err = urlErr.Err
	}

	// Request cancellation / deadline.
	if errors.Is(err, context.Canceled) {
		return roundTripErrorTexts{
			Heading:            "Request cancelled",
			TitleHumanReadable: "Request cancelled",
			Description:        "The request was cancelled before the proxy received a complete response from the destination server.",
		}
	}

	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, os.ErrDeadlineExceeded) {
		return roundTripErrorTexts{
			Heading:            "Timeout",
			TitleHumanReadable: "Request timed out",
			Description:        "The destination server did not respond in time, or the connection took too long to establish.",
		}
	}

	// DNS failures.
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) {
		if dnsErr.IsNotFound {
			return roundTripErrorTexts{
				Heading:            "DNS error",
				TitleHumanReadable: "Host not found",
				Description:        "The proxy could not resolve the destination hostname. The domain may not exist, or DNS resolution may be blocked or unavailable.",
			}
		}

		if dnsErr.IsTimeout {
			return roundTripErrorTexts{
				Heading:            "DNS timeout",
				TitleHumanReadable: "DNS lookup timed out",
				Description:        "The proxy could not resolve the destination hostname because the DNS lookup took too long.",
			}
		}

		if dnsErr.IsTemporary {
			return roundTripErrorTexts{
				Heading:            "Temporary DNS error",
				TitleHumanReadable: "Temporary DNS failure",
				Description:        "The proxy could not resolve the destination hostname because of a temporary DNS problem.",
			}
		}

		return roundTripErrorTexts{
			Heading:            "DNS error",
			TitleHumanReadable: "DNS lookup failed",
			Description:        "The proxy could not resolve the destination hostname.",
		}
	}

	// DNS RCODE errors (from custom dns package or miekg/dns responses).
	var rcodeErr dns.RcodeError
	if errors.As(err, &rcodeErr) {
		switch rcodeErr {
		case dns.RcodeNameError:
			return roundTripErrorTexts{
				Heading:            "DNS error",
				TitleHumanReadable: "Domain does not exist",
				Description:        "The DNS server reported that the requested domain name does not exist (NXDOMAIN).",
			}

		case dns.RcodeRefused:
			return roundTripErrorTexts{
				Heading:            "DNS error",
				TitleHumanReadable: "DNS query refused",
				Description:        "The DNS server refused to answer the query.",
			}

		case dns.RcodeFormatError:
			return roundTripErrorTexts{
				Heading:            "DNS error",
				TitleHumanReadable: "Invalid DNS request",
				Description:        "The DNS server reported that the query format was invalid.",
			}

		case dnsRcodeServerFailure:
			return roundTripErrorTexts{
				Heading:            "DNS error",
				TitleHumanReadable: "DNS server failure",
				Description:        "The DNS server encountered an internal failure while processing the request.",
			}

		case dnsRcodeNotImplemented:
			return roundTripErrorTexts{
				Heading:            "DNS error",
				TitleHumanReadable: "DNS operation not supported",
				Description:        "The DNS server does not support the requested operation.",
			}

		default:
			return roundTripErrorTexts{
				Heading:            "DNS error",
				TitleHumanReadable: "DNS lookup failed",
				Description:        fmt.Sprintf("DNS query failed with code: %d", int(rcodeErr)),
			}
		}
	}

	// TLS / certificate failures.
	var certInvalid x509.CertificateInvalidError
	if errors.As(err, &certInvalid) {
		return roundTripErrorTexts{
			Heading:            "TLS error",
			TitleHumanReadable: "Invalid certificate",
			Description:        describeCertificateInvalidError(certInvalid),
		}
	}

	var unknownAuthority x509.UnknownAuthorityError
	if errors.As(err, &unknownAuthority) {
		return roundTripErrorTexts{
			Heading:            "TLS error",
			TitleHumanReadable: "Untrusted certificate",
			Description:        "The destination server presented a certificate that is not trusted by this proxy.",
		}
	}

	var hostnameErr x509.HostnameError
	if errors.As(err, &hostnameErr) {
		return roundTripErrorTexts{
			Heading:            "TLS error",
			TitleHumanReadable: "Certificate name mismatch",
			Description:        "The destination server presented a certificate that is not valid for the requested hostname.",
		}
	}

	var systemRootsErr x509.SystemRootsError
	if errors.As(err, &systemRootsErr) {
		return roundTripErrorTexts{
			Heading:            "TLS error",
			TitleHumanReadable: "Certificate store unavailable",
			Description:        "The proxy could not access the system certificate store needed to verify the destination server certificate.",
		}
	}

	// Connection reset / refused / routing failures.
	if errors.Is(err, syscall.ECONNREFUSED) {
		return roundTripErrorTexts{
			Heading:            "Connection error",
			TitleHumanReadable: "Connection refused",
			Description:        "The destination host rejected the connection attempt.",
		}
	}

	if errors.Is(err, syscall.ECONNRESET) {
		return roundTripErrorTexts{
			Heading:            "Connection error",
			TitleHumanReadable: "Connection reset",
			Description:        "The connection to the destination server was unexpectedly closed.",
		}
	}

	if errors.Is(err, syscall.ECONNABORTED) {
		return roundTripErrorTexts{
			Heading:            "Connection error",
			TitleHumanReadable: "Connection aborted",
			Description:        "The connection to the destination server was aborted before the request completed.",
		}
	}

	if errors.Is(err, syscall.EHOSTUNREACH) {
		return roundTripErrorTexts{
			Heading:            "Network error",
			TitleHumanReadable: "Host unreachable",
			Description:        "The proxy could not reach the destination host.",
		}
	}

	if errors.Is(err, syscall.ENETUNREACH) {
		return roundTripErrorTexts{
			Heading:            "Network error",
			TitleHumanReadable: "Network unreachable",
			Description:        "The proxy could not reach the destination network.",
		}
	}

	if errors.Is(err, syscall.EPIPE) {
		return roundTripErrorTexts{
			Heading:            "Connection error",
			TitleHumanReadable: "Broken connection",
			Description:        "The connection was closed while the proxy was sending or receiving data.",
		}
	}

	// Body/protocol-ish errors that can appear depending on the transport.
	if errors.Is(err, io.ErrUnexpectedEOF) {
		return roundTripErrorTexts{
			Heading:            "Protocol error",
			TitleHumanReadable: "Connection closed early",
			Description:        "The destination server closed the connection before sending a complete response.",
		}
	}

	if errors.Is(err, http.ErrUseLastResponse) {
		return roundTripErrorTexts{
			Heading:            "Proxy error",
			TitleHumanReadable: "Redirect handling failed",
			Description:        "The proxy could not process the response redirect policy.",
		}
	}

	// Generic network error. This must come after the more specific net errors above.
	var netErr net.Error
	if errors.As(err, &netErr) {
		if netErr.Timeout() {
			return roundTripErrorTexts{
				Heading:            "Timeout",
				TitleHumanReadable: "Network timeout",
				Description:        "The network operation took too long to complete.",
			}
		}

		return roundTripErrorTexts{
			Heading:            "Network error",
			TitleHumanReadable: "Network request failed",
			Description:        "The proxy encountered a network error while contacting the destination server.",
		}
	}

	// Some errors only expose useful classification through their text.
	// Keep this small and conservative.
	msg := strings.ToLower(err.Error())

	switch {
	case strings.Contains(msg, "server gave http response to https client"):
		return roundTripErrorTexts{
			Heading:            "Protocol error",
			TitleHumanReadable: "Invalid HTTPS response",
			Description:        "The proxy expected an HTTPS response, but the destination server replied with plain HTTP.",
		}

	case strings.Contains(msg, "malformed http response"):
		return roundTripErrorTexts{
			Heading:            "Protocol error",
			TitleHumanReadable: "Malformed response",
			Description:        "The destination server returned a response that the proxy could not parse.",
		}

	case strings.Contains(msg, "too many redirects"):
		return roundTripErrorTexts{
			Heading:            "Redirect error",
			TitleHumanReadable: "Too many redirects",
			Description:        "The request was redirected too many times before the proxy could receive a final response.",
		}

	case strings.Contains(msg, "unsupported protocol scheme"):
		return roundTripErrorTexts{
			Heading:            "Request error",
			TitleHumanReadable: "Unsupported URL scheme",
			Description:        "The requested URL uses a protocol scheme that this proxy does not support.",
		}
	}

	// Future/uncovered fallback.
	return roundTripErrorTexts{
		Heading:            "Forwarding error",
		TitleHumanReadable: "Request failed",
		Description: fmt.Sprintf(
			"The proxy could not complete this request because of an unexpected error. " +
				"This error type is not specifically recognized yet, but the raw error details are shown below.",
		),
	}
}

func describeCertificateInvalidError(err x509.CertificateInvalidError) string {
	switch err.Reason {
	case x509.Expired:
		return "The destination server certificate has expired or is not yet valid."

	case x509.NotAuthorizedToSign:
		return "The destination server certificate was signed by a certificate that is not authorized to sign certificates."

	case x509.IncompatibleUsage:
		return "The destination server certificate cannot be used for this kind of TLS connection."

	case x509.NameMismatch:
		return "The destination server certificate is not valid for the requested hostname."

	case x509.NameConstraintsWithoutSANs:
		return "The destination server certificate is missing required subject alternative name information."

	case x509.UnconstrainedName:
		return "The destination server certificate violates name constraint requirements."

	case x509.TooManyIntermediates:
		return "The destination server certificate chain contains too many intermediate certificates."

	case x509.CANotAuthorizedForThisName:
		return "The destination server certificate authority is not authorized for this hostname."

	case x509.TooManyConstraints:
		return "The destination server certificate contains too many name constraints."

	case x509.CANotAuthorizedForExtKeyUsage:
		return "The destination server certificate authority is not authorized for the required certificate usage."

	default:
		return "The destination server presented a certificate that could not be verified by this proxy."
	}
}
