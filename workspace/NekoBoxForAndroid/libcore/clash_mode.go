package libcore

import (
	"encoding/json"
	"fmt"
	"libcore/device"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/service"
)

type clashModeSetter interface {
	SetMode(newMode string)
}

func clashServerFromInstance(b *BoxInstance) adapter.ClashServer {
	if b == nil || b.ctx == nil {
		return nil
	}
	return service.FromContext[adapter.ClashServer](b.ctx)
}

func CurrentClashMode(b *BoxInstance) (mode string, err error) {
	defer device.DeferPanicToError("CurrentClashMode", func(err_ error) { err = err_ })

	if b == nil {
		return "", nil
	}
	b.access.Lock()
	defer b.access.Unlock()

	clashServer := clashServerFromInstance(b)
	if clashServer == nil {
		return "", nil
	}
	return clashServer.Mode(), nil
}

func ClashModeList(b *BoxInstance) (modeListJson string, err error) {
	defer device.DeferPanicToError("ClashModeList", func(err_ error) { err = err_ })

	if b == nil {
		return "[]", nil
	}
	b.access.Lock()
	defer b.access.Unlock()

	clashServer := clashServerFromInstance(b)
	if clashServer == nil {
		return "[]", nil
	}
	modeList, err := json.Marshal(clashServer.ModeList())
	if err != nil {
		return "", err
	}
	return string(modeList), nil
}

func SetClashMode(b *BoxInstance, newMode string) (err error) {
	defer device.DeferPanicToError("SetClashMode", func(err_ error) { err = err_ })

	if b == nil {
		return nil
	}
	b.access.Lock()
	defer b.access.Unlock()

	clashServer := clashServerFromInstance(b)
	if clashServer == nil {
		return nil
	}
	modeSetter, ok := clashServer.(clashModeSetter)
	if !ok {
		return fmt.Errorf("clash mode switching is not supported")
	}
	mode, ok := canonicalClashMode(clashServer.ModeList(), newMode)
	if ok {
		oldMode := clashServer.Mode()
		modeSetter.SetMode(mode)
		if !strings.EqualFold(oldMode, clashServer.Mode()) {
			return b.resetConnectionsLocked()
		}
		return nil
	}
	return fmt.Errorf("unknown Clash Mode: %s", newMode)
}

func canonicalClashMode(modeList []string, newMode string) (string, bool) {
	for _, mode := range modeList {
		if strings.EqualFold(mode, newMode) {
			return mode, true
		}
	}
	return "", false
}
