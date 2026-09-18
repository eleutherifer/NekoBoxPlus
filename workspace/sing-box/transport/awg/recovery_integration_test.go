package awg_test

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	awgconn "github.com/amnezia-vpn/amneziawg-go/v3/conn"
	awgdevice "github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/transport/awg"
	"github.com/sagernet/sing-box/transport/wireguard"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type localDialer struct {
	opens atomic.Int32
}

func (d *localDialer) DialContext(ctx context.Context, network string, address M.Socksaddr) (net.Conn, error) {
	d.opens.Add(1)
	return (&net.Dialer{}).DialContext(ctx, network, address.String())
}

func (d *localDialer) ListenPacket(ctx context.Context, address M.Socksaddr) (net.PacketConn, error) {
	d.opens.Add(1)
	return (&net.ListenConfig{}).ListenPacket(ctx, "udp", "127.0.0.1:0")
}

// These tests use a real encrypted peer and an isolated userspace TCP/IP stack;
// no external server, privileged interface, or subscription is required.
func TestEncryptedTrafficRecoversAfterPauseAndRebind(t *testing.T) {
	for _, protocol := range []string{"wireguard", "amneziawg"} {
		for _, keepalive := range []uint16{0, 25} {
			t.Run(fmt.Sprintf("%s/keepalive-%d", protocol, keepalive), func(t *testing.T) {
				testEncryptedRecovery(t, protocol, keepalive)
			})
		}
	}
}

