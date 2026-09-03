package muxcool

import (
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// pipeDialer is a test N.Dialer that hands out the client end of a pre-created
// net.Pipe. The server end is driven by the test as a minimal mux.cool server.
type pipeDialer struct {
	conn net.Conn
	once sync.Once
	used bool
}

type shortWriter struct {
	bytes.Buffer
	chunk int
}

func (w *shortWriter) Write(p []byte) (int, error) {
	if len(p) > w.chunk {
		p = p[:w.chunk]
	}
	return w.Buffer.Write(p)
}

func (d *pipeDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	var conn net.Conn
	d.once.Do(func() {
		conn = d.conn
		d.used = true
	})
	if conn == nil {
		return nil, io.ErrClosedPipe
	}
	return conn, nil
}

func (d *pipeDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, io.ErrClosedPipe
}

// writeFrame serializes a full mux.cool frame (meta + optional single data
// segment) into w. It is the test-server counterpart to worker.writeRawMeta.
func writeFrame(w io.Writer, meta *FrameMetadata, network TargetNetwork, includeUDPAddr bool, data []byte) error {
	var body bytes.Buffer
	if err := meta.writeMetaTo(&body, network, includeUDPAddr); err != nil {
		return err
	}
	var out bytes.Buffer
	var lenBuf [2]byte
	binary.BigEndian.PutUint16(lenBuf[:], uint16(body.Len()))
	out.Write(lenBuf[:])
	out.Write(body.Bytes())
	if data != nil {
		meta.Option |= OptionData
		// Re-encode meta if Option changed (data presence flips the OptionData
		// bit, which is legal but harmless to leave as written above).
		var dataLen [2]byte
		binary.BigEndian.PutUint16(dataLen[:], uint16(len(data)))
		out.Write(dataLen[:])
		out.Write(data)
	}
	_, err := w.Write(out.Bytes())
	return err
}

// runEchoServer drives a minimal mux.cool server on serverConn. For every
// Keep+OptionData frame received it echoes the payload back to the same
// session id. It returns when the connection is closed.
func runEchoServer(t *testing.T, serverConn net.Conn) {
	defer serverConn.Close()
	type sessInfo struct {
		udp bool
	}
	sessions := make(map[uint16]*sessInfo)
	for {
		meta, err := readMeta(serverConn)
		if err != nil {
			if err != io.EOF {
				t.Logf("server read meta: %v", err)
			}
			return
		}
		switch meta.SessionStatus {
		case SessionStatusNew:
			// Discard any inline data (client sends New without data).
			if optionHas(meta.Option, OptionData) {
				if _, err := readData(serverConn); err != nil {
					t.Logf("server read new data: %v", err)
					return
				}
			}
			// Determine session network from the address presence: the client's
			// New frame always carries an address; we recorded udp via a
			// separate heuristic by peeking would be complex, so instead infer
			// from the target network byte that the server cannot see directly.
			// For the test we treat sessions as TCP unless registered as UDP by
			// the caller via the meta's GlobalID sentinel. To keep the test
			// self-contained, default to TCP here; UDP correctness is exercised
			// by inspecting the wire in TestMuxCoolWireFormat.
			sessions[meta.SessionID] = &sessInfo{udp: false}
		case SessionStatusKeep:
			if !optionHas(meta.Option, OptionData) {
				continue
			}
			data, err := readData(serverConn)
			if err != nil {
				t.Logf("server read keep data: %v", err)
				return
			}
			info := sessions[meta.SessionID]
			udp := info != nil && info.udp
			// Echo back as a Keep+OptionData frame on the same session.
			resp := &FrameMetadata{
				SessionID:     meta.SessionID,
				SessionStatus: SessionStatusKeep,
				Option:        OptionData,
				Target:        meta.Target,
			}
			if err := writeFrame(serverConn, resp, TargetNetworkTCP, udp, data); err != nil {
				t.Logf("server write echo: %v", err)
				return
			}
		case SessionStatusEnd:
			delete(sessions, meta.SessionID)
			if optionHas(meta.Option, OptionData) {
				if _, err := readData(serverConn); err != nil {
					return
				}
			}
		case SessionStatusKeepAlive:
			if optionHas(meta.Option, OptionData) {
				if _, err := readData(serverConn); err != nil {
					return
				}
			}
		}
	}
}

func newTestClient(t *testing.T) (*Client, *pipeDialer, func()) {
	clientConn, serverConn := net.Pipe()
	dialer := &pipeDialer{conn: clientConn}
	client, err := NewClientWithOptions(dialer, logger.NOP(), option.OutboundMultiplexOptions{
		Enabled:  true,
		Protocol: "mux.cool",
	})
	if err != nil {
		t.Fatalf("NewClientWithOptions: %v", err)
	}
	done := make(chan struct{})
	go func() {
		runEchoServer(t, serverConn)
		close(done)
	}()
	cleanup := func() {
		_ = client.Close()
		// Closing the client closes the underlying conn, which unblocks the
		// server read loop.
		<-done
	}
	return client, dialer, cleanup
}

func TestMuxCoolClientTCP(t *testing.T) {
	client, _, cleanup := newTestClient(t)
	defer cleanup()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	conn, err := client.DialContext(ctx, N.NetworkTCP, M.Socksaddr{Fqdn: "example.com", Port: 80})
	if err != nil {
		t.Fatalf("DialContext: %v", err)
	}
	defer conn.Close()

	payload := []byte("hello mux.cool")
	if _, err := conn.Write(payload); err != nil {
		t.Fatalf("Write: %v", err)
	}

	buf := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, buf); err != nil {
		t.Fatalf("Read: %v", err)
	}
	if !bytes.Equal(buf, payload) {
		t.Fatalf("echo mismatch: got %q want %q", buf, payload)
	}
}

