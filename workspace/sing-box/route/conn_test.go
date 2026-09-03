package route

import (
	"errors"
	"io"
	"testing"
)

func TestIsNormalConnectionCloseIncludesIntentionalClientConnClose(t *testing.T) {
	if !isNormalConnectionClose(errors.New("http2: client connection force closed via ClientConn.Close"), 0) {
		t.Fatal("intentional HTTP/2 client connection close should be normal")
	}
}

func TestIsNormalConnectionCloseUnexpectedEOF(t *testing.T) {
	if !isNormalConnectionClose(io.ErrUnexpectedEOF, 1) {
		t.Fatal("unexpected EOF after relaying bytes should be normal")
	}
	if isNormalConnectionClose(io.ErrUnexpectedEOF, 0) {
		t.Fatal("unexpected EOF before relaying bytes should not be normal")
	}
}

func TestIsNormalConnectionCloseRejectsUnrelatedError(t *testing.T) {
	if isNormalConnectionClose(errors.New("read failed"), 1) {
		t.Fatal("unrelated errors should not be normal")
	}
}
