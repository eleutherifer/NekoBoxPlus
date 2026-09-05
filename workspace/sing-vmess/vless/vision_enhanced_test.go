package vless

import (
	"bytes"
	"errors"
	"io"
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	"github.com/sagernet/sing/common/logger"
)

func tlsApplicationRecord(payloadLen int) []byte {
	record := make([]byte, 5+payloadLen)
	copy(record, tlsApplicationDataStart)
	record[3] = byte(payloadLen >> 8)
	record[4] = byte(payloadLen)
	for i := 5; i < len(record); i++ {
		record[i] = byte(i)
	}
	return record
}

type captureVectorisedWriter struct {
	writes [][]byte
	err    error
}

func (w *captureVectorisedWriter) WriteVectorised(buffers []*buf.Buffer) error {
	defer buf.ReleaseMulti(buffers)
	w.writes = append(w.writes, bytes.Join(buf.ToSliceMulti(buffers), nil))
	return w.err
}

type captureConn struct {
	bytes.Buffer
	err       error
	readCount int
	closed    bool
}

type fragmentedCaptureConn struct {
	*captureConn
	fragments [][]byte
}

func (c *fragmentedCaptureConn) Read(p []byte) (int, error) {
	c.readCount++
	if len(c.fragments) == 0 {
		return 0, io.EOF
	}
	n := copy(p, c.fragments[0])
	c.fragments[0] = c.fragments[0][n:]
	if len(c.fragments[0]) == 0 {
		c.fragments = c.fragments[1:]
	}
	return n, nil
}

func (c *captureConn) Close() error {
	c.closed = true
	return nil
}
func (c *captureConn) LocalAddr() net.Addr              { return nil }
func (c *captureConn) RemoteAddr() net.Addr             { return nil }
func (c *captureConn) SetDeadline(time.Time) error      { return nil }
func (c *captureConn) SetReadDeadline(time.Time) error  { return nil }
func (c *captureConn) SetWriteDeadline(time.Time) error { return nil }
func (c *captureConn) Read(p []byte) (int, error) {
	c.readCount++
	return c.Buffer.Read(p)
}
func (c *captureConn) Write(p []byte) (int, error) {
	if c.err != nil {
		return 0, c.err
	}
	return c.Buffer.Write(p)
}

func visionPaddingFrame(userUUID [16]byte, command byte, payload []byte) []byte {
	frame := append([]byte(nil), userUUID[:]...)
	frame = append(frame, command, byte(len(payload)>>8), byte(len(payload)), 0, 0)
	return append(frame, payload...)
}

func TestVisionReadKeepsTransportWhenSplicingDisabled(t *testing.T) {
	userUUID := [16]byte{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}
	outerConn := &captureConn{}
	_, _ = outerConn.Buffer.Write(visionPaddingFrame(userUUID, commandPaddingDirect, nil))
	_, _ = outerConn.Buffer.WriteString("stream")
	rawConn := &captureConn{}
	conn := &enhancedVisionConn{
		Conn:                 outerConn,
		reader:               bufio.NewChunkReader(outerConn, xrayChunkSize),
		rawConn:              rawConn,
		logger:               logger.NOP(),
		userUUID:             userUUID,
		withinPaddingBuffers: true,
		remainingContent:     -1,
		remainingPadding:     -1,
	}

	p := make([]byte, 6)
	n, err := conn.Read(p)
	if err != nil {
		t.Fatal(err)
	}
	if string(p[:n]) != "stream" {
		t.Fatalf("unexpected stream payload: %q", p[:n])
	}
	if rawConn.readCount != 0 {
		t.Fatalf("raw transport was read %d times", rawConn.readCount)
	}
	if rawConn.closed {
		t.Fatal("raw transport was closed")
	}
	if conn.directRead || conn.ReaderReplaceable() || conn.UpstreamReader() != nil {
		t.Fatal("direct reader was exposed while splicing was disabled")
	}
}

func TestVisionReadKeepsFragmentedTransportWhenSplicingDisabled(t *testing.T) {
	userUUID := [16]byte{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}
	frame := visionPaddingFrame(userUUID, commandPaddingDirect, nil)
	outerConn := &fragmentedCaptureConn{
		captureConn: &captureConn{},
		fragments: [][]byte{
			frame[:7],
			frame[7:18],
			append(append([]byte(nil), frame[18:]...), []byte("stream")...),
		},
	}
	rawConn := &captureConn{}
	conn := &enhancedVisionConn{
		Conn:                 outerConn,
		reader:               bufio.NewChunkReader(outerConn, xrayChunkSize),
		rawConn:              rawConn,
		logger:               logger.NOP(),
		userUUID:             userUUID,
		withinPaddingBuffers: true,
		remainingContent:     -1,
		remainingPadding:     -1,
	}

	p := make([]byte, 6)
	n, err := io.ReadFull(conn, p)
	if err != nil {
		t.Fatal(err)
	}
	if n != len(p) || string(p) != "stream" {
		t.Fatalf("unexpected stream payload: %q", p[:n])
	}
	if rawConn.readCount != 0 || rawConn.closed {
		t.Fatal("fragmented transport escaped to or closed the raw connection")
	}
	if conn.directRead || conn.ReaderReplaceable() || conn.UpstreamReader() != nil {
		t.Fatal("direct reader was exposed while splicing was disabled")
	}
}