func TestMuxCoolClientUDP(t *testing.T) {
	client, _, cleanup := newTestClient(t)
	defer cleanup()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	pconn, err := client.ListenPacket(ctx, M.Socksaddr{Fqdn: "dns.example", Port: 53})
	if err != nil {
		t.Fatalf("ListenPacket: %v", err)
	}
	defer pconn.Close()

	payload := []byte("mux.cool udp packet")
	if _, err := pconn.WriteTo(payload, M.Socksaddr{Fqdn: "dns.example", Port: 53}); err != nil {
		t.Fatalf("WriteTo: %v", err)
	}

	buf := make([]byte, len(payload))
	n, _, err := pconn.ReadFrom(buf)
	if err != nil {
		t.Fatalf("ReadFrom: %v", err)
	}
	if !bytes.Equal(buf[:n], payload) {
		t.Fatalf("udp echo mismatch: got %q want %q", buf[:n], payload)
	}
}

func TestMuxCoolClientTCPOutlivesDialContext(t *testing.T) {
	client, _, cleanup := newTestClient(t)
	defer cleanup()

	ctx, cancel := context.WithCancel(t.Context())
	conn, err := client.DialContext(ctx, N.NetworkTCP, M.Socksaddr{Fqdn: "example.com", Port: 80})
	if err != nil {
		t.Fatalf("DialContext: %v", err)
	}
	cancel()

	payload := []byte("hello after dial context cancellation")
	if _, err = conn.Write(payload); err != nil {
		t.Fatalf("Write after dial context cancellation: %v", err)
	}
	response := make([]byte, len(payload))
	if _, err = io.ReadFull(conn, response); err != nil {
		t.Fatalf("Read after dial context cancellation: %v", err)
	}
	if !bytes.Equal(response, payload) {
		t.Fatalf("echo mismatch: got %q want %q", response, payload)
	}
	if err = conn.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if size := client.workers[0].sessionManager.size(); size != 0 {
		t.Fatalf("session count after close = %d, want 0", size)
	}
}

