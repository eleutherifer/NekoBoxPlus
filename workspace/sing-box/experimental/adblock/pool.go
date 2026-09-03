//go:build with_adblock

package adblock

import (
	"sync"
)

type PoolItem[T any] struct {
	Value T
	owner PoolItemOwner[T]
}

func (p *PoolItem[T]) Release() {
	p.owner.Put(p)
}

type PoolItemOwner[T any] interface {
	Put(x *PoolItem[T])
}

func NewPoolItem[T any](x T, owner PoolItemOwner[T]) *PoolItem[T] {
	return &PoolItem[T]{x, owner}
}

type TypedPoolConstructor[T any] func() T

type TypedPool[T any] struct {
	pool *sync.Pool
}

func NewTypedPool[T any]() *TypedPool[T] {
	return &TypedPool[T]{pool: &sync.Pool{}}
}

func (p *TypedPool[T]) SetConstructor(construct TypedPoolConstructor[T]) *TypedPool[T] {
	p.pool.New = func() any {
		return NewPoolItem(construct(), p)
	}

	return p
}

func (p *TypedPool[T]) Put(x *PoolItem[T]) {
	p.pool.Put(x)
}

func (p *TypedPool[T]) Get() *PoolItem[T] {
	return p.pool.Get().(*PoolItem[T])
}
