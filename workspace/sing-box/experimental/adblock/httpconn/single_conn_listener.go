//go:build with_adblock

package httpconn

import (
	"net"
	"sync"
)

type SingleConnListener struct {
	Conn     net.Conn
	access   sync.Mutex
	accepted bool
	done     chan struct{}
	close    sync.Once
}

func (l *SingleConnListener) Accept() (net.Conn, error) {
	l.access.Lock()
	if l.done == nil {
		l.done = make(chan struct{})
	}
	if l.accepted {
		done := l.done
		l.access.Unlock()
		<-done
		return nil, net.ErrClosed
	}
	l.accepted = true
	conn := &CloseNotifyConn{
		Conn: l.Conn,
		close: func() {
			l.close.Do(func() {
				close(l.done)
			})
		},
	}
	l.access.Unlock()
	return conn, nil
}

func (l *SingleConnListener) Close() error {
	l.access.Lock()
	if l.done == nil {
		l.done = make(chan struct{})
	}
	l.close.Do(func() {
		close(l.done)
	})
	l.access.Unlock()
	if l.Conn == nil {
		return nil
	}
	return l.Conn.Close()
}

func (l *SingleConnListener) Addr() net.Addr {
	if l.Conn == nil {
		return nil
	}
	return l.Conn.LocalAddr()
}
