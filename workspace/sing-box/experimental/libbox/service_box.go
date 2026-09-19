package libbox

import (
	"context"
	"os"
	runtimeDebug "runtime/debug"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type BoxService struct {
	ctx          context.Context
	cancel       context.CancelFunc
	instance     *box.Box
	clashServer  adapter.ClashServer
	pauseManager pause.Manager

	iOSPauseFields
}

func NewService(configContent string, platformInterface PlatformInterface) (*BoxService, error) {
	ctx := baseContext(platformInterface)
	options, err := parseConfig(ctx, configContent)
	if err != nil {
		return nil, err
	}
	runtimeDebug.FreeOSMemory()
	ctx, cancel := context.WithCancel(ctx)
	platformWrapper := &platformInterfaceWrapper{
		iif:       platformInterface,
		useProcFS: platformInterface.UseProcFS(),
	}
	service.MustRegister[adapter.PlatformInterface](ctx, platformWrapper)
	instance, err := box.New(box.Options{
		Context: ctx,
		Options: options,
	})
	if err != nil {
		cancel()
		return nil, E.Cause(err, "create service")
	}
	runtimeDebug.FreeOSMemory()
	return &BoxService{
		ctx:          ctx,
		cancel:       cancel,
		instance:     instance,
		pauseManager: service.FromContext[pause.Manager](ctx),
		clashServer:  service.FromContext[adapter.ClashServer](ctx),
	}, nil
}

func (s *BoxService) Start() error {
	if sFixAndroidStack {
		var err error
		done := make(chan struct{})
		go func() {
			err = s.instance.Start()
			close(done)
		}()
		<-done
		return err
	}
	return s.instance.Start()
}

func (s *BoxService) Close() error {
	s.cancel()
	var err error
	done := make(chan struct{})
	go func() {
		err = s.instance.Close()
		close(done)
	}()
	select {
	case <-done:
		return err
	case <-time.After(C.FatalStopTimeout):
		os.Exit(1)
		return nil
	}
}

func (s *BoxService) NeedWIFIState() bool {
	return s.instance.Network().NeedWIFIState()
}
