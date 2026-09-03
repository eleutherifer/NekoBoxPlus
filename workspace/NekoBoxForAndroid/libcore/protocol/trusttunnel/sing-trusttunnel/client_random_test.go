package trusttunnel

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
	"golang.org/x/net/http2"
)

func TestClientRandomSpecGeneratePrefix(t *testing.T) {
	t.Parallel()

	spec, err := parseClientRandomSpec("aabbcc")
	require.NoError(t, err)
	clientRandom, err := spec.Generate()
	require.NoError(t, err)
	require.Len(t, clientRandom, clientRandomLength)
	require.Equal(t, []byte{0xaa, 0xbb, 0xcc}, clientRandom[:3])
}

func TestClientRandomSpecRandReaderReturnsConfiguredRandomFirst(t *testing.T) {
	t.Parallel()

	spec, err := parseClientRandomSpec("aabbcc")
	require.NoError(t, err)
	reader, err := spec.RandReader()
	require.NoError(t, err)
	first := make([]byte, clientRandomLength)
	_, err = io.ReadFull(reader, first)
	require.NoError(t, err)
	require.Equal(t, []byte{0xaa, 0xbb, 0xcc}, first[:3])

	next := make([]byte, 8)
	_, err = io.ReadFull(reader, next)
	require.NoError(t, err)
}

func TestClientRandomSpecGenerateMask(t *testing.T) {
	t.Parallel()

	spec, err := parseClientRandomSpec("a0b0/f0f0")
	require.NoError(t, err)
	for range 20 {
		clientRandom, err := spec.Generate()
		require.NoError(t, err)
		require.Equal(t, byte(0xa0), clientRandom[0]&0xf0)
		require.Equal(t, byte(0xb0), clientRandom[1]&0xf0)
	}
}

func TestParseClientRandomSpecRejectsInvalidValues(t *testing.T) {
	t.Parallel()

	for _, value := range []string{
		"zz",
		"aabb/",
		"aabb/ff",
		"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff00",
	} {
		t.Run(value, func(t *testing.T) {
			t.Parallel()
			_, err := parseClientRandomSpec(value)
			require.Error(t, err)
		})
	}
}

func TestUTLSClientUsesConfiguredClientRandomPrefix(t *testing.T) {
	t.Parallel()

	spec, err := parseClientRandomSpec("aabbcc")
	require.NoError(t, err)
	client := &Client{clientRandomSpec: spec}

	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()

	captured := make(chan []byte, 1)
	serverErr := make(chan error, 1)
	go func() {
		recordHeader := make([]byte, 5)
		_, err := io.ReadFull(serverConn, recordHeader)
		if err != nil {
			serverErr <- err
			return
		}
		recordLen := binary.BigEndian.Uint16(recordHeader[3:5])
		payload := make([]byte, recordLen)
		_, err = io.ReadFull(serverConn, payload)
		if err != nil {
			serverErr <- err
			return
		}
		require.GreaterOrEqual(t, len(payload), 38)
		captured <- payload[6:38]
		_ = serverConn.Close()
	}()

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, err = client.utlsClient(ctx, clientConn, &tls.Config{
		ServerName:         "localhost",
		InsecureSkipVerify: true,
		NextProtos:         []string{http2.NextProtoTLS},
	})
	require.Error(t, err)

	select {
	case err := <-serverErr:
		require.NoError(t, err)
	case clientRandom := <-captured:
		require.Equal(t, []byte{0xaa, 0xbb, 0xcc}, clientRandom[:3])
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
}

func TestStdTLSRandReaderControlsClientHelloRandom(t *testing.T) {
	t.Parallel()

	spec, err := parseClientRandomSpec("ddeeff")
	require.NoError(t, err)
	randReader, err := spec.RandReader()
	require.NoError(t, err)

	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()

	captured := make(chan []byte, 1)
	serverErr := make(chan error, 1)
	go func() {
		clientRandom, err := readClientHelloRandom(serverConn)
		if err != nil {
			serverErr <- err
			return
		}
		captured <- clientRandom
		_ = serverConn.Close()
	}()

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	tlsConn := tls.Client(clientConn, &tls.Config{
		ServerName:         "localhost",
		InsecureSkipVerify: true,
		NextProtos:         []string{"h3"},
		Rand:               randReader,
	})
	err = tlsConn.HandshakeContext(ctx)
	require.Error(t, err)

	select {
	case err := <-serverErr:
		require.NoError(t, err)
	case clientRandom := <-captured:
		require.Equal(t, []byte{0xdd, 0xee, 0xff}, clientRandom[:3])
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
}

type fakeRandomConn struct {
	net.Conn
	built        bool
	clientRandom []byte
}

func (c *fakeRandomConn) BuildHandshakeState() error {
	c.built = true
	return nil
}

func (c *fakeRandomConn) SetClientRandom(clientRandom []byte) error {
	c.clientRandom = append([]byte(nil), clientRandom...)
	return nil
}

type fakeUpstreamConn struct {
	net.Conn
	upstream any
}

func (c fakeUpstreamConn) Upstream() any {
	return c.upstream
}

func TestSetClientRandomUsesProvidedUTLSUpstream(t *testing.T) {
	t.Parallel()

	spec, err := parseClientRandomSpec("aabbcc")
	require.NoError(t, err)
	randomConn := &fakeRandomConn{}
	client := &Client{clientRandomSpec: spec}

	err = client.setClientRandom(fakeUpstreamConn{upstream: randomConn})
	require.NoError(t, err)
	require.True(t, randomConn.built)
	require.Len(t, randomConn.clientRandom, clientRandomLength)
	require.Equal(t, []byte{0xaa, 0xbb, 0xcc}, randomConn.clientRandom[:3])
}

func readClientHelloRandom(reader io.Reader) ([]byte, error) {
	recordHeader := make([]byte, 5)
	_, err := io.ReadFull(reader, recordHeader)
	if err != nil {
		return nil, err
	}
	recordLen := binary.BigEndian.Uint16(recordHeader[3:5])
	payload := make([]byte, recordLen)
	_, err = io.ReadFull(reader, payload)
	if err != nil {
		return nil, err
	}
	return payload[6:38], nil
}
