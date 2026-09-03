package cachefile

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
)

func TestMASQUEConfigUsesDedicatedBucket(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cache.db")
	cacheFile := New(context.Background(), logger.NOP(), option.CacheFileOptions{
		Enabled: true,
		Path:    path,
	})
	if err := cacheFile.Start(adapter.StartStateInitialize); err != nil {
		t.Fatal(err)
	}
	defer cacheFile.Close()

	tag := "shared-tag"
	masqueConfig := &adapter.SavedBinary{
		Content:     []byte(`{"client_id":"masque"}`),
		LastUpdated: time.Unix(1, 0),
		LastEtag:    "masque",
	}
	ruleSet := &adapter.SavedBinary{
		Content:     []byte("ruleset"),
		LastUpdated: time.Unix(2, 0),
		LastEtag:    "ruleset",
	}

	if err := cacheFile.SaveMASQUEConfig(tag, masqueConfig); err != nil {
		t.Fatal(err)
	}
	if err := cacheFile.SaveRuleSet(tag, ruleSet); err != nil {
		t.Fatal(err)
	}

	savedMASQUEConfig := cacheFile.LoadMASQUEConfig(tag)
	if savedMASQUEConfig == nil {
		t.Fatal("missing MASQUE config")
	}
	if string(savedMASQUEConfig.Content) != string(masqueConfig.Content) {
		t.Fatalf("unexpected MASQUE config content: %q", savedMASQUEConfig.Content)
	}

	savedRuleSet := cacheFile.LoadRuleSet(tag)
	if savedRuleSet == nil {
		t.Fatal("missing rule set")
	}
	if string(savedRuleSet.Content) != string(ruleSet.Content) {
		t.Fatalf("unexpected rule set content: %q", savedRuleSet.Content)
	}
}
