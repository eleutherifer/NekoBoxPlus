package trusttunnel

import (
	"encoding/binary"
	"math"
	"net"
	"net/netip"
	"sync"

	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/buf"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/rw"
)

type packetConn struct {
	httpConn
	resolveFunc     func(fqdn string) (netip.Addr, error)
	readWaitMutex   sync.Mutex
	readWaitOptions N.ReadWaitOptions
}

const (
	packetAddressPortLen       = 16 + 2
	packetLengthLen            = 4
	packetAppNameLengthLen     = 1
	clientPacketHeaderBaseLen  = packetLengthLen + packetAddressPortLen + packetAddressPortLen + packetAppNameLengthLen
	serverPacketHeaderFixedLen = packetLengthLen + packetAddressPortLen + packetAddressPortLen
)

func packetAppName() string {
	appName := AppName
	if len(appName) > math.MaxUint8 {
		appName = appName[:math.MaxUint8]
	}
	return appName
}

func clientPacketHeaderLen() int {
	return clientPacketHeaderBaseLen + len(packetAppName())
}

func packetWriteBuffer(payload []byte, frontHeadroom int) *buf.Buffer {
	buffer := buf.NewSize(frontHeadroom + len(payload))
	buffer.Resize(frontHeadroom, 0)
	common.Must1(buffer.Write(payload))
	return buffer
}

func (c *packetConn) InitializeReadWaiter(options N.ReadWaitOptions) (needCopy bool) {
	c.readWaitMutex.Lock()
	defer c.readWaitMutex.Unlock()
	c.readWaitOptions = options
	return false
}

func (c *packetConn) newPacketBuffer() *buf.Buffer {
	c.readWaitMutex.Lock()
	options := c.readWaitOptions
	c.readWaitMutex.Unlock()
	return options.NewPacketBuffer()
}

func (c *packetConn) postReturn(buffer *buf.Buffer) {
	c.readWaitMutex.Lock()
	options := c.readWaitOptions
	c.readWaitMutex.Unlock()
	options.PostReturn(buffer)
}

type cachedPacketConn struct {
	N.PacketConn
	mutex       sync.Mutex
	taken       bool
	buffer      *buf.Buffer
	destination M.Socksaddr
}

func newCachedPacketConn(conn N.PacketConn, buffer *buf.Buffer, destination M.Socksaddr) *cachedPacketConn {
	buffer.IncRef()
	return &cachedPacketConn{
		PacketConn:  conn,
		buffer:      buffer,
		destination: destination,
	}
}

func (c *cachedPacketConn) ReadPacket(buffer *buf.Buffer) (destination M.Socksaddr, err error) {
	c.mutex.Lock()
	if c.buffer != nil {
		_, err = buffer.ReadOnceFrom(c.buffer)
		if err != nil {
			c.mutex.Unlock()
			return M.Socksaddr{}, err
		}
		c.buffer.DecRef()
		c.buffer.Release()
		c.buffer = nil
		destination = c.destination
		c.mutex.Unlock()
		return destination, nil
	}
	c.mutex.Unlock()
	return c.PacketConn.ReadPacket(buffer)
}

func (c *cachedPacketConn) ReadCachedPacket() *N.PacketBuffer {
	c.mutex.Lock()
	if c.taken {
		c.mutex.Unlock()
		return nil
	}
	c.taken = true
	buffer := c.buffer
	c.buffer = nil
	destination := c.destination
	c.mutex.Unlock()
	if buffer == nil {
		return nil
	}
	buffer.DecRef()
	packet := N.NewPacketBuffer()
	*packet = N.PacketBuffer{
		Buffer:      buffer,
		Destination: destination,
	}
	return packet
}

func (c *cachedPacketConn) Close() error {
	c.mutex.Lock()
	if !c.taken {
		c.taken = true
		if buffer := c.buffer; buffer != nil {
			c.buffer = nil
			buffer.DecRef()
			buffer.Release()
		}
	}
	c.mutex.Unlock()
	return c.PacketConn.Close()
}

var (
	_ N.NetPacketConn    = (*clientPacketConn)(nil)
	_ N.FrontHeadroom    = (*clientPacketConn)(nil)
	_ N.PacketReadWaiter = (*clientPacketConn)(nil)
)

type clientPacketConn struct {
	packetConn
}

func (u *clientPacketConn) FrontHeadroom() int {
	return clientPacketHeaderLen()
}

func (u *clientPacketConn) WaitReadPacket() (buffer *buf.Buffer, destination M.Socksaddr, err error) {
	buffer = u.newPacketBuffer()
	destination, err = u.ReadPacket(buffer)
	if err != nil {
		buffer.Release()
		return nil, M.Socksaddr{}, err
	}
	u.postReturn(buffer)
	return buffer, destination, nil
}

