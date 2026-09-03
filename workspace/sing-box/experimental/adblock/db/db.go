//go:build with_adblock

package db

import (
	"context"
	"encoding/binary"
	"errors"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/bbolt"
	bboltErrors "github.com/sagernet/bbolt/errors"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/service/filemanager"
)

var (
	bucketFilters = []byte("filters")
	bucketStats   = []byte("stats")

	bucketNameList = []string{
		string(bucketFilters),
		string(bucketStats),
	}
)

var _ adapter.AdblockDatabase = (*AdblockDB)(nil)

type AdblockDB struct {
	ctx      context.Context
	path     string
	readOnly bool
	DB       *bbolt.DB
	access   sync.Mutex
}

func New(ctx context.Context, filePath string) *AdblockDB {
	return newAdblockDB(ctx, filePath, false)
}

// NewReadOnly opens an existing adblock database without performing any writes.
//
// A read-only handle is safe for bridge reads while another read-write
// AdblockDB in the same Android process owns the database. It must not run
// housekeeping, chown, corruption removal, or any other write transaction.
func NewReadOnly(ctx context.Context, filePath string) *AdblockDB {
	return newAdblockDB(ctx, filePath, true)
}

func newAdblockDB(ctx context.Context, filePath string, readOnly bool) *AdblockDB {
	var path string
	if filePath != "" {
		path = filePath
	} else {
		path = "adblock.db"
	}
	return &AdblockDB{
		ctx:      ctx,
		path:     filemanager.BasePath(ctx, path),
		readOnly: readOnly,
	}
}

func (c *AdblockDB) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStateInitialize {
		return nil
	}
	c.access.Lock()
	defer c.access.Unlock()
	if c.DB != nil {
		return nil
	}
	const fileMode = 0o666
	if c.readOnly {
		if _, err := os.Stat(c.path); err != nil {
			return err
		}
		db, err := bbolt.Open(c.path, fileMode, &bbolt.Options{Timeout: time.Second, ReadOnly: true})
		if err != nil {
			return err
		}
		c.DB = db
		return nil
	}

	options := bbolt.Options{Timeout: time.Second}
	var (
		db  *bbolt.DB
		err error
	)
	for range 10 {
		db, err = bbolt.Open(c.path, fileMode, &options)
		if err == nil {
			break
		}
		if errors.Is(err, bboltErrors.ErrTimeout) {
			continue
		}
		if E.IsMulti(err, bboltErrors.ErrInvalid, bboltErrors.ErrChecksum, bboltErrors.ErrVersionMismatch) {
			rmErr := os.Remove(c.path)
			if rmErr != nil {
				return err
			}
		}
		time.Sleep(100 * time.Millisecond)
	}
	if err != nil {
		return err
	}
	err = filemanager.Chown(c.ctx, c.path)
	if err != nil {
		db.Close()
		return E.Cause(err, "platform chown")
	}
	err = db.Update(func(tx *bbolt.Tx) error {
		return tx.ForEach(func(name []byte, b *bbolt.Bucket) error {
			if name[0] == 0 {
				return b.ForEachBucket(func(k []byte) error {
					bucketName := string(k)
					if !(common.Contains(bucketNameList, bucketName)) {
						_ = b.DeleteBucket(name)
					}
					return nil
				})
			} else {
				bucketName := string(name)
				if !common.Contains(bucketNameList, bucketName) {
					_ = tx.DeleteBucket(name)
				}
			}
			return nil
		})
	})
	if err != nil {
		if strings.HasPrefix(err.Error(), "database corrupted:") {
			return nil
		}
		db.Close()
		return err
	}
	c.DB = db
	return nil
}

func (c *AdblockDB) Close() error {
	c.access.Lock()
	defer c.access.Unlock()
	if c.DB == nil {
		return nil
	}
	err := c.DB.Close()
	c.DB = nil
	return err
}