func testEncryptedRecovery(t *testing.T, protocol string, keepalive uint16) {
	serverKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	clientKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	serverAddress := netip.MustParseAddr("10.77.0.1")
	clientAddress := netip.MustParsePrefix("10.77.0.2/32")
	tunDevice, stack, err := netstack.CreateNetTUN([]netip.Addr{serverAddress}, nil, 1408)
	if err != nil {
		t.Fatal(err)
	}
	server := awgdevice.NewDevice(tunDevice, awgconn.NewDefaultBind(), awgdevice.NewLogger(awgdevice.LogLevelSilent, ""))
	t.Cleanup(server.Close)
	ipc := fmt.Sprintf("private_key=%s\nlisten_port=0\npublic_key=%s\nallowed_ip=10.77.0.2/32\n",
		hex.EncodeToString(serverKey.Bytes()), hex.EncodeToString(clientKey.PublicKey().Bytes()))
	if err = server.IpcSet(ipc); err != nil {
		t.Fatal(err)
	}
	if err = server.Up(); err != nil {
		t.Fatal(err)
	}
	state, err := server.IpcGet()
	if err != nil {
		t.Fatal(err)
	}
	var port uint64
	for line := range strings.SplitSeq(state, "\n") {
		if value, ok := strings.CutPrefix(line, "listen_port="); ok {
			port, err = strconv.ParseUint(value, 10, 16)
			if err != nil {
				t.Fatal(err)
			}
		}
	}
	if port == 0 {
		t.Fatal("peer did not open its UDP socket")
	}
	tcpListener, err := stack.ListenTCPAddrPort(netip.AddrPortFrom(serverAddress, 8080))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { tcpListener.Close() })
	go func() {
		for {
			conn, acceptErr := tcpListener.Accept()
			if acceptErr != nil {
				return
			}
			go func() {
				defer conn.Close()
				io.Copy(conn, conn)
			}()
		}
	}()
	udpListener, err := stack.ListenUDPAddrPort(netip.AddrPortFrom(serverAddress, 8081))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udpListener.Close() })
	go func() {
		buffer := make([]byte, 2048)
		for {
			n, address, readErr := udpListener.ReadFrom(buffer)
			if readErr != nil {
				return
			}
			udpListener.WriteTo(buffer[:n], address)
		}
	}()

	ctx := pause.WithDefaultManager(service.ContextWithDefaultRegistry(t.Context()))
	manager := service.FromContext[pause.Manager](ctx)
	manager.DevicePause()
	dialer := &localDialer{}
	peer := netip.AddrPortFrom(netip.MustParseAddr("127.0.0.1"), uint16(port))
	var client N.Dialer
	var rebind func()
	var waitReady func(context.Context) error
	if protocol == "wireguard" {
		endpoint, createErr := wireguard.NewEndpoint(wireguard.EndpointOptions{
			Context: ctx, Logger: logger.NOP(), Dialer: dialer,
			Address:    []netip.Prefix{clientAddress},
			PrivateKey: base64.StdEncoding.EncodeToString(clientKey.Bytes()),
			Peers: []wireguard.PeerOptions{{
				Endpoint:                    M.SocksaddrFromNetIP(peer),
				PublicKey:                   base64.StdEncoding.EncodeToString(serverKey.PublicKey().Bytes()),
				AllowedIPs:                  []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0")},
				PersistentKeepaliveInterval: keepalive,
			}},
		})
		if createErr != nil {
			t.Fatal(createErr)
		}
		t.Cleanup(func() { endpoint.Close() })
		if err = endpoint.Start(false); err != nil {
			t.Fatal(err)
		}
		client, rebind, waitReady = endpoint, endpoint.InterfaceUpdated, endpoint.WaitReady
	} else {
		ipc = fmt.Sprintf("private_key=%s\npublic_key=%s\nendpoint=%s\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=%d\n",
			hex.EncodeToString(clientKey.Bytes()), hex.EncodeToString(serverKey.PublicKey().Bytes()), peer, keepalive)
		device, createErr := awg.NewDevice(ctx, logger.NOP(), dialer, ipc, awg.DeviceOpts{
			Address: []netip.Prefix{clientAddress}, MTU: 1408, LazyBind: true, PeerEndpoint: peer,
		})
		if createErr != nil {
			t.Fatal(createErr)
		}
		t.Cleanup(func() { device.Close() })
		if err = device.Start(adapter.StartStateStart); err != nil {
			t.Fatal(err)
		}
		client = device
		rebind = func() { device.InterfaceUpdated(ctx) }
		waitReady = device.WaitReady
	}

	probe := func(stage string) {
		t.Helper()
		for _, network := range []string{"tcp", "udp"} {
			probePort := uint16(8080)
			if network == "udp" {
				probePort = 8081
			}
			probeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			conn, dialErr := client.DialContext(probeCtx, network, M.SocksaddrFromNetIP(netip.AddrPortFrom(serverAddress, probePort)))
			cancel()
			if dialErr != nil {
				t.Fatalf("%s %s: %v", stage, network, dialErr)
			}
			conn.SetDeadline(time.Now().Add(10 * time.Second))
			_, writeErr := conn.Write([]byte("recovered"))
			buffer := make([]byte, len("recovered"))
			_, readErr := io.ReadFull(conn, buffer)
			conn.Close()
			if writeErr != nil || readErr != nil || string(buffer) != "recovered" {
				t.Fatalf("%s %s: write=%v read=%v", stage, network, writeErr, readErr)
			}
		}
	}
	waitForReady := func(stage string) {
		t.Helper()
		readyCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		if readyErr := waitReady(readyCtx); readyErr != nil {
			t.Fatalf("%s readiness: %v", stage, readyErr)
		}
	}
	time.Sleep(100 * time.Millisecond)
	if dialer.opens.Load() != 0 {
		t.Fatal("device opened an upstream socket while starting paused")
	}
	manager.DeviceWake()
	waitForReady("initial")
	probe("initial")
	for cycle := range 3 {
		manager.DevicePause()
		manager.NetworkPause()
		rebind()
		manager.DeviceWake()
		manager.NetworkWake()
		waitForReady(fmt.Sprintf("wake-%d", cycle))
		probe(fmt.Sprintf("wake-%d", cycle))
	}
	rebind()
	waitForReady("explicit-reset")
	probe("explicit-reset")
}
