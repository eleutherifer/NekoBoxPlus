//go:build with_adblock

package adblock

import (
	"context"
	"crypto/x509"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestTLSExclusionToken(t *testing.T) {
	token, err := newTLSExclusionToken("EXAMPLE.COM.")
	if err != nil {
		t.Fatal(err)
	}
	secondToken, err := newTLSExclusionToken("example.com")
	if err != nil {
		t.Fatal(err)
	}
	if token == secondToken {
		t.Fatal("tokens should use independent random nonces")
	}
	domain, err := decryptTLSExclusionToken(token)
	if err != nil {
		t.Fatal(err)
	}
	if domain != "example.com" {
		t.Fatalf("domain = %q, want example.com", domain)
	}

	tampered := []byte(token)
	tampered[len(tampered)/2] ^= 1
	if _, err = decryptTLSExclusionToken(string(tampered)); err == nil {
		t.Fatal("tampered token was accepted")
	}
	if _, err = newTLSExclusionToken("not a domain"); err == nil {
		t.Fatal("invalid domain was accepted")
	}
}

func TestHandleTLSExclusionRequest(t *testing.T) {
	service := &Service{}
	token, err := newTLSExclusionToken("example.com")
	if err != nil {
		t.Fatal(err)
	}

	t.Run("success", func(t *testing.T) {
		request := httptest.NewRequest(http.MethodGet, "https://example.com"+tlsExclusionEndpoint+"?token="+url.QueryEscape(token), nil)
		writer := httptest.NewRecorder()
		if !service.handleTLSExclusionRequest(writer, request) {
			t.Fatal("TLS exclusion request was not handled")
		}
		if writer.Code != http.StatusOK || writer.Body.Len() != 0 {
			t.Fatalf("response = %d %q, want empty 200", writer.Code, writer.Body.String())
		}
		if writer.Header().Get("Cache-Control") != "no-store" {
			t.Fatal("missing no-store cache policy")
		}
		if !service.tlsExclusionActive("EXAMPLE.COM.") {
			t.Fatal("domain was not added to the exclusion cache")
		}
	})

	tests := []struct {
		name   string
		method string
		host   string
		query  string
		status int
	}{
		{name: "missing token", method: http.MethodGet, host: "example.com", status: http.StatusBadRequest},
		{name: "wrong host", method: http.MethodGet, host: "other.example", query: "?token=" + url.QueryEscape(token), status: http.StatusBadRequest},
		{name: "wrong method", method: http.MethodPost, host: "example.com", query: "?token=" + url.QueryEscape(token), status: http.StatusMethodNotAllowed},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(test.method, "https://"+test.host+tlsExclusionEndpoint+test.query, nil)
			writer := httptest.NewRecorder()
			if !service.handleTLSExclusionRequest(writer, request) {
				t.Fatal("TLS exclusion request was not handled")
			}
			if writer.Code != test.status {
				t.Fatalf("status = %d, want %d", writer.Code, test.status)
			}
		})
	}
}

func TestTLSExclusionExpiryAndCleanup(t *testing.T) {
	service := &Service{}
	service.tlsExclusions.Store("active.example", time.Now().Add(-tlsExclusionTTL+time.Minute))
	service.tlsExclusions.Store("stale.example", time.Now().Add(-tlsExclusionTTL))
	service.tlsExclusions.Store("invalid.example", "invalid")

	if !service.tlsExclusionActive("active.example") {
		t.Fatal("fresh exclusion is inactive")
	}
	if service.tlsExclusionActive("stale.example") {
		t.Fatal("expired exclusion is active")
	}
	if _, loaded := service.tlsExclusions.Load("stale.example"); loaded {
		t.Fatal("expired exclusion was not deleted on lookup")
	}

	service.cleanupTLSExclusions()
	if _, loaded := service.tlsExclusions.Load("invalid.example"); loaded {
		t.Fatal("invalid exclusion was not removed by cleanup")
	}
	if !service.tlsExclusionActive("active.example") {
		t.Fatal("cleanup removed a fresh exclusion")
	}
}

