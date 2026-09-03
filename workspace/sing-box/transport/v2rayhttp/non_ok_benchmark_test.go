package v2rayhttp

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func BenchmarkHTTP2NonOKResponseHandling(b *testing.B) {
	payload := make([]byte, 8<<20)
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.WriteHeader(http.StatusServiceUnavailable)
		_, _ = writer.Write(payload)
	}))
	server.EnableHTTP2 = true
	server.StartTLS()
	b.Cleanup(server.Close)
	client := server.Client()

	b.Run("close", func(b *testing.B) {
		b.ReportAllocs()
		b.RunParallel(func(parallel *testing.PB) {
			for parallel.Next() {
				response, err := client.Get(server.URL)
				if err != nil {
					b.Error(err)
					continue
				}
				if err = response.Body.Close(); err != nil {
					b.Error(err)
				}
			}
		})
	})
	b.Run("drain", func(b *testing.B) {
		b.ReportAllocs()
		b.RunParallel(func(parallel *testing.PB) {
			for parallel.Next() {
				response, err := client.Get(server.URL)
				if err != nil {
					b.Error(err)
					continue
				}
				if _, err = io.Copy(io.Discard, response.Body); err != nil {
					response.Body.Close()
					b.Error(err)
					continue
				}
				if err = response.Body.Close(); err != nil {
					b.Error(err)
				}
			}
		})
	})
}
