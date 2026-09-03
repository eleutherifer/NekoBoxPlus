package main

import (
	"context"
	"io"
	"log"
	"os"

	F "github.com/sagernet/sing/common/format"
	"github.com/sagernet/sing/common/logger"
)

var _ logger.ContextLogger = (*MyLogger)(nil)

type MyLogger struct {
	*log.Logger
}

func NewMyLogger(writer io.Writer, prefix string) *MyLogger {
	return &MyLogger{
		log.New(writer, prefix, log.Ltime|log.LUTC|log.Lmsgprefix),
	}
}

func (m *MyLogger) Trace(args ...any) {
	m.TraceContext(context.Background(), args...)
}

func (m *MyLogger) Debug(args ...any) {
	m.DebugContext(context.Background(), args...)
}

func (m *MyLogger) Info(args ...any) {
	m.InfoContext(context.Background(), args...)
}

func (m *MyLogger) Warn(args ...any) {
	m.WarnContext(context.Background(), args...)
}

func (m *MyLogger) Error(args ...any) {
	m.ErrorContext(context.Background(), args...)
}

func (m *MyLogger) TraceContext(ctx context.Context, args ...any) {
	text := F.ToString(args...)
	m.Logger.Println("[TRACE] " + text)
}

func (m *MyLogger) DebugContext(ctx context.Context, args ...any) {
	text := F.ToString(args...)
	m.Logger.Println("[DEBUG] " + text)
}

func (m *MyLogger) InfoContext(ctx context.Context, args ...any) {
	text := F.ToString(args...)
	m.Logger.Println("[INFO] " + text)
}

func (m *MyLogger) WarnContext(ctx context.Context, args ...any) {
	text := F.ToString(args...)
	m.Logger.Println("[WARN] " + text)
}

func (m *MyLogger) ErrorContext(ctx context.Context, args ...any) {
	text := F.ToString(args...)
	m.Logger.Println("[ERROR] " + text)
}

func (m *MyLogger) FatalContext(ctx context.Context, args ...any) {
	text := F.ToString(args...)
	m.Logger.Println("[FATAL] " + text)
	os.Exit(1)
}

func (m *MyLogger) PanicContext(ctx context.Context, args ...any) {
	text := F.ToString(args...)
	m.Logger.Println("[PANIC] " + text)
	panic(text)
}
