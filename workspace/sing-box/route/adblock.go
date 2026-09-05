package route

type adblockBlockedError interface {
	error
	IsAdblockBlocked()
}
