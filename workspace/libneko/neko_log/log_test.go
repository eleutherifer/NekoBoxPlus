package neko_log

import (
	"bytes"
	"io"
	"testing"
)

func TestSetLogEnabled(t *testing.T) {
	var output bytes.Buffer
	writer := &logWriter{writers: []io.Writer{&output}}

	SetLogEnabled(false)
	if _, err := writer.Write([]byte("disabled")); err != nil {
		t.Fatal(err)
	}
	if output.Len() != 0 {
		t.Fatalf("disabled writer produced %q", output.String())
	}

	SetLogEnabled(true)
	t.Cleanup(func() { SetLogEnabled(true) })
	if _, err := writer.Write([]byte("enabled")); err != nil {
		t.Fatal(err)
	}
	if output.String() != "enabled" {
		t.Fatalf("enabled writer produced %q", output.String())
	}
}
