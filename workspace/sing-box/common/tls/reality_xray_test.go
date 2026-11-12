//go:build with_utls

package tls

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	stdtls "crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/sagernet/sing-vmess/vless"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/cloudflare/circl/sign/mldsa/mldsa65"
	utls "github.com/metacubex/utls"
)

// Set REALITY_XRAY_BINARY to a binary built from the reference Xray checkout.
func TestRealityXray(t *testing.T) {
	binary := os.Getenv("REALITY_XRAY_BINARY")
	if binary == "" {
		t.Skip("REALITY_XRAY_BINARY is unset")
	}
	target := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) }))
	target.TLS = &stdtls.Config{MinVersion: stdtls.VersionTLS13}
	target.StartTLS()
	defer target.Close()
	// REALITY needs enough camouflage flight space for its ML-DSA certificate.
	leaf := target.TLS.Certificates[0].Certificate[0]
	for range 6 {
		target.TLS.Certificates[0].Certificate = append(target.TLS.Certificates[0].Certificate, leaf)
	}
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	var seed [mldsa65.SeedSize]byte
	if _, err := rand.Read(seed[:]); err != nil {
		t.Fatal(err)
	}
	verify, _ := mldsa65.NewKeyFromSeed(&seed)
	config := map[string]any{
		"log": map[string]any{"loglevel": "debug"},
		"inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": port, "protocol": "vless",
			"settings": map[string]any{"decryption": "none", "clients": []any{map[string]any{"id": "00000000-0000-0000-0000-000000000001", "flow": "xtls-rprx-vision"}}},
			"streamSettings": map[string]any{"network": "raw", "security": "reality", "realitySettings": map[string]any{
				"mldsa65Seed": base64.RawURLEncoding.EncodeToString(seed[:]), "target": target.Listener.Addr().String(), "serverNames": []string{"example.com"}, "privateKey": base64.RawURLEncoding.EncodeToString(key.Bytes()), "minClientVer": "26.11.9", "shortIds": []string{""},
			}}}}, "outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.1"}}}}}},
	}
	data, _ := json.Marshal(config)
	file := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(file, data, 0600); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(binary, "run", "-c", file)
	cmd.Stdout = os.Stderr
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { cmd.Process.Kill(); cmd.Wait() }()
	address := fmt.Sprintf("127.0.0.1:%d", port)
	for _, test := range []struct {
		name, policy string
		fingerprint  utls.ClientHelloID
		success      bool
		enhanced     bool
	}{
		{"modern preserve", "preserve", utls.HelloChrome_Auto, true, false},
		{"legacy adapted", "ensure_hybrid", utls.HelloChrome_100, true, false},
		{"enhanced modern", "preserve", utls.HelloChrome_Auto, true, true},
		{"enhanced adapted", "ensure_hybrid", utls.HelloChrome_100, true, true},
		{"legacy preserve", "preserve", utls.HelloChrome_100, false, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			var conn net.Conn
			deadline := time.Now().Add(5 * time.Second)
			for time.Now().Before(deadline) {
				conn, err = net.DialTimeout("tcp", address, time.Second)
				if err == nil {
					break
				}
				time.Sleep(20 * time.Millisecond)
			}
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			conn.SetDeadline(time.Now().Add(10 * time.Second))
			config := &RealityClientConfig{ctx: t.Context(), uClient: &UTLSClientConfig{serverName: "example.com", config: &utls.Config{ServerName: "example.com"}, id: test.fingerprint}, publicKey: key.PublicKey().Bytes(), keySharePolicy: test.policy, mldsa65Verify: verify}
			secured, err := config.ClientHandshake(context.Background(), conn)
			if test.success {
				if err != nil {
					t.Fatal(err)
				}
				defer secured.Close()
				client, err := vless.NewClient("00000000-0000-0000-0000-000000000001", "xtls-rprx-vision", logger.NOP())
				if err != nil {
					t.Fatal(err)
				}
				var tunnel net.Conn
				if test.enhanced {
					tunnel, err = client.DialEarlyConnWithOptions(secured, secured, M.ParseSocksaddr(target.Listener.Addr().String()), true)
				} else {
					tunnel, err = client.DialConn(secured, M.ParseSocksaddr(target.Listener.Addr().String()))
				}
				if err != nil {
					t.Fatal(err)
				}
				defer tunnel.Close()
				inner := stdtls.Client(tunnel, &stdtls.Config{InsecureSkipVerify: true})
				if err := inner.Handshake(); err != nil {
					t.Fatal(err)
				}
				if _, err := io.WriteString(inner, "GET / HTTP/1.0\r\nHost: example.com\r\n\r\n"); err != nil {
					t.Fatal(err)
				}
				response, err := io.ReadAll(inner)
				if err != nil {
					t.Fatal(err)
				}
				if !strings.Contains(string(response), "ok") {
					t.Fatalf("unexpected response: %q", response)
				}
			} else if err == nil {
				secured.Close()
				t.Fatal("legacy fingerprint unexpectedly accepted")
			}
		})
	}
}
