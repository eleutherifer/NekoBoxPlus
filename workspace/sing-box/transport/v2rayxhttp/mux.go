package xhttp

import (
	"context"
	"errors"
	"math"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/option"
)

type XmuxConn interface {
	IsClosed() bool
	Close() error
}

type XmuxClient struct {
	XmuxConn     XmuxConn
	manager      *XmuxManager
	openUsage    int32
	packetUsage  int32
	leftUsage    int32
	LeftRequests atomic.Int32
	UnreusableAt time.Time
	idleSince    time.Time
	closed       bool
	mtx          sync.Mutex
	closeOnce    sync.Once
	closeErr     error
}

type XmuxManager struct {
	options     option.V2RayXHTTPXmuxOptions
	concurrency int32
	connections int32
	newConnFunc func() XmuxConn
	xmuxClients []*XmuxClient
	nextIndex   int
	mtx         sync.Mutex
}

func NewXmuxManager(options option.V2RayXHTTPXmuxOptions, newConnFunc func() XmuxConn) *XmuxManager {
	return &XmuxManager{
		options:     options,
		concurrency: options.GetNormalizedMaxConcurrency().Rand(),
		connections: options.GetNormalizedMaxConnections().Rand(),
		newConnFunc: newConnFunc,
		xmuxClients: make([]*XmuxClient, 0),
	}
}

func (m *XmuxManager) newXmuxClient() *XmuxClient {
	xmuxClient := &XmuxClient{
		XmuxConn:  m.newConnFunc(),
		manager:   m,
		leftUsage: -1,
		idleSince: time.Now(),
	}
	if x := m.options.GetNormalizedCMaxReuseTimes().Rand(); x > 0 {
		xmuxClient.leftUsage = x - 1
	}
	xmuxClient.LeftRequests.Store(math.MaxInt32)
	if x := m.options.GetNormalizedHMaxRequestTimes().Rand(); x > 0 {
		xmuxClient.LeftRequests.Store(x)
	}
	if x := m.options.GetNormalizedHMaxReusableSecs().Rand(); x > 0 {
		xmuxClient.UnreusableAt = time.Now().Add(time.Duration(x) * time.Second)
	}
	m.xmuxClients = append(m.xmuxClients, xmuxClient)
	return xmuxClient
}

func (m *XmuxManager) GetXmuxClient(ctx context.Context) *XmuxClient {
	m.mtx.Lock()
	now := time.Now()
	var retiredClients []*XmuxClient
	for i := 0; i < len(m.xmuxClients); {
		xmuxClient := m.xmuxClients[i]
		if xmuxClient.XmuxConn.IsClosed() ||
			xmuxClient.leftUsage == 0 ||
			xmuxClient.LeftRequests.Load() <= 0 ||
			(xmuxClient.UnreusableAt != time.Time{} && now.After(xmuxClient.UnreusableAt)) {
			m.xmuxClients = append(m.xmuxClients[:i], m.xmuxClients[i+1:]...)
			retiredClients = append(retiredClients, xmuxClient)
		} else {
			i++
		}
	}
	defer func() {
		for _, xmuxClient := range retiredClients {
			xmuxClient.Retire()
		}
	}()
	defer m.mtx.Unlock()
	if len(m.xmuxClients) == 0 {
		return m.newXmuxClient()
	}
	if m.connections > 0 && len(m.xmuxClients) < int(m.connections) {
		return m.newXmuxClient()
	}
	if m.concurrency > 0 {
		eligible := 0
		for _, xmuxClient := range m.xmuxClients {
			if xmuxClient.GetOpenUsage() < m.concurrency {
				eligible++
			}
		}
		if eligible == 0 {
			return m.newXmuxClient()
		}
		index := m.nextIndex % eligible
		m.nextIndex++
		for _, xmuxClient := range m.xmuxClients {
			if xmuxClient.GetOpenUsage() >= m.concurrency {
				continue
			}
			if index == 0 {
				if xmuxClient.leftUsage > 0 {
					xmuxClient.leftUsage -= 1
				}
				return xmuxClient
			}
			index--
		}
		return m.newXmuxClient()
	}
	xmuxClient := m.xmuxClients[m.nextIndex%len(m.xmuxClients)]
	m.nextIndex++
	if xmuxClient.leftUsage > 0 {
		xmuxClient.leftUsage -= 1
	}
	return xmuxClient
}

