package libcore

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestHTTPRequestContextLivesUntilResponseBodyIsRead(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	}))
	defer server.Close()

	client := NewHttpClient()
	defer client.Close()
	request := client.NewRequest()
	if err := request.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	response, err := request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	content, err := response.GetContentString()
	if err != nil {
		t.Fatal(err)
	}
	if content.Value != "ok" {
		t.Fatalf("expected response body, got %q", content.Value)
	}
}

func TestHTTPRequestCancelInterruptsExecute(t *testing.T) {
	started := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(started)
		<-r.Context().Done()
	}))
	defer server.Close()

	client := NewHttpClient()
	defer client.Close()
	request := client.NewRequest()
	if err := request.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	result := make(chan error, 1)
	go func() {
		_, err := request.Execute()
		result <- err
	}()

	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("request did not start")
	}
	request.Cancel()

	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("expected cancellation, got %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("request cancellation did not interrupt Execute")
	}
}

func TestHTTPRequestCancelBeforeExecute(t *testing.T) {
	request := NewHttpClient().NewRequest()
	request.Cancel()
	request.Cancel()
	_, err := request.Execute()
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected cancellation, got %v", err)
	}
}
