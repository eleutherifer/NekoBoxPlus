package mobile

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"masterdnsvpn-go/internal/netbind"
	"masterdnsvpn-go/pkg/nativeclient"
)

var runner struct {
	sync.Mutex
	client *nativeclient.Client
}

func SetBoundInterface(name string) {
	netbind.SetInterface(name)
}

func SetBoundAddress(ipv4, ipv6 string) {
	netbind.SetAddress(ipv4, ipv6)
}

func BoundInterface() string {
	return netbind.Current()
}

func BoundIPv4() string {
	return netbind.CurrentIPv4()
}

func BoundIPv6() string {
	return netbind.CurrentIPv6()
}

func Start(configText string, resolversText string, profileDir string, socksPort int64) (bool, error) {
	runner.Lock()
	defer runner.Unlock()

	if runner.client != nil {
		return false, errors.New("MasterDnsVPN is already running")
	}
	if socksPort <= 0 {
		return false, fmt.Errorf("invalid socks port: %d", socksPort)
	}

	client, err := nativeclient.Start(context.Background(), nativeclient.Options{
		ConfigText:        configText,
		ResolversText:     resolversText,
		ProfileDir:        profileDir,
		DisableLocalProxy: false,
	})
	if err != nil {
		return false, err
	}
	runner.client = client
	return true, nil
}

func WaitReady(timeoutMillis int64) (bool, error) {
	runner.Lock()
	client := runner.client
	runner.Unlock()

	if client == nil {
		return false, errors.New("MasterDnsVPN is not running")
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMillis)*time.Millisecond)
	defer cancel()

	if err := client.WaitReady(ctx); err != nil {
		return false, err
	}
	return true, nil
}

func Stop() {
	runner.Lock()
	client := runner.client
	runner.client = nil
	runner.Unlock()

	if client != nil {
		_ = client.Close()
	}
}

func IsRunning() bool {
	runner.Lock()
	defer runner.Unlock()
	return runner.client != nil
}

func ActiveSocksPort() int64 {
	runner.Lock()
	client := runner.client
	runner.Unlock()

	if client == nil {
		return 0
	}
	return int64(client.ActiveSocksPort())
}