func (m *XmuxManager) idleRetentionLimit() int {
	if m.connections > 0 {
		return int(m.connections)
	}
	return 1
}

func (m *XmuxManager) retireExcessIdle() {
	var retiredClients []*XmuxClient
	m.mtx.Lock()
	idleCount := 0
	for _, xmuxClient := range m.xmuxClients {
		if idle, _ := xmuxClient.idleState(); idle {
			idleCount++
		}
	}
	for idleCount > m.idleRetentionLimit() {
		var retireIndex = -1
		var retireIdleSince time.Time
		for index, xmuxClient := range m.xmuxClients {
			idle, idleSince := xmuxClient.idleState()
			if !idle {
				continue
			}
			if retireIndex == -1 || idleSince.Before(retireIdleSince) {
				retireIndex = index
				retireIdleSince = idleSince
			}
		}
		if retireIndex == -1 {
			break
		}
		xmuxClient := m.xmuxClients[retireIndex]
		m.xmuxClients = append(m.xmuxClients[:retireIndex], m.xmuxClients[retireIndex+1:]...)
		retiredClients = append(retiredClients, xmuxClient)
		idleCount--
	}
	m.mtx.Unlock()
	for _, xmuxClient := range retiredClients {
		xmuxClient.Retire()
	}
}

func (m *XmuxManager) Close() error {
	m.mtx.Lock()
	xmuxClients := m.xmuxClients
	m.xmuxClients = nil
	m.mtx.Unlock()
	var err error
	for _, xmuxClient := range xmuxClients {
		err = errors.Join(err, xmuxClient.ForceClose())
	}
	return err
}

func (c *XmuxClient) Retire() {
	closeSilently(c)
}

func (c *XmuxClient) AddOpenUsage(delta int32) {
	c.mtx.Lock()
	wasActive := c.openUsage > 0
	c.openUsage += delta
	isActive := c.openUsage > 0
	becameIdle := wasActive && !isActive
	if !wasActive && isActive {
		c.idleSince = time.Time{}
	} else if becameIdle {
		c.idleSince = time.Now()
	}
	shouldClose := c.closed && c.openUsage <= 0
	manager := c.manager
	c.mtx.Unlock()
	if shouldClose {
		_ = c.closeXmuxConn()
		return
	}
	if becameIdle && manager != nil {
		manager.retireExcessIdle()
	}
}

func (c *XmuxClient) AddPacketUsage(delta int32) {
	c.mtx.Lock()
	c.packetUsage += delta
	shouldClose := c.closed && c.openUsage <= 0 && c.packetUsage <= 0
	c.mtx.Unlock()
	if shouldClose {
		_ = c.closeXmuxConn()
	}
}

func (c *XmuxClient) GetOpenUsage() int32 {
	c.mtx.Lock()
	defer c.mtx.Unlock()
	return c.openUsage
}

func (c *XmuxClient) GetPacketUsage() int32 {
	c.mtx.Lock()
	defer c.mtx.Unlock()
	return c.packetUsage
}

func (c *XmuxClient) idleState() (bool, time.Time) {
	c.mtx.Lock()
	defer c.mtx.Unlock()
	return !c.closed && c.openUsage <= 0, c.idleSince
}

func (c *XmuxClient) ReleaseUsage() {
	c.AddOpenUsage(-1)
}

func (c *XmuxClient) Close() error {
	c.mtx.Lock()
	c.closed = true
	shouldClose := c.openUsage <= 0 && c.packetUsage <= 0
	c.mtx.Unlock()
	if !shouldClose {
		return nil
	}
	return c.closeXmuxConn()
}

func (c *XmuxClient) ForceClose() error {
	c.mtx.Lock()
	c.closed = true
	c.mtx.Unlock()
	return c.closeXmuxConn()
}

func (c *XmuxClient) closeXmuxConn() error {
	c.closeOnce.Do(func() {
		if c.XmuxConn != nil {
			c.closeErr = c.XmuxConn.Close()
		}
	})
	return c.closeErr
}
