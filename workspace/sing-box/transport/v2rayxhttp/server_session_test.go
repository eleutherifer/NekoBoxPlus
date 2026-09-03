package xhttp

import (
	"sync"
	"testing"

	"github.com/sagernet/sing-box/common/kmutex"
	"github.com/sagernet/sing-box/option"
)

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
