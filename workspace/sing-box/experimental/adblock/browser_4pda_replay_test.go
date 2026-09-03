//go:build with_adblock && adblock_replay

package adblock

import (
	"bytes"
	"os"
	"strings"
	"testing"

	"github.com/antchfx/htmlquery"
	"github.com/goccy/go-json"
	"golang.org/x/net/html"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/adblock/adblockrust"
	"github.com/sagernet/sing-box/experimental/adblock/db"
)

func Test4PDASavedPageReplay(t *testing.T) {
	pagePath := replayEnvironment("ADBLOCK_4PDA_PAGE", "../../../Logs/4pda/4PDA.html")
	configPath := replayEnvironment("ADBLOCK_4PDA_CONFIG", "../../../adblock-test-with-filters.json")
	databasePath := replayEnvironment("ADBLOCK_4PDA_DATABASE", "/tmp/adblock-sing-box.db")

	page, err := os.ReadFile(pagePath)
	if err != nil {
		t.Fatal(err)
	}
	configContent, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	var config struct {
		Experimental struct {
			Adblock struct {
				Filters struct {
					Lists []struct {
						URL string `json:"url"`
					} `json:"lists"`
				} `json:"filters"`
			} `json:"adblock"`
		} `json:"experimental"`
	}
	if err = json.Unmarshal(configContent, &config); err != nil {
		t.Fatal(err)
	}
	store := db.New(t.Context(), databasePath)
	if err = store.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	var filters htmlFilterRules
	for _, list := range config.Experimental.Adblock.Filters.Lists {
		saved := store.LoadFilterList(cacheTag(list.URL))
		if saved == nil {
			continue
		}
		filters.merge(parseFilterLines(saved.Content).HTML)
	}
	actions := filters.browserProceduralActions("https://4pda.to/")
	if len(actions) == 0 {
		t.Fatal("cached filters produced no browser snippet actions for 4pda.to")
	}

	document, err := html.Parse(bytes.NewReader(page))
	if err != nil {
		t.Fatal(err)
	}
	matched := 0
	for _, rawAction := range actions {
		var action struct {
			Selector []struct {
				Type string `json:"type"`
				Arg  string `json:"arg"`
			} `json:"selector"`
		}
		if err = json.Unmarshal([]byte(rawAction), &action); err != nil {
			t.Fatal(err)
		}
		for _, selector := range action.Selector {
			if selector.Type == "xpath" {
				matched += len(htmlquery.Find(document, selector.Arg))
			}
		}
	}
	if matched == 0 {
		t.Fatal("4PDA snapshot did not match any generated XPath action")
	}
	payload, changed, err := (browserFilterState{cosmetic: structToCosmeticResources(actions)}).payload("")
	if err != nil {
		t.Fatal(err)
	}
	if !changed || !strings.Contains(string(payload.injection), `"type":"xpath"`) {
		t.Fatal("4PDA XPath actions were not included in the browser injection")
	}
}

func structToCosmeticResources(actions []string) adblockrust.CosmeticResources {
	return adblockrust.CosmeticResources{ProceduralActions: actions}
}

func replayEnvironment(name string, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
