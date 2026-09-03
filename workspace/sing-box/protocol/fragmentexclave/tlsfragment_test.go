package fragmentexclave

import (
	"bytes"
	"crypto/tls"
	"io"
	"net"
	"testing"
	"time"
)

type recordingConn struct {
	chunks [][]byte
}

func (c *recordingConn) Read([]byte) (int, error) {
	return 0, io.EOF
}

func (c *recordingConn) Write(p []byte) (int, error) {
	chunk := make([]byte, len(p))
	copy(chunk, p)
	c.chunks = append(c.chunks, chunk)
	return len(p), nil
}

func (c *recordingConn) Close() error                     { return nil }
func (c *recordingConn) LocalAddr() net.Addr              { return dummyAddr("local") }
func (c *recordingConn) RemoteAddr() net.Addr             { return dummyAddr("remote") }
func (c *recordingConn) SetDeadline(time.Time) error      { return nil }
func (c *recordingConn) SetReadDeadline(time.Time) error  { return nil }
func (c *recordingConn) SetWriteDeadline(time.Time) error { return nil }

type dummyAddr string

func (a dummyAddr) Network() string { return string(a) }
func (a dummyAddr) String() string  { return string(a) }

func TestTLSFragmentMethods(t *testing.T) {
	serverNames := []string{
		"www.example.com",
		".sslip.io",
		"example..com",
	}
	tests := []struct {
		name        string
		splitRecord bool
		splitPacket bool
	}{
		{name: "record", splitRecord: true},
		{name: "packet", splitPacket: true},
		{name: "record_packet", splitRecord: true, splitPacket: true},
	}
	for _, serverName := range serverNames {
		t.Run(serverName, func(t *testing.T) {
			clientHello := captureClientHello(t, serverName)
			for _, test := range tests {
				t.Run(test.name, func(t *testing.T) {
					recorder := &recordingConn{}
					conn := NewTLSFragmentConn(recorder, test.splitRecord, test.splitPacket)
					n, err := conn.Write(clientHello)
					if err != nil {
						t.Fatal(err)
					}
					if n != len(clientHello) {
						t.Fatalf("written length mismatch: got %d, want %d", n, len(clientHello))
					}
					if len(recorder.chunks) == 0 {
						t.Fatal("expected fragmented output")
					}
					output := bytes.Join(recorder.chunks, nil)
					if test.splitRecord {
						payload := joinTLSRecordPayloads(t, output)
						if !bytes.Equal(payload, clientHello[recordLayerHeaderLen:]) {
							t.Fatal("record-fragmented payload does not reconstruct original ClientHello payload")
						}
					} else if !bytes.Equal(output, clientHello) {
						t.Fatal("packet-fragmented chunks do not reconstruct original ClientHello")
					}
					if test.splitPacket && len(recorder.chunks) < 2 {
						t.Fatal("expected TCP segmentation to write multiple chunks")
					}
				})
			}
		})
	}
}

func TestFragmentServerNameIndexes(t *testing.T) {
	for _, test := range []struct {
		name       string
		serverName string
		want       bool
	}{
		{name: "leading dot", serverName: ".sslip.io", want: true},
		{name: "repeated dot", serverName: "example..com", want: true},
		{name: "only dots", serverName: "..."},
		{name: "empty"},
	} {
		t.Run(test.name, func(t *testing.T) {
			indexes := fragmentServerNameIndexes(test.serverName, 100)
			if (len(indexes) > 0) != test.want {
				t.Fatalf("unexpected fragmentation indexes: %v", indexes)
			}
			for _, index := range indexes {
				if index < 100 || index >= 100+len(test.serverName) {
					t.Fatalf("fragmentation index %d is outside server name", index)
				}
			}
		})
	}
}

func TestIndexTLSServerNameRejectsInvalidLength(t *testing.T) {
	clientHello := captureClientHello(t, "example.com")
	serverName := indexTLSServerName(clientHello)
	clientHello[serverName.index-2] = 0xff
	clientHello[serverName.index-1] = 0xff
	if indexTLSServerName(clientHello) != nil {
		t.Fatal("invalid SNI length should be rejected")
	}
}

func TestTLSFragmentPassThrough(t *testing.T) {
	recorder := &recordingConn{}
	conn := NewTLSFragmentConn(recorder, true, true)
	plain := []byte("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
	n, err := conn.Write(plain)
	if err != nil {
		t.Fatal(err)
	}
	if n != len(plain) {
		t.Fatalf("written length mismatch: got %d, want %d", n, len(plain))
	}
	if len(recorder.chunks) != 1 || !bytes.Equal(recorder.chunks[0], plain) {
		t.Fatal("non-TLS payload should pass through unchanged")
	}
	next := []byte("next")
	if _, err := conn.Write(next); err != nil {
		t.Fatal(err)
	}
	if len(recorder.chunks) != 2 || !bytes.Equal(recorder.chunks[1], next) {
		t.Fatal("subsequent writes should pass through unchanged")
	}
}

func captureClientHello(t *testing.T, serverName string) []byte {
	t.Helper()
	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()
	errCh := make(chan error, 1)
	go func() {
		tlsConn := tls.Client(clientConn, &tls.Config{
			ServerName:         serverName,
			InsecureSkipVerify: true,
		})
		errCh <- tlsConn.Handshake()
	}()
	buf := make([]byte, 8192)
	n, err := serverConn.Read(buf)
	if err != nil {
		t.Fatal(err)
	}
	_ = serverConn.Close()
	<-errCh
	clientHello := make([]byte, n)
	copy(clientHello, buf[:n])
	if indexTLSServerName(clientHello) == nil {
		t.Fatal("captured ClientHello does not contain SNI")
	}
	return clientHello
}

func joinTLSRecordPayloads(t *testing.T, records []byte) []byte {
	t.Helper()
	var payload bytes.Buffer
	for len(records) > 0 {
		if len(records) < recordLayerHeaderLen {
			t.Fatalf("short TLS record header: %d bytes", len(records))
		}
		if records[0] != contentType {
			t.Fatalf("unexpected TLS record content type: %d", records[0])
		}
		recordLen := int(records[3])<<8 | int(records[4])
		if len(records) < recordLayerHeaderLen+recordLen {
			t.Fatalf("short TLS record payload: got %d, want %d", len(records)-recordLayerHeaderLen, recordLen)
		}
		payload.Write(records[recordLayerHeaderLen : recordLayerHeaderLen+recordLen])
		records = records[recordLayerHeaderLen+recordLen:]
	}
	return payload.Bytes()
}