func TestVisionSetDirectConnIgnoredWhenSplicingDisabled(t *testing.T) {
	conn := &enhancedVisionConn{}
	conn.SetDirectConn(&captureConn{})
	if conn.directConn != nil {
		t.Fatal("direct connection was installed while splicing was disabled")
	}
}

func TestVisionReadDirectWhenSplicingEnabled(t *testing.T) {
	userUUID := [16]byte{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}
	outerConn := &captureConn{}
	_, _ = outerConn.Buffer.Write(visionPaddingFrame(userUUID, commandPaddingDirect, nil))
	rawConn := &captureConn{}
	_, _ = rawConn.Buffer.WriteString("raw")
	conn := &enhancedVisionConn{
		Conn:                 outerConn,
		reader:               bufio.NewChunkReader(outerConn, xrayChunkSize),
		input:                bytes.NewReader(nil),
		rawInput:             &bytes.Buffer{},
		rawConn:              rawConn,
		directConn:           rawConn,
		logger:               logger.NOP(),
		userUUID:             userUUID,
		canSplice:            true,
		withinPaddingBuffers: true,
		remainingContent:     -1,
		remainingPadding:     -1,
	}

	p := make([]byte, 3)
	n, err := conn.Read(p)
	if err != nil {
		t.Fatal(err)
	}
	if string(p[:n]) != "raw" {
		t.Fatalf("unexpected direct payload: %q", p[:n])
	}
	if !conn.directRead || !conn.ReaderReplaceable() || conn.UpstreamReader() != rawConn {
		t.Fatal("direct reader was not exposed after an allowed transition")
	}
}

func TestVisionWriteEndsPaddingWhenSplicingDisabled(t *testing.T) {
	initialWriter := &captureVectorisedWriter{}
	conn := &enhancedVisionConn{
		writer:           initialWriter,
		logger:           logger.NOP(),
		isTLS:            true,
		isTLS12orAbove:   true,
		enableXTLS:       true,
		isPadding:        true,
		writeUUID:        true,
		remainingContent: -1,
		remainingPadding: -1,
	}

	if _, err := conn.Write(tlsApplicationRecord(100)); err != nil {
		t.Fatal(err)
	}
	if conn.directWrite || conn.WriterReplaceable() {
		t.Fatal("direct writer was exposed while splicing was disabled")
	}
	if len(initialWriter.writes) != 1 {
		t.Fatalf("expected one padded write, got %d", len(initialWriter.writes))
	}
	if command := initialWriter.writes[0][16]; command != commandPaddingEnd {
		t.Fatalf("unexpected padding command: %d", command)
	}
}

func TestVisionWriteFragmentedRecordStaysFramed(t *testing.T) {
	initialWriter := &captureVectorisedWriter{}
	directConn := &captureConn{}
	conn := &enhancedVisionConn{
		writer:           initialWriter,
		directConn:       directConn,
		logger:           logger.NOP(),
		isTLS:            true,
		isTLS12orAbove:   true,
		enableXTLS:       true,
		canSplice:        true,
		isPadding:        true,
		remainingContent: -1,
		remainingPadding: -1,
	}
	record := tlsApplicationRecord(100)

	for _, fragment := range [][]byte{record[:20], record[20:]} {
		n, err := conn.Write(fragment)
		if err != nil {
			t.Fatal(err)
		}
		if n != len(fragment) {
			t.Fatalf("wrote %d bytes, expected %d", n, len(fragment))
		}
	}
	if conn.directWrite {
		t.Fatal("fragmented record enabled direct write")
	}
	if len(initialWriter.writes) != 2 {
		t.Fatalf("expected two framed writes, got %d", len(initialWriter.writes))
	}
	if directConn.Len() != 0 {
		t.Fatalf("fragmented record wrote %d direct bytes", directConn.Len())
	}
}

func TestVisionWriteDirectTransitionStartsNextWrite(t *testing.T) {
	initialWriter := &captureVectorisedWriter{}
	directConn := &captureConn{}
	conn := &enhancedVisionConn{
		writer:           initialWriter,
		directConn:       directConn,
		logger:           logger.NOP(),
		isTLS:            true,
		isTLS12orAbove:   true,
		enableXTLS:       true,
		canSplice:        true,
		isPadding:        true,
		remainingContent: -1,
		remainingPadding: -1,
	}
	record := append(tlsApplicationRecord(100), tlsApplicationRecord(9000)...)

	n, err := conn.Write(record)
	if err != nil {
		t.Fatal(err)
	}
	if n != len(record) {
		t.Fatalf("wrote %d bytes, expected %d", n, len(record))
	}
	if !conn.directWrite {
		t.Fatal("direct write was not enabled")
	}
	if len(initialWriter.writes) != 1 {
		t.Fatalf("expected one framed transition write, got %d", len(initialWriter.writes))
	}
	if directConn.Len() != 0 {
		t.Fatalf("transition write leaked %d direct bytes", directConn.Len())
	}
	next := []byte("next write")
	n, err = conn.Write(next)
	if err != nil {
		t.Fatal(err)
	}
	if n != len(next) || !bytes.Equal(directConn.Bytes(), next) {
		t.Fatalf("unexpected direct write: n=%d data=%q", n, directConn.Bytes())
	}
}

