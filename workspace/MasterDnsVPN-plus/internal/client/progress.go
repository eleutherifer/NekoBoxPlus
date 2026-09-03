package client

type ResolverProgressStage string

const (
	ResolverProgressChecking ResolverProgressStage = "checking"
	ResolverProgressReady    ResolverProgressStage = "ready"
)

type ResolverProgress struct {
	Stage    ResolverProgressStage
	Found    int
	Total    int
	Resolver string
	Domain   string
}

type ResolverProgressCallback func(ResolverProgress)

func (c *Client) SetResolverProgressCallback(callback ResolverProgressCallback) {
	if c == nil {
		return
	}
	c.resolverProgress = callback
}

func (c *Client) reportResolverProgress(progress ResolverProgress) {
	if c == nil || c.resolverProgress == nil {
		return
	}
	c.resolverProgress(progress)
}
