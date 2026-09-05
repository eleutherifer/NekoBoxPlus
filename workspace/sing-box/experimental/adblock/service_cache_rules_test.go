//go:build with_adblock

package adblock

import (
	"context"
	"github.com/goccy/go-json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/experimental/adblock/db"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
)

func mustFilterListOption(t *testing.T, url string) option.AdblockFilterList {
	t.Helper()
	var fl option.AdblockFilterList
	if err := json.Unmarshal([]byte(`{"url":"`+url+`"}`), &fl); err != nil {
		t.Fatal(err)
	}
	return fl
}

// TestCachedRestartInstallsAllRuleKinds is the regression test for the cache
// asymmetry. It drives the real startup flow against a primed cache DB:
// prepareFilterList loads the cached bytes, then updateEngine builds the engine
// from them (no network). Previously prepareFilterList parsed the cache and
// applyParsed nilled list.content, so updateEngine re-parsed nil content and
// advanced/companion/html rules were silently lost on every restart — only a
// first run (fresh fetch) installed them. After the fix the cached path
// installs every rule kind just like a first run.
func TestCachedRestartInstallsAllRuleKinds(t *testing.T) {
	const listURL = "https://filters.example/cached.txt"
	content := []byte("! Title: Cached mixed list\n" +
		"||ads.example^\n" +
		"||cdn.example^$removeparam=utm_source\n" +
		"||block.example^$strict3p\n" +
		"||api.example^$xhr,permissions=collapse\n" +
		"example.org##^div\n")

	databasePath := t.TempDir() + "/adblock.db"
	store := db.New(context.Background(), databasePath)
	if err := store.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	if err := store.SaveFilterList(cacheTag(listURL), &adapter.SavedBinary{
		Content:     content,
		LastUpdated: time.Now(),
	}); err != nil {
		t.Fatal(err)
	}

	service := newTestService(context.Background(), option.AdblockOptions{}, nil)
	logFactory, err := log.New(log.Options{Context: context.Background(), DefaultWriter: io.Discard})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = logFactory.Close() })
	service.logger = logFactory.Logger()
	service.store = store
	service.filterLists = []filterList{{
		option:   mustFilterListOption(t, listURL),
		interval: time.Hour,
	}}

	// Startup flow: prime content from cache, then build the engine. shouldUpdate
	// is false (fresh cache), so updateEngine must take the cached (no-fetch) path.
	if err := service.prepareFilterList(0); err != nil {
		t.Fatalf("prepareFilterList: %v", err)
	}
	if err := service.updateEngine(context.Background()); err != nil {
		t.Fatalf("updateEngine: %v", err)
	}

	if service.advanced.empty() {
		t.Error("advanced rules were NOT installed on the cached path (cache asymmetry regression)")
	}
	if service.companion.empty() {
		t.Error("companion rules were NOT installed on the cached path (cache asymmetry regression)")
	}
	if service.htmlFilters.empty() {
		t.Error("html filter rules were NOT installed on the cached path (cache asymmetry regression)")
	}
	if !service.filterLists[0].hasRules {
		t.Error("network rules were NOT installed on the cached path")
	}
}

// TestPrepareFilterListRetainsCachedContent asserts the other half of the fix:
// loading a list from the cache must keep list.content available so the engine
// builder can classify it. Previously applyParsed nilled content here, which
// starved the cached engine build of advanced/companion/html rules.
func TestPrepareFilterListRetainsCachedContent(t *testing.T) {
	const listURL = "https://filters.example/prime.txt"
	content := []byte("||ads.example^\n||cdn.example^$removeparam=utm_source\n")

	databasePath := t.TempDir() + "/adblock.db"
	store := db.New(context.Background(), databasePath)
	if err := store.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	if err := store.SaveFilterList(cacheTag(listURL), &adapter.SavedBinary{
		Content:     content,
		LastUpdated: time.Now(),
	}); err != nil {
		t.Fatal(err)
	}

	service := newTestService(context.Background(), option.AdblockOptions{}, nil)
	service.store = store
	service.filterLists = []filterList{{option: mustFilterListOption(t, listURL)}}

	if err := service.prepareFilterList(0); err != nil {
		t.Fatalf("prepareFilterList: %v", err)
	}

	if len(service.filterLists[0].content) == 0 {
		t.Fatal("cached content must be retained after prepareFilterList so the engine build can classify it")
	}
}

func TestUpdateEngineReloadsStoredContentAfterNotModified(t *testing.T) {
	content := []byte("! Title: Cached\n||ads.example^\n")
	const etag = "test-etag"

	filterServer := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Header.Get("If-None-Match") != etag {
			t.Errorf("missing If-None-Match: %q", request.Header.Get("If-None-Match"))
		}
		writer.WriteHeader(http.StatusNotModified)
	}))
	t.Cleanup(filterServer.Close)

	databasePath := t.TempDir() + "/adblock.db"
	store := db.New(context.Background(), databasePath)
	if err := store.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	if err := store.SaveFilterList(cacheTag(filterServer.URL), &adapter.SavedBinary{
		Content:     content,
		LastUpdated: time.Now().Add(-2 * time.Hour),
		LastEtag:    etag,
	}); err != nil {
		t.Fatal(err)
	}

	service := newTestService(context.Background(), option.AdblockOptions{}, nil)
	logFactory, err := log.New(log.Options{Context: context.Background(), DefaultWriter: io.Discard})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = logFactory.Close() })
	service.logger = logFactory.Logger()
	service.store = store
	service.filterLists = []filterList{{
		option:      mustFilterListOption(t, filterServer.URL),
		interval:    time.Hour,
		lastUpdated: time.Now().Add(-2 * time.Hour),
		lastEtag:    etag,
		hasRules:    true,
	}}

	if err := service.updateEngine(context.Background()); err != nil {
		t.Fatal(err)
	}
	if service.engine == nil {
		t.Fatal("expected engine to be installed")
	}
	if !service.filterLists[0].hasRules {
		t.Fatal("expected stored content to be parsed after 304 response")
	}
	if len(service.filterLists[0].content) != 0 {
		t.Fatal("stored filter content should be released after successful engine install")
	}
}
