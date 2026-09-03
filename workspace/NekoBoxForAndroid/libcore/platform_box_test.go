package libcore

import (
	"testing"

	sblog "github.com/sagernet/sing-box/log"
)

func TestBoxPlatformLogWriterLevel(t *testing.T) {
	writer := newBoxPlatformLogWriter(sblog.LevelWarn)
	if !writer.allows(sblog.LevelError) {
		t.Fatal("warning threshold must allow errors")
	}
	if writer.allows(sblog.LevelInfo) {
		t.Fatal("warning threshold must reject info")
	}

	writer.SetLevel(sblog.LevelTrace)
	if !writer.allows(sblog.LevelTrace) {
		t.Fatal("updated trace threshold must allow trace")
	}
}

func TestParseLogLevel(t *testing.T) {
	if _, err := parseLogLevel("fatal"); err != nil {
		t.Fatal(err)
	}
	if _, err := parseLogLevel("none"); err == nil {
		t.Fatal("unknown log level must fail")
	}
}
