package cachefile

import (
	"os"

	"github.com/sagernet/bbolt"
	"github.com/sagernet/sing-box/adapter"
)

func (c *CacheFile) StoreMASQUEConfig() bool {
	return true
}

func (c *CacheFile) LoadMASQUEConfig(tag string) *adapter.SavedBinary {
	var savedConfig adapter.SavedBinary
	err := c.view(func(t *bbolt.Tx) error {
		bucket := c.bucket(t, bucketMASQUE)
		if bucket == nil {
			return os.ErrNotExist
		}
		configBinary := bucket.Get([]byte(tag))
		if len(configBinary) == 0 {
			return os.ErrInvalid
		}
		return savedConfig.UnmarshalBinary(configBinary)
	})
	if err != nil {
		return nil
	}
	return &savedConfig
}

func (c *CacheFile) SaveMASQUEConfig(tag string, set *adapter.SavedBinary) error {
	return c.batch(func(t *bbolt.Tx) error {
		bucket, err := c.createBucket(t, bucketMASQUE)
		if err != nil {
			return err
		}
		configBinary, err := set.MarshalBinary()
		if err != nil {
			return err
		}
		return bucket.Put([]byte(tag), configBinary)
	})
}