func TestVisionWriteDirectTransitionWaitsForFramedWrite(t *testing.T) {
	expectedErr := errors.New("framed write failed")
	initialWriter := &captureVectorisedWriter{err: expectedErr}
	directConn := &captureConn{}
	conn := &enhancedVisionConn{
		writer:           initialWriter,
		directConn:       directConn,
		logger:           logger.NOP(),
		isTLS:            true,
		isTLS12orAbove:   true,
		enableXTLS:       true,
		canSplice:        true,
		isPadding:        true,
		remainingContent: -1,
		remainingPadding: -1,
	}
	record := append(tlsApplicationRecord(100), tlsApplicationRecord(9000)...)

	n, err := conn.Write(record)
	if !errors.Is(err, expectedErr) {
		t.Fatalf("expected direct write error, got %v", err)
	}
	if n != 0 {
		t.Fatalf("reported %d bytes written on failed framed transition", n)
	}
	if conn.directWrite || conn.WriterReplaceable() {
		t.Fatal("direct write was exposed after failed framed transition")
	}
	if len(initialWriter.writes) != 1 {
		t.Fatalf("expected one failed framed write, got %d", len(initialWriter.writes))
	}
	if directConn.Len() != 0 {
		t.Fatalf("failed transition leaked %d direct bytes", directConn.Len())
	}
}

func TestVisionPaddingReleasesConsumedInputBuffer(t *testing.T) {
	conn := &enhancedVisionConn{logger: logger.NOP()}
	input := buf.NewSize(16)
	if _, err := input.Write([]byte("payload")); err != nil {
		t.Fatal(err)
	}

	output := conn.padding(input, commandPaddingEnd)
	defer output.Release()

	if input.RawCap() != 0 || input.Len() != 0 {
		t.Fatal("padding did not release consumed input buffer")
	}
	if output.Len() == 0 {
		t.Fatal("padding produced empty output")
	}
	output.Release()
	if output.RawCap() != 0 || output.Len() != 0 {
		t.Fatal("padding output buffer did not release cleanly")
	}
}

func TestVisionUnPaddingFragmentedHeader(t *testing.T) {
	userUUID := [16]byte{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}
	conn := &enhancedVisionConn{
		logger:               logger.NOP(),
		userUUID:             userUUID,
		remainingContent:     -1,
		remainingPadding:     -1,
		paddingHeader:        [5]byte{},
		paddingHeaderLen:     0,
		paddingUUID:          [16]byte{},
		paddingUUIDLen:       0,
		remainingBuffers:     nil,
		withinPaddingBuffers: true,
	}
	payload := []byte("hello")
	padding := []byte{9, 9, 9}
	frame := append(userUUID[:], commandPaddingEnd, 0, byte(len(payload)), 0, byte(len(padding)))
	frame = append(frame, payload...)
	frame = append(frame, padding...)

	if buffers := conn.unPadding(frame[:8]); len(buffers) != 0 {
		buf.ReleaseMulti(buffers)
		t.Fatal("partial UUID produced data")
	}
	if buffers := conn.unPadding(frame[8:18]); len(buffers) != 0 {
		buf.ReleaseMulti(buffers)
		t.Fatal("partial padding header produced data")
	}
	buffers := conn.unPadding(frame[18:])
	defer buf.ReleaseMulti(buffers)
	if len(buffers) != 1 {
		t.Fatalf("expected one payload buffer, got %d", len(buffers))
	}
	if !bytes.Equal(buffers[0].Bytes(), payload) {
		t.Fatalf("unexpected payload: %q", buffers[0].Bytes())
	}
	if conn.remainingContent != 0 || conn.remainingPadding != 0 {
		t.Fatal("padding parser did not consume frame")
	}
}

func TestVisionUnPaddingPlainFragmentMismatchReleasesOwnedBuffer(t *testing.T) {
	userUUID := [16]byte{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}
	conn := &enhancedVisionConn{
		logger:           logger.NOP(),
		userUUID:         userUUID,
		remainingContent: -1,
		remainingPadding: -1,
	}

	if buffers := conn.unPadding(userUUID[:4]); len(buffers) != 0 {
		buf.ReleaseMulti(buffers)
		t.Fatal("partial UUID produced data")
	}
	buffers := conn.unPadding([]byte{99, 98, 97})
	if len(buffers) != 1 {
		t.Fatalf("expected one plain buffer after UUID mismatch, got %d", len(buffers))
	}
	if !bytes.Equal(buffers[0].Bytes(), []byte{0, 1, 2, 3, 99, 98, 97}) {
		t.Fatalf("unexpected plain buffer: %v", buffers[0].Bytes())
	}
	buffers[0].Release()
	if buffers[0].RawCap() != 0 || buffers[0].Len() != 0 {
		t.Fatal("owned plain buffer did not release cleanly")
	}
}
