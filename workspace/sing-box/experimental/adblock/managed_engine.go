//go:build with_adblock

package adblock

import (
	"sync"

	"github.com/sagernet/sing-box/common/adblock/adblockrust"
)

type managedEngine struct {
	engine adblockrust.Engine
	refs   sync.WaitGroup
	once   sync.Once
	err    error
}

func newManagedEngine(engine adblockrust.Engine) *managedEngine {
	// preheat
	_, _ = engine.Check("https://google.com", "https://google.com", "document", adblockrust.RequestMethodGet)
	_, _ = engine.CheckDetailed("https://google.com", "https://google.com", "document", adblockrust.RequestMethodGet)
	_, _ = engine.CSPDirectives("https://google.com", "https://google.com", "document", adblockrust.RequestMethodGet)
	resources, err := engine.URLCosmeticResources("https://google.com")
	if err == nil {
		_, _ = engine.HiddenClassIDSelectors([]string{"generic-ad"}, nil, resources.Exceptions)
	}
	return &managedEngine{engine: engine}
}

func (e *managedEngine) retain() adblockrust.Engine {
	e.refs.Add(1)
	return e.engine
}

func (e *managedEngine) release() {
	e.refs.Done()
}

func (e *managedEngine) close() error {
	e.once.Do(func() {
		e.refs.Wait()
		e.err = e.engine.Close()
	})
	return e.err
}

func (e *managedEngine) forceClose() error {
	e.once.Do(func() {
		e.err = e.engine.Close()
	})
	return e.err
}
