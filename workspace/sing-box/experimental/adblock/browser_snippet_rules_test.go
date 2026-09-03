//go:build with_adblock

package adblock

import (
	"strings"
	"testing"

	"github.com/goccy/go-json"
)

func TestBrowserSnippetHideIfMatchesXPath(t *testing.T) {
	const firstXPath = `.//a[@target="_blank"]/img[@alt="" and @title=""]/parent::a/ancestor::*[@id and @class][1]`
	const secondXPath = `.//a[@href][@title][@target="_blank"]/img[@itemprop]/parent::a[@href]`
	parsed := parseFilterLines([]byte(`example.com,~disabled.example.com#$#hide-if-matches-xpath '` + firstXPath + `'; hide-if-matches-xpath '` + secondXPath + `'; abort-on-property-read test;`))

	actions := parsed.HTML.browserProceduralActions("https://www.example.com/page")
	if len(actions) != 2 {
		t.Fatalf("expected two XPath actions, got %d: %v", len(actions), actions)
	}
	for index, xpath := range []string{firstXPath, secondXPath} {
		var action struct {
			Selector []struct {
				Type string `json:"type"`
				Arg  string `json:"arg"`
			} `json:"selector"`
			Action struct {
				Type string `json:"type"`
			} `json:"action"`
		}
		if err := json.Unmarshal([]byte(actions[index]), &action); err != nil {
			t.Fatal(err)
		}
		if len(action.Selector) != 1 || action.Selector[0].Type != "xpath" || action.Selector[0].Arg != xpath || action.Action.Type != "hide-sticky" {
			t.Fatalf("unexpected XPath action: %s", actions[index])
		}
	}
	if actions := parsed.HTML.browserProceduralActions("https://disabled.example.com/"); len(actions) != 0 {
		t.Fatalf("excluded domain received XPath actions: %v", actions)
	}
	if len(parsed.Rules) != 0 {
		t.Fatalf("ABP snippets must not be forwarded as network rules: %v", parsed.Rules)
	}
}

func TestBrowserSnippetXPathException(t *testing.T) {
	const xpath = `.//aside[@class="sponsor"]`
	parsed := parseFilterLines([]byte(strings.Join([]string{
		`example.com#$#hide-if-matches-xpath '` + xpath + `';`,
		`allowed.example.com#@$#hide-if-matches-xpath '` + xpath + `';`,
	}, "\n")))

	if actions := parsed.HTML.browserProceduralActions("https://example.com/"); len(actions) != 1 {
		t.Fatalf("expected parent-domain XPath action: %v", actions)
	}
	if actions := parsed.HTML.browserProceduralActions("https://allowed.example.com/"); len(actions) != 0 {
		t.Fatalf("XPath exception was not applied: %v", actions)
	}
}
