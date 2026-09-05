//go:build with_adblock

package httpconn

import (
	"net/http"
	"net/http/httptest"
	"testing"

	adblockctx "github.com/sagernet/sing-box/experimental/adblock/ctx"
)

func TestStandardForwarderInsecureSkipVerify(t *testing.T) {
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()

	forwarder := NewHTTPForwarder(t.Context(), &adblockctx.Conn{UseTLS: true, InsecureSkipVerify: true})
	defer forwarder.Close()
	request, err := http.NewRequestWithContext(t.Context(), http.MethodGet, upstream.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := forwarder.RoundTrip(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", response.StatusCode, http.StatusNoContent)
	}
}
