package adapter

type MASQUECacheFile interface {
	StoreMASQUEConfig() bool
	LoadMASQUEConfig(tag string) *SavedBinary
	SaveMASQUEConfig(tag string, set *SavedBinary) error
}

type dummyAdapter struct{}

func (a *dummyAdapter) StoreMASQUEConfig() bool {
	return false
}

func (a *dummyAdapter) LoadMASQUEConfig(tag string) *SavedBinary {
	return nil
}

func (a *dummyAdapter) SaveMASQUEConfig(tag string, set *SavedBinary) error {
	return nil
}

var masqueDummyCache MASQUECacheFile = &dummyAdapter{}

func GetMASQUECache(cf CacheFile) MASQUECacheFile {
	if cf == nil {
		return masqueDummyCache
	}

	mcf, ok := cf.(MASQUECacheFile)
	if !ok {
		return masqueDummyCache
	}

	return mcf
}