func TestMuxCoolClientUDPOutlivesDialContext(t *testing.T) {
	client, _, cleanup := newTestClient(t)
	defer cleanup()

	ctx, cancel := context.WithCancel(t.Context())
	packetConn, err := client.ListenPacket(ctx, M.Socksaddr{Fqdn: "dns.example", Port: 53})
	if err != nil {
		t.Fatalf("ListenPacket: %v", err)
	}
	cancel()

	payload := []byte("packet after dial context cancellation")
	if _, err = packetConn.WriteTo(payload, M.Socksaddr{Fqdn: "dns.example", Port: 53}); err != nil {
		t.Fatalf("WriteTo after dial context cancellation: %v", err)
	}
	response := make([]byte, len(payload))
	n, _, err := packetConn.ReadFrom(response)
	if err != nil {
		t.Fatalf("ReadFrom after dial context cancellation: %v", err)
	}
	if !bytes.Equal(response[:n], payload) {
		t.Fatalf("echo mismatch: got %q want %q", response[:n], payload)
	}
	if err = packetConn.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if size := client.workers[0].sessionManager.size(); size != 0 {
		t.Fatalf("session count after close = %d, want 0", size)
	}
}

func TestMuxCoolClientDisabledByDefault(t *testing.T) {
	c, err := NewClientWithOptions(&pipeDialer{}, logger.NOP(), option.OutboundMultiplexOptions{Enabled: false})
	if err != nil {
		t.Fatalf("NewClientWithOptions: %v", err)
	}
	if c != nil {
		t.Fatalf("expected nil client when disabled")
	}
}

func TestMuxCoolWireFormatNewTCP(t *testing.T) {
	// Verify the exact bytes of a TCP New frame against the documented
	// mux.cool wire layout: [len][sid][status=1][opt=0][net=1][port][addr].
	clientConn, serverConn := net.Pipe()
	dialer := &pipeDialer{conn: clientConn}
	client, err := NewClientWithOptions(dialer, logger.NOP(), option.OutboundMultiplexOptions{
		Enabled:  true,
		Protocol: "mux.cool",
	})
	if err != nil {
		t.Fatalf("NewClientWithOptions: %v", err)
	}
	defer client.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	type dialResult struct {
		conn net.Conn
		err  error
	}
	resultCh := make(chan dialResult, 1)
	go func() {
		conn, err := client.DialContext(ctx, N.NetworkTCP, M.Socksaddr{Fqdn: "x.com", Port: 443})
		resultCh <- dialResult{conn, err}
	}()

	// Read the New frame header from the server side concurrently, so the
	// client's write of the frame is not blocked on the pipe.
	var lenBuf [2]byte
	if _, err := io.ReadFull(serverConn, lenBuf[:]); err != nil {
		t.Fatalf("read metalen: %v", err)
	}
	metaLen := binary.BigEndian.Uint16(lenBuf[:])
	body := make([]byte, metaLen)
	if _, err := io.ReadFull(serverConn, body); err != nil {
		t.Fatalf("read meta body: %v", err)
	}

	res := <-resultCh
	if res.err != nil {
		t.Fatalf("DialContext: %v", res.err)
	}
	// Drain the server end so the End frame emitted on conn.Close() does not
	// block (the deadline in writeEndFrame is the safety net).
	go io.Copy(io.Discard, serverConn)
	defer res.conn.Close()

	// sid(2) status(1) opt(1) net(1)=1 port(2)=443 addrType(1)=domain len(1)=5 "x.com"
	want := []byte{0x01, 0x00, 0x01, 0x00, 0x01, 0x01, 0xBB, addressTypeDomain, 5, 'x', '.', 'c', 'o', 'm'}
	// sid is assigned by the client (monotonic); normalize it for comparison.
	if len(body) < 2 {
		t.Fatalf("body too short: %d", len(body))
	}
	body[0], body[1] = 0x01, 0x00
	if !bytes.Equal(body, want) {
		t.Fatalf("New TCP meta mismatch:\n got % x\nwant % x", body, want)
	}
}