func (u *clientPacketConn) ReadPacket(buffer *buf.Buffer) (destination M.Socksaddr, err error) {
	err = u.waitCreated()
	if err != nil {
		return M.Socksaddr{}, err
	}
	u.readMutex.Lock()
	defer u.readMutex.Unlock()
	return u.readPacketFromServer(buffer)
}

func (u *clientPacketConn) ReadFrom(p []byte) (n int, addr net.Addr, err error) {
	buffer := buf.With(p)
	destination, err := u.ReadPacket(buffer)
	if err != nil {
		return 0, nil, err
	}
	return buffer.Len(), destination.UDPAddr(), nil
}

func (u *clientPacketConn) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	return u.writePacketToServer(buffer, destination)
}

func (u *clientPacketConn) WriteTo(p []byte, addr net.Addr) (n int, err error) {
	err = u.WritePacket(packetWriteBuffer(p, clientPacketHeaderLen()), M.SocksaddrFromNet(addr))
	if err != nil {
		return 0, err
	}
	return len(p), nil
}

func (u *clientPacketConn) readPacketFromServer(buffer *buf.Buffer) (destination M.Socksaddr, err error) {
	header := buf.NewSize(4 + 16 + 2 + 16 + 2)
	defer header.Release()
	_, err = header.ReadFullFrom(u.body, header.Cap())
	if err != nil {
		err = u.wrapRoundTripperError(err)
		return
	}
	var length uint32
	common.Must(binary.Read(header, binary.BigEndian, &length))
	var sourceAddressBuffer [16]byte
	common.Must1(header.Read(sourceAddressBuffer[:]))
	destination.Addr = parse16BytesIP(sourceAddressBuffer)
	common.Must(binary.Read(header, binary.BigEndian, &destination.Port))
	common.Must(rw.SkipN(header, 16+2)) // To local address:port
	payloadLen := int(length) - (16 + 2 + 16 + 2)
	if payloadLen < 0 {
		return M.Socksaddr{}, E.New("invalid udp length: ", length)
	}
	_, err = buffer.ReadFullFrom(u.body, payloadLen)
	err = u.wrapRoundTripperError(err)
	return
}

func (u *clientPacketConn) writePacketToServer(buffer *buf.Buffer, source M.Socksaddr) error {
	defer buffer.Release()
	if !source.IsIP() {
		if u.resolveFunc == nil {
			return E.New("write to without resolveFunc")
		}
		ip, err := u.resolveFunc(source.Fqdn)
		if err != nil {
			return err
		}
		source.Addr = ip
	}
	appName := packetAppName()
	payloadLen := buffer.Len()
	headerLen := clientPacketHeaderBaseLen + len(appName)
	lengthField := uint32(packetAddressPortLen + packetAddressPortLen + packetAppNameLengthLen + len(appName) + payloadLen)
	destinationAddress := buildPaddingIP(source.Addr)

	var (
		header         *buf.Buffer
		headerInBuffer bool
	)
	if buffer.Start() >= headerLen {
		headerBytes := buffer.ExtendHeader(headerLen)
		header = buf.With(headerBytes)
		headerInBuffer = true
	} else {
		header = buf.NewSize(headerLen)
		defer header.Release()
	}
	common.Must(binary.Write(header, binary.BigEndian, lengthField))
	common.Must(header.WriteZeroN(16 + 2)) // Source address:port (unknown)
	common.Must1(header.Write(destinationAddress[:]))
	common.Must(binary.Write(header, binary.BigEndian, source.Port))
	common.Must(binary.Write(header, binary.BigEndian, uint8(len(appName))))
	common.Must1(header.WriteString(appName))
	u.writeMutex.Lock()
	defer u.writeMutex.Unlock()
	if !headerInBuffer {
		_, err := u.writer.Write(header.Bytes())
		if err != nil {
			return u.wrapRoundTripperError(err)
		}
	}
	_, err := u.writer.Write(buffer.Bytes())
	if err != nil {
		return u.wrapRoundTripperError(err)
	}
	if u.flusher != nil {
		u.flusher.Flush()
	}
	return nil
}

var (
	_ N.NetPacketConn    = (*serverPacketConn)(nil)
	_ N.FrontHeadroom    = (*serverPacketConn)(nil)
	_ N.PacketReadWaiter = (*serverPacketConn)(nil)
)

type serverPacketConn struct {
	packetConn
}

func (u *serverPacketConn) FrontHeadroom() int {
	return serverPacketHeaderFixedLen
}

