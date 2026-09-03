//go:build android && cgo

package byedpi

/*
#cgo CFLAGS: -I${SRCDIR}/../../byedpi
#cgo LDFLAGS: -llog

#include <stdlib.h>
#include <unistd.h>

struct byedpi_runner;

struct byedpi_runner *byedpi_runner_start(int argc, char **argv);
int byedpi_runner_wait_ready(struct byedpi_runner *runner, int timeout_ms);
int byedpi_runner_open_connection(
    struct byedpi_runner *runner, int with_udp, int *stream_fd, int *udp_fd);
int byedpi_runner_stop(struct byedpi_runner *runner);
int byedpi_runner_join(struct byedpi_runner *runner);
const char *byedpi_runner_last_error(struct byedpi_runner *runner);
void byedpi_runner_free(struct byedpi_runner *runner);
int byedpi_android_protect_fd(int fd);
*/
import "C"

import (
	"fmt"
	protectfd "libcore/protect"
	"net"
	"os"
	"strings"
	"sync"
	"time"
	"unsafe"

	"github.com/google/shlex"
)

//export byedpi_android_protect_fd
func byedpi_android_protect_fd(fd C.int) C.int {
	if err := protectfd.FD(int(fd)); err != nil {
		return -1
	}
	return 0
}

type bridgeHandle struct {
	runner *C.struct_byedpi_runner
	key    string
	refs   int
}

var (
	bridgeAccess  sync.Mutex
	activeBridges = make(map[string]*bridgeHandle)
)

func acquireBridge(cli string) (*bridgeHandle, error) {
	key := strings.TrimSpace(cli)

	bridgeAccess.Lock()
	defer bridgeAccess.Unlock()

	if bridge, ok := activeBridges[key]; ok {
		bridge.refs++
		return bridge, nil
	}

	args, err := buildArgs(cli)
	if err != nil {
		return nil, err
	}
	cArgs, cleanup := makeCStringArray(args)
	defer cleanup()

	runner := C.byedpi_runner_start(C.int(len(args)), cArgs)
	if runner == nil {
		return nil, fmt.Errorf("start ByeDPI runner")
	}
	ready := C.byedpi_runner_wait_ready(runner, 5000)
	if ready == 0 {
		exitCode := int(C.byedpi_runner_join(runner))
		stderrText := strings.TrimSpace(C.GoString(C.byedpi_runner_last_error(runner)))
		C.byedpi_runner_free(runner)
		if stderrText != "" {
			return nil, fmt.Errorf("ByeDPI private bridge did not start: %s", stderrText)
		}
		return nil, fmt.Errorf("ByeDPI private bridge did not start, exit code %d", exitCode)
	}

	bridge := &bridgeHandle{
		runner: runner,
		key:    key,
		refs:   1,
	}
	activeBridges[key] = bridge
	return bridge, nil
}

func (h *bridgeHandle) openConnection(withUDP bool) (net.Conn, net.Conn, error) {
	var streamFD C.int
	var udpFD C.int
	if C.byedpi_runner_open_connection(
		h.runner,
		C.int(boolToInt(withUDP)),
		&streamFD,
		&udpFD,
	) != 0 {
		return nil, nil, fmt.Errorf("open ByeDPI private connection")
	}
	stream, err := fileConn(int(streamFD), "byedpi-stream")
	if err != nil {
		if udpFD >= 0 {
			_ = C.close(udpFD)
		}
		return nil, nil, err
	}
	if !withUDP {
		return stream, nil, nil
	}
	datagram, err := fileConn(int(udpFD), "byedpi-datagram")
	if err != nil {
		_ = stream.Close()
		return nil, nil, err
	}
	return stream, datagram, nil
}

func fileConn(fd int, name string) (net.Conn, error) {
	file := os.NewFile(uintptr(fd), name)
	if file == nil {
		_ = C.close(C.int(fd))
		return nil, fmt.Errorf("wrap %s file descriptor", name)
	}
	conn, err := net.FileConn(file)
	_ = file.Close()
	if err != nil {
		return nil, fmt.Errorf("wrap %s connection: %w", name, err)
	}
	return conn, nil
}

func boolToInt(value bool) int {
	if value {
		return 1
	}
	return 0
}

func releaseBridge(handle *bridgeHandle) error {
	if handle == nil {
		return nil
	}

	bridgeAccess.Lock()
	activeBridge, ok := activeBridges[handle.key]
	if !ok || activeBridge != handle {
		bridgeAccess.Unlock()
		return nil
	}
	handle.refs--
	if handle.refs > 0 {
		bridgeAccess.Unlock()
		return nil
	}
	delete(activeBridges, handle.key)
	bridgeAccess.Unlock()

	stopResult := int(C.byedpi_runner_stop(handle.runner))
	done := make(chan int, 1)
	go func() {
		done <- int(C.byedpi_runner_join(handle.runner))
	}()

	var joinResult int
	joined := false
	select {
	case joinResult = <-done:
		joined = true
	case <-time.After(3 * time.Second):
		joinResult = -1
	}
	if joined {
		C.byedpi_runner_free(handle.runner)
	}

	if stopResult != 0 && stopResult != -1 {
		return fmt.Errorf("stop ByeDPI runner: %d", stopResult)
	}
	if !joined {
		return fmt.Errorf("stop ByeDPI runner: join timed out")
	}
	if joinResult != 0 && joinResult != -1 {
		return fmt.Errorf("ByeDPI exited with code %d", joinResult)
	}
	return nil
}

func buildArgs(cli string) ([]string, error) {
	userArgs, err := shlex.Split(cli)
	if err != nil {
		return nil, fmt.Errorf("parse CLI strategy: %w", err)
	}
	userArgs = sanitizeArgs(userArgs)
	args := []string{
		"ciadpi",
		"--ip", "127.0.0.1",
		"--port", "0",
		"--protect-path", "protect_path",
	}
	return append(args, userArgs...), nil
}

func makeCStringArray(args []string) (**C.char, func()) {
	cArgs := make([]*C.char, 0, len(args))
	for _, arg := range args {
		cArgs = append(cArgs, C.CString(arg))
	}
	return (**C.char)(unsafe.Pointer(&cArgs[0])), func() {
		for _, arg := range cArgs {
			C.free(unsafe.Pointer(arg))
		}
	}
}