func TestMuxCoolWireFormatNewUDP(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	dialer := &pipeDialer{conn: clientConn}
	client, err := NewClientWithOptions(dialer, logger.NOP(), option.OutboundMultiplexOptions{
		Enabled:  true,
		Protocol: "mux.cool",
	})
	if err != nil {
		t.Fatalf("NewClientWithOptions: %v", err)
	}
	defer client.Close()

	type dialResult struct{ err error }
	resultCh := make(chan dialResult, 1)
	go func() {
		_, err := client.ListenPacket(context.Background(), M.Socksaddr{Fqdn: "dns.example", Port: 53})
		resultCh <- dialResult{err}
	}()

	var lenBuf [2]byte
	if _, err := io.ReadFull(serverConn, lenBuf[:]); err != nil {
		t.Fatalf("read metalen: %v", err)
	}
	body := make([]byte, binary.BigEndian.Uint16(lenBuf[:]))
	if _, err := io.ReadFull(serverConn, body); err != nil {
		t.Fatalf("read meta body: %v", err)
	}
	if result := <-resultCh; result.err != nil {
		t.Fatalf("ListenPacket: %v", result.err)
	}
	go io.Copy(io.Discard, serverConn)
	defer serverConn.Close()

	// UDP New has no synthetic XUDP Global ID: sid/status/opt/net/port/domain.
	want := []byte{0x01, 0x00, 0x01, 0x00, 0x02, 0x00, 0x35, addressTypeDomain, 11, 'd', 'n', 's', '.', 'e', 'x', 'a', 'm', 'p', 'l', 'e'}
	body[0], body[1] = 0x01, 0x00
	if !bytes.Equal(body, want) {
		t.Fatalf("New UDP meta mismatch:\n got % x\nwant % x", body, want)
	}
}

func TestSessionPreservesQueuedChunks(t *testing.T) {
	s := newSession(1, nil, TargetNetworkTCP, M.Socksaddr{})
	s.deliver([]byte("first"))
	s.deliver([]byte("second"))

	for _, want := range []string{"first", "second"} {
		chunk, ok, err := s.nextChunk(context.Background())
		if err != nil || !ok || string(chunk) != want {
			t.Fatalf("nextChunk() = %q, %v, %v; want %q, true, nil", chunk, ok, err, want)
		}
	}
}

func TestSessionReadHonorsContextCancellation(t *testing.T) {
	s := newSession(1, nil, TargetNetworkTCP, M.Socksaddr{})
	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() {
		_, _, err := s.nextChunk(ctx)
		result <- err
	}()
	cancel()
	select {
	case err := <-result:
		if err != context.Canceled {
			t.Fatalf("nextChunk() error = %v, want context.Canceled", err)
		}
	case <-time.After(time.Second):
		t.Fatal("nextChunk did not return after context cancellation")
	}
}

func TestSessionManagerSkipsLiveIDAfterWrap(t *testing.T) {
	manager := newSessionManager()
	first := newSession(1, nil, TargetNetworkTCP, M.Socksaddr{})
	manager.sessions[1] = first
	manager.nextID = ^uint16(0)

	id, ok := manager.allocate(newSession(0, nil, TargetNetworkTCP, M.Socksaddr{}))
	if !ok || id != 2 || manager.sessions[1] != first {
		t.Fatalf("allocate after wrap = (%d, %t), existing session was replaced: %t", id, ok, manager.sessions[1] != first)
	}
}

func TestWriteAllCompletesPartialWrites(t *testing.T) {
	writer := &shortWriter{chunk: 2}
	if err := writeAll(writer, []byte("mux.cool")); err != nil {
		t.Fatalf("writeAll: %v", err)
	}
	if got := writer.String(); got != "mux.cool" {
		t.Fatalf("writeAll wrote %q, want %q", got, "mux.cool")
	}
}

func TestFrameValidation(t *testing.T) {
	if _, err := parseMetaBody([]byte{0, 1, byte(SessionStatusNew), 0, 99, 0, 80, addressTypeIPv4, 127, 0, 0, 1}); err == nil {
		t.Fatal("parseMetaBody accepted an unknown target network")
	}
	longDomain := string(bytes.Repeat([]byte{'a'}, 256))
	meta := FrameMetadata{SessionID: 1, SessionStatus: SessionStatusNew, Target: M.Socksaddr{Fqdn: longDomain, Port: 443}}
	if err := meta.writeMetaTo(io.Discard, TargetNetworkTCP, false); err == nil {
		t.Fatal("writeMetaTo accepted a domain longer than 255 bytes")
	}
}