func (c *AdblockDB) view(fn func(tx *bbolt.Tx) error) (err error) {
	c.access.Lock()
	defer c.access.Unlock()
	if c.DB == nil {
		return os.ErrClosed
	}
	defer func() {
		if r := recover(); r != nil {
			c.resetDBLocked()
			err = E.New("database corrupted: ", r)
		}
	}()
	return c.DB.View(fn)
}

// update must remain synchronous: bbolt.Batch commits on its own goroutine,
// outside this method's panic recovery. access already serializes all writes,
// so batching cannot combine transactions here anyway.
func (c *AdblockDB) update(fn func(tx *bbolt.Tx) error) (err error) {
	c.access.Lock()
	defer c.access.Unlock()
	if c.DB == nil {
		return os.ErrClosed
	}
	if c.readOnly {
		return bboltErrors.ErrDatabaseReadOnly
	}
	defer func() {
		if r := recover(); r != nil {
			c.resetDBLocked()
			err = E.New("database corrupted: ", r)
		}
	}()
	return c.DB.Update(fn)
}

func (c *AdblockDB) resetDBLocked() {
	if c.DB != nil {
		_ = c.DB.Close()
		c.DB = nil
	}
	if c.readOnly {
		return
	}
	_ = os.Remove(c.path)
	db, err := bbolt.Open(c.path, 0o666, &bbolt.Options{Timeout: time.Second})
	if err == nil {
		_ = filemanager.Chown(c.ctx, c.path)
		c.DB = db
	}
}

func (c *AdblockDB) LoadFilterList(tag string) *adapter.SavedBinary {
	var savedSet adapter.SavedBinary
	err := c.view(func(t *bbolt.Tx) error {
		bucket := t.Bucket(bucketFilters)
		if bucket == nil {
			return os.ErrNotExist
		}
		setBinary := bucket.Get([]byte(tag))
		if len(setBinary) == 0 {
			return os.ErrInvalid
		}
		return savedSet.UnmarshalBinary(setBinary)
	})
	if err != nil {
		return nil
	}
	return &savedSet
}

func (c *AdblockDB) SaveFilterList(tag string, set *adapter.SavedBinary) error {
	return c.update(func(t *bbolt.Tx) error {
		bucket, err := t.CreateBucketIfNotExists(bucketFilters)
		if err != nil {
			return err
		}
		setBinary, err := set.MarshalBinary()
		if err != nil {
			return err
		}
		return bucket.Put([]byte(tag), setBinary)
	})
}

func (c *AdblockDB) DeleteFilterList(tag string) error {
	return c.update(func(t *bbolt.Tx) error {
		bucket, err := t.CreateBucketIfNotExists(bucketFilters)
		if err != nil {
			return err
		}
		return bucket.Delete([]byte(tag))
	})
}

var keyAdblockStats = []byte("stats")

func (c *AdblockDB) LoadAdblockStats() (total uint64, blocked uint64, loaded bool) {
	err := c.view(func(t *bbolt.Tx) error {
		bucket := t.Bucket(bucketStats)
		if bucket == nil {
			return os.ErrNotExist
		}
		content := bucket.Get(keyAdblockStats)
		if len(content) != 16 {
			return os.ErrInvalid
		}
		total = binary.BigEndian.Uint64(content[:8])
		blocked = binary.BigEndian.Uint64(content[8:])
		loaded = true
		return nil
	})
	if err != nil {
		return 0, 0, false
	}
	return
}

func (c *AdblockDB) StoreAdblockStats(total uint64, blocked uint64) error {
	content := make([]byte, 16)
	binary.BigEndian.PutUint64(content[:8], total)
	binary.BigEndian.PutUint64(content[8:], blocked)
	return c.update(func(t *bbolt.Tx) error {
		bucket, err := t.CreateBucketIfNotExists(bucketStats)
		if err != nil {
			return err
		}
		return bucket.Put(keyAdblockStats, content)
	})
}