func TestTLSCertificateErrorIncludesExclusionControls(t *testing.T) {
	service := &Service{}
	certificateError := x509.CertificateInvalidError{Cert: &x509.Certificate{}, Reason: x509.Expired}

	t.Run("HTML", func(t *testing.T) {
		request := httptest.NewRequest(http.MethodGet, "https://example.com/", nil)
		request.Header.Set("Accept", "text/html")
		writer := httptest.NewRecorder()
		requestContext := &adblockRequestContext{
			ctx:        t.Context(),
			writer:     writer,
			request:    request,
			requestURL: mustParseURL(t, "https://example.com/"),
			useTLS:     true,
		}
		if err := service.writeForwardRoundTripError(requestContext, nil, certificateError); !errors.As(err, &certificateError) {
			t.Fatalf("returned error = %v", err)
		}
		body := writer.Body.String()
		if !strings.Contains(body, "I understand the risk, proceed anyway") || !strings.Contains(body, tlsExclusionEndpoint+"?token=") {
			t.Fatalf("TLS exclusion controls missing from HTML: %s", body)
		}
	})

	t.Run("text", func(t *testing.T) {
		request := httptest.NewRequest(http.MethodGet, "https://example.com/", nil)
		request.Header.Set("Accept", "text/plain")
		writer := httptest.NewRecorder()
		requestContext := &adblockRequestContext{
			ctx:        t.Context(),
			writer:     writer,
			request:    request,
			requestURL: mustParseURL(t, "https://example.com/"),
			useTLS:     true,
		}
		_ = service.writeForwardRoundTripError(requestContext, nil, certificateError)
		if !strings.Contains(writer.Body.String(), "To bypass TLS certificate verification for this site for 6 hours, open this URL, then reload the site: https://example.com") {
			t.Fatalf("TLS exclusion URL missing from text response: %s", writer.Body.String())
		}
	})

	t.Run("non TLS error", func(t *testing.T) {
		request := httptest.NewRequest(http.MethodGet, "https://example.com/", nil)
		request.Header.Set("Accept", "text/html")
		writer := httptest.NewRecorder()
		requestContext := &adblockRequestContext{
			ctx:        t.Context(),
			writer:     writer,
			request:    request,
			requestURL: mustParseURL(t, "https://example.com/"),
			useTLS:     true,
		}
		_ = service.writeForwardRoundTripError(requestContext, nil, context.Canceled)
		if strings.Contains(writer.Body.String(), "proceed anyway") {
			t.Fatal("non-TLS error contains exclusion controls")
		}
	})
}

func TestCronetCertificateErrorAllowsTLSExclusion(t *testing.T) {
	tests := []string{
		"cert authority invalid",
		"net::ERR_CERT_AUTHORITY_INVALID",
		"net::ERR_CERT_DATE_INVALID",
		"net::ERR_CERT_COMMON_NAME_INVALID",
	}
	for _, message := range tests {
		t.Run(message, func(t *testing.T) {
			desc := textsForRoundTripError(testTLSNetworkError(message))
			if desc.Heading != "TLS error" || !desc.TLSExclusionAllowed {
				t.Fatalf("unexpected error classification: %+v", desc)
			}
		})
	}
}

func TestCronetCipherMismatchDoesNotAllowTLSExclusion(t *testing.T) {
	desc := textsForRoundTripError(testTLSNetworkError("net::ERR_SSL_VERSION_OR_CIPHER_MISMATCH"))
	if desc.TLSExclusionAllowed {
		t.Fatalf("cipher mismatch unexpectedly allows TLS exclusion: %+v", desc)
	}
}

func TestExcludedDomainUsesInsecureForwarder(t *testing.T) {
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()
	upstreamURL := mustParseURL(t, upstream.URL)

	service := &Service{ctx: t.Context()}
	service.tlsExclusions.Store(upstreamURL.Hostname(), time.Now())
	request := httptest.NewRequest(http.MethodGet, upstream.URL, nil)
	requestContext := &adblockRequestContext{
		ctx:        t.Context(),
		writer:     httptest.NewRecorder(),
		request:    request,
		requestURL: upstreamURL,
		useTLS:     true,
	}
	response, err := service.roundTripForwardedHTTPRequest(requestContext)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", response.StatusCode, http.StatusNoContent)
	}
}

type testTLSNetworkError string

func (e testTLSNetworkError) Error() string { return string(e) }

func (testTLSNetworkError) Timeout() bool { return false }
