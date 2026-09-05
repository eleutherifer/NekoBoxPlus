package xhttp

import (
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/kmutex"
	Xbadoption "github.com/sagernet/sing-box/common/xray/json/badoption"
	"github.com/sagernet/sing-box/common/xray/signal/done"
	"github.com/sagernet/sing-box/option"
)

type paddingResponseWriter struct {
	header http.Header
	wrote  chan struct{}
	once   sync.Once
}

func (w *paddingResponseWriter) Header() http.Header {
	return w.header
}

func (w *paddingResponseWriter) Write(payload []byte) (int, error) {
	w.once.Do(func() {
		close(w.wrote)
	})
	return len(payload), nil
}

func (w *paddingResponseWriter) WriteHeader(int) {
}

func (w *paddingResponseWriter) Flush() {
}

func TestServerSessionDeleteKeepsReplacement(t *testing.T) {
	server := &Server{
		options:   &option.V2RayXHTTPOptions{},
		sessionMu: kmutex.New[string](),
	}
	original := &httpSession{uploadQueue: NewUploadQueue(1)}
	replacement := &httpSession{uploadQueue: NewUploadQueue(1)}
	server.sessions.Store("session", replacement)
	server.deleteSession("session", original)
	current, loaded := server.sessions.Load("session")
	if !loaded || current != replacement {
		t.Fatal("cleanup removed a replacement session")
	}
	server.deleteSession("session", replacement)
	if _, loaded = server.sessions.Load("session"); loaded {
		t.Fatal("matching session was not removed")
	}
}

func TestServerUpsertSessionIsSingleInstance(t *testing.T) {
	server := &Server{
		options:   &option.V2RayXHTTPOptions{},
		sessionMu: kmutex.New[string](),
	}
	const count = 64
	results := make(chan *httpSession, count)
	var waitGroup sync.WaitGroup
	for range count {
		waitGroup.Go(func() {
			session, err := server.upsertSession("session")
			if err != nil {
				t.Errorf("upsert session: %v", err)
				return
			}
			results <- session
		})
	}
	waitGroup.Wait()
	close(results)
	var expected *httpSession
	for session := range results {
		if expected == nil {
			expected = session
		} else if session != expected {
			t.Fatal("concurrent upsert returned multiple sessions")
		}
	}
	if expected != nil {
		closeSilently(expected.isFullyConnected)
		server.deleteSession("session", expected)
		_ = expected.uploadQueue.Close()
	}
}

func TestValidateXHTTPSessionMode(t *testing.T) {
	tests := []struct {
		name      string
		mode      string
		sessionID string
		wantError bool
	}{
		{name: "auto with session", mode: "auto", sessionID: "session"},
		{name: "stream one without session", mode: "stream-one"},
		{name: "stream up without session", mode: "stream-up"},
		{name: "packet up with session", mode: "packet-up", sessionID: "session"},
		{name: "stream one with session", mode: "stream-one", sessionID: "session", wantError: true},
		{name: "packet up without session", mode: "packet-up", wantError: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := validateXHTTPSessionMode(test.mode, test.sessionID)
			if (err != nil) != test.wantError {
				t.Fatalf("validateXHTTPSessionMode(%q, %q) error = %v, wantError %v", test.mode, test.sessionID, err, test.wantError)
			}
		})
	}
}

func TestShouldWriteStreamUpPadding(t *testing.T) {
	interval := Xbadoption.Range{From: 10, To: 10}
	request, err := http.NewRequestWithContext(t.Context(), http.MethodPost, "https://example.com", nil)
	if err != nil {
		t.Fatal(err)
	}
	options := &option.V2RayXHTTPOptions{}
	if shouldWriteStreamUpPadding(options, request, "padding", interval) {
		t.Fatal("padding enabled without a compatibility marker")
	}
	options.XPaddingObfsMode = true
	if !shouldWriteStreamUpPadding(options, request, "padding", interval) {
		t.Fatal("obfuscated padding did not enable stream-up padding")
	}
	if shouldWriteStreamUpPadding(options, request, "", interval) {
		t.Fatal("empty obfuscated padding enabled stream-up padding")
	}
	options.XPaddingObfsMode = false
	request.Header.Set("Referer", "https://example.com/")
	if !shouldWriteStreamUpPadding(options, request, "", interval) {
		t.Fatal("legacy Referer marker did not enable stream-up padding")
	}
	if shouldWriteStreamUpPadding(options, request, "", Xbadoption.Range{}) {
		t.Fatal("disabled interval enabled stream-up padding")
	}
}

func TestWriteStreamUpPaddingStopsWithConnection(t *testing.T) {
	writer := &paddingResponseWriter{
		header: make(http.Header),
		wrote:  make(chan struct{}),
	}
	conn := &httpServerConn{
		Instance:       done.New(),
		Reader:         http.NoBody,
		ResponseWriter: writer,
	}
	server := &Server{options: &option.V2RayXHTTPOptions{
		V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{
			XPaddingBytes: Xbadoption.Range{From: 1, To: 1},
		},
	}}
	finished := make(chan struct{})
	go func() {
		server.writeStreamUpPadding(conn, Xbadoption.Range{From: 60, To: 60})
		close(finished)
	}()
	select {
	case <-writer.wrote:
	case <-time.After(time.Second):
		t.Fatal("stream-up padding was not written")
	}
	if err := conn.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("stream-up padding loop did not stop with the connection")
	}
}
