package libcore

import (
	"errors"
	"strings"
	"testing"
)

func TestRunWithPanicErrorReturnsValue(t *testing.T) {
	wantErr := errors.New("test error")
	result, err := runWithPanicError("test operation", func() (int, error) {
		return 42, wantErr
	})
	if result != 42 {
		t.Fatalf("expected result 42, got %d", result)
	}
	if !errors.Is(err, wantErr) {
		t.Fatalf("expected original error, got %v", err)
	}
}

func TestRunWithPanicErrorConvertsPanic(t *testing.T) {
	result, err := runWithPanicError("test operation", func() (int, error) {
		panic("boom")
	})
	if result != 0 {
		t.Fatalf("expected zero result after panic, got %d", result)
	}
	if err == nil || !strings.Contains(err.Error(), "test operation panic: boom") {
		t.Fatalf("expected contextual panic error, got %v", err)
	}
}