func (u *serverPacketConn) WaitReadPacket() (buffer *buf.Buffer, destination M.Socksaddr, err error) {
	buffer = u.newPacketBuffer()
	destination, err = u.ReadPacket(buffer)
	if err != nil {
		buffer.Release()
		return nil, M.Socksaddr{}, err
	}
	u.postReturn(buffer)
	return buffer, destination, nil
}

func (u *serverPacketConn) ReadPacket(buffer *buf.Buffer) (destination M.Socksaddr, err error) {
	err = u.waitCreated()
	if err != nil {
		return M.Socksaddr{}, err
	}
	u.readMutex.Lock()
	defer u.readMutex.Unlock()
	return u.readPacketFromClient(buffer)
}

func (u *serverPacketConn) ReadFrom(p []byte) (n int, addr net.Addr, err error) {
	buffer := buf.With(p)
	destination, err := u.ReadPacket(buffer)
	if err != nil {
		return 0, nil, err
	}
	return buffer.Len(), destination.UDPAddr(), nil
}

func (u *serverPacketConn) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	return u.writePacketToClient(buffer, destination)
}

func (u *serverPacketConn) WriteTo(p []byte, addr net.Addr) (n int, err error) {
	err = u.WritePacket(packetWriteBuffer(p, serverPacketHeaderFixedLen), M.SocksaddrFromNet(addr))
	if err != nil {
		return 0, err
	}
	return len(p), nil
}

func (u *serverPacketConn) readPacketFromClient(buffer *buf.Buffer) (destination M.Socksaddr, err error) {
	header := buf.NewSize(4 + 16 + 2 + 16 + 2 + 1)
	defer header.Release()
	_, err = header.ReadFullFrom(u.body, header.Cap())
	if err != nil {
		err = u.wrapRoundTripperError(err)
		return
	}
	var length uint32
	common.Must(binary.Read(header, binary.BigEndian, &length))
	var sourceAddressBuffer [16]byte
	common.Must1(header.Read(sourceAddressBuffer[:]))
	var sourcePort uint16
	common.Must(binary.Read(header, binary.BigEndian, &sourcePort))
	_ = sourcePort
	var destinationAddressBuffer [16]byte
	common.Must1(header.Read(destinationAddressBuffer[:]))
	destination.Addr = parse16BytesIP(destinationAddressBuffer)
	common.Must(binary.Read(header, binary.BigEndian, &destination.Port))
	var appNameLen uint8
	common.Must(binary.Read(header, binary.BigEndian, &appNameLen))
	if appNameLen > 0 {
		err = rw.SkipN(u.body, int(appNameLen))
		if err != nil {
			err = u.wrapRoundTripperError(err)
			return M.Socksaddr{}, err
		}
	}
	payloadLen := int(length) - (16 + 2 + 16 + 2 + 1 + int(appNameLen))
	if payloadLen < 0 {
		return M.Socksaddr{}, E.New("invalid udp length: ", length)
	}
	_, err = buffer.ReadFullFrom(u.body, payloadLen)
	err = u.wrapRoundTripperError(err)
	return
}

func (u *serverPacketConn) writePacketToClient(buffer *buf.Buffer, source M.Socksaddr) error {
	defer buffer.Release()
	if !source.IsIP() {
		return E.New("only support IP")
	}
	payloadLen := buffer.Len()
	headerLen := serverPacketHeaderFixedLen
	lengthField := uint32(packetAddressPortLen + packetAddressPortLen + payloadLen)
	sourceAddress := buildPaddingIP(source.Addr)
	var destinationAddress [16]byte
	var destinationPort uint16
	var (
		header         *buf.Buffer
		headerInBuffer bool
	)
	if buffer.Start() >= headerLen {
		headerBytes := buffer.ExtendHeader(headerLen)
		header = buf.With(headerBytes)
		headerInBuffer = true
	} else {
		header = buf.NewSize(headerLen)
		defer header.Release()
	}
	common.Must(binary.Write(header, binary.BigEndian, lengthField))
	common.Must1(header.Write(sourceAddress[:]))
	common.Must(binary.Write(header, binary.BigEndian, source.Port))
	common.Must1(header.Write(destinationAddress[:]))
	common.Must(binary.Write(header, binary.BigEndian, destinationPort))
	u.writeMutex.Lock()
	defer u.writeMutex.Unlock()
	if !headerInBuffer {
		_, err := u.writer.Write(header.Bytes())
		if err != nil {
			return u.wrapRoundTripperError(err)
		}
	}
	_, err := u.writer.Write(buffer.Bytes())
	if err != nil {
		return u.wrapRoundTripperError(err)
	}
	if u.flusher != nil {
		u.flusher.Flush()
	}
	return nil
}
