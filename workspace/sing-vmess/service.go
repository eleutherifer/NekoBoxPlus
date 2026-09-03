package vmess

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/md5"
	"crypto/sha256"
	"encoding/binary"
	"hash/crc32"
	"io"
	"math"
	"net"
	"sync"
	"time"
	"unsafe"

	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/replay"
	"github.com/sagernet/sing/common/rw"

	"github.com/gofrs/uuid/v5"
)

type Handler interface {
	N.TCPConnectionHandlerEx
	N.UDPConnectionHandlerEx
}

var (
	ErrBadHeader    = E.New("bad header")
	ErrBadTimestamp = E.New("bad timestamp")
	ErrReplay       = E.New("replayed request")
	ErrBadRequest   = E.New("bad request")
	ErrBadVersion   = E.New("bad version")
)

type Service[U comparable] struct {
	userKey              map[U][16]byte
	userIdCipher         map[U]cipher.Block
	replayFilter         replay.Filter
	handler              Handler
	time                 func() time.Time
	disableHeaderProtect bool
	alterIds             map[U][][16]byte
	alterIdUpdateTime    map[U]int64
	alterIdMap           map[[16]byte]legacyUserEntry[U]
	alterIdUpdateTask    *time.Ticker
	alterIdUpdateDone    chan struct{}
	connections          map[U]map[*trackedConnection[U]]struct{}
	userRevision         uint64

	userAccess sync.RWMutex
}

type trackedConnection[U comparable] struct {
	net.Conn
	service *Service[U]
	user    U
	once    sync.Once
}

type legacyUserEntry[U comparable] struct {
	User  U
	Time  int64
	Index int
}

func NewService[U comparable](handler Handler, options ...ServiceOption) *Service[U] {
	service := &Service[U]{
		replayFilter: replay.NewSimple(time.Second * 120),
		handler:      handler,
		time:         time.Now,
		connections:  make(map[U]map[*trackedConnection[U]]struct{}),
	}
	anyService := (*Service[string])(unsafe.Pointer(service))
	for _, option := range options {
		option(anyService)
	}
	return service
}

func (s *Service[U]) UpdateUsers(userList []U, userIdList []string, alterIdList []int) error {
	userKeyMap := make(map[U][16]byte)
	userIdCipherMap := make(map[U]cipher.Block)
	userAlterIds := make(map[U][][16]byte)
	for i, user := range userList {
		userId := userIdList[i]
		userUUID, err := uuid.FromString(userId)
		if err != nil {
			userUUID = uuid.NewV5(uuid.Nil, userId)
		}
		userCmdKey := Key(userUUID)
		userKeyMap[user] = userCmdKey
		userIdCipher, err := aes.NewCipher(KDF(userCmdKey[:], KDFSaltConstAuthIDEncryptionKey)[:16])
		if err != nil {
			return err
		}
		userIdCipherMap[user] = userIdCipher
		alterId := alterIdList[i]
		if alterId > 0 {
			alterIds := make([][16]byte, 0, alterId)
			currentId := userUUID
			for j := 0; j < alterId; j++ {
				currentId = AlterId(currentId)
				alterIds = append(alterIds, currentId)
			}
			userAlterIds[user] = alterIds
		}
	}
	s.userAccess.Lock()
	s.userKey = userKeyMap
	s.userIdCipher = userIdCipherMap
	s.alterIds = userAlterIds
	s.alterIdUpdateTime = make(map[U]int64)
	s.generateLegacyKeysLocked()
	s.userRevision++
	var removedConnections []*trackedConnection[U]
	for user, connections := range s.connections {
		if _, loaded := userKeyMap[user]; !loaded {
			for connection := range connections {
				removedConnections = append(removedConnections, connection)
			}
			delete(s.connections, user)
		}
	}
	s.userAccess.Unlock()
	for _, connection := range removedConnections {
		_ = connection.Close()
	}
	return nil
}

func (s *Service[U]) Start() error {
	const updateInterval = 10 * time.Second
	s.userAccess.RLock()
	hasAlterIds := len(s.alterIds) > 0
	s.userAccess.RUnlock()
	if hasAlterIds {
		s.alterIdUpdateTask = time.NewTicker(updateInterval)
		s.alterIdUpdateDone = make(chan struct{})
		go s.loopGenerateLegacyKeys()
	}
	return nil
}

func (s *Service[U]) Close() error {
	if s.alterIdUpdateTask != nil {
		s.alterIdUpdateTask.Stop()
		close(s.alterIdUpdateDone)
	}
	return nil
}

func (s *Service[U]) loopGenerateLegacyKeys() {
	for {
		select {
		case <-s.alterIdUpdateDone:
			return
		case <-s.alterIdUpdateTask.C:
		}
		s.userAccess.Lock()
		s.generateLegacyKeysLocked()
		s.userAccess.Unlock()
	}
}

func (s *Service[U]) generateLegacyKeysLocked() {
	nowSec := s.time().Unix()
	endSec := nowSec + CacheDurationSeconds
	var hashValue [16]byte

	userAlterIdMap := make(map[[16]byte]legacyUserEntry[U])
	userAlterIdUpdateTime := make(map[U]int64)

	for user, alterIds := range s.alterIds {
		beginSec := s.alterIdUpdateTime[user]
		if beginSec < nowSec-CacheDurationSeconds {
			beginSec = nowSec - CacheDurationSeconds
		}
		for i, alterId := range alterIds {
			idHash := hmac.New(md5.New, alterId[:])
			for ts := beginSec; ts <= endSec; ts++ {
				common.Must(binary.Write(idHash, binary.BigEndian, uint64(ts)))
				idHash.Sum(hashValue[:0])
				idHash.Reset()
				userAlterIdMap[hashValue] = legacyUserEntry[U]{user, ts, i}
			}
		}
		userAlterIdUpdateTime[user] = nowSec
	}
	s.alterIdUpdateTime = userAlterIdUpdateTime
	s.alterIdMap = userAlterIdMap
}

func (s *Service[U]) NewConnection(ctx context.Context, conn net.Conn, source M.Socksaddr, onClose N.CloseHandlerFunc) error {
	const headerLenBufferLen = 2 + CipherOverhead
	const aeadMinHeaderLen = 16 + headerLenBufferLen + 8 + CipherOverhead + 42
	minHeaderLen := aeadMinHeaderLen
	s.userAccess.RLock()
	hasAlterIds := len(s.alterIds) > 0
	s.userAccess.RUnlock()
	if hasAlterIds {
		minHeaderLen = 16 + 38
	}

	requestBuffer := buf.New()
	defer requestBuffer.Release()

	if !s.disableHeaderProtect {
		n, err := requestBuffer.ReadOnceFrom(conn)
		if err != nil {
			return err
		}
		if n < minHeaderLen {
			return ErrBadHeader
		}
	} else {
		_, err := requestBuffer.ReadAtLeastFrom(conn, minHeaderLen)
		if err != nil {
			return err
		}
	}

	authId := requestBuffer.To(16)
	var decodedId [16]byte
	var user U
	var found bool
	var cmdKey [16]byte
	var userRevision uint64
	s.userAccess.RLock()
	for currUser, userIdBlock := range s.userIdCipher {
		userIdBlock.Decrypt(decodedId[:], authId)
		timestamp := int64(binary.BigEndian.Uint64(decodedId[:]))
		checksum := binary.BigEndian.Uint32(decodedId[12:])
		if crc32.ChecksumIEEE(decodedId[:12]) != checksum {
			continue
		}
		if math.Abs(math.Abs(float64(timestamp))-float64(time.Now().Unix())) > 120 {
			s.userAccess.RUnlock()
			return ErrBadTimestamp
		}
		if !s.replayFilter.Check(decodedId[:]) {
			s.userAccess.RUnlock()
			return ErrReplay
		}
		user = currUser
		found = true
		break
	}

	var legacyProtocol bool
	var legacyTimestamp uint64
	if !found {
		copy(decodedId[:], authId)
		if currUser, loaded := s.alterIdMap[decodedId]; loaded {
			found = true
			legacyProtocol = true
			user = currUser.User
			legacyTimestamp = uint64(currUser.Time)
		}
	}
	if !found {
		s.userAccess.RUnlock()
		return ErrBadRequest
	}

	ctx = auth.ContextWithUser(ctx, user)
	cmdKey = s.userKey[user]
	userRevision = s.userRevision
	s.userAccess.RUnlock()
	var headerReader io.Reader
	var headerBuffer []byte

	var reader io.Reader
	var err error
	if legacyProtocol {
		requestBuffer.Advance(16)
		reader = io.MultiReader(bytes.NewReader(requestBuffer.Bytes()), conn)

		timeHash := md5.New()
		common.Must(binary.Write(timeHash, binary.BigEndian, legacyTimestamp))
		common.Must(binary.Write(timeHash, binary.BigEndian, legacyTimestamp))
		common.Must(binary.Write(timeHash, binary.BigEndian, legacyTimestamp))
		common.Must(binary.Write(timeHash, binary.BigEndian, legacyTimestamp))
		userKey := s.userKey[user]
		headerReader = NewStreamReader(reader, userKey[:], timeHash.Sum(nil))
		headerBuffer = make([]byte, 38)
		_, err = io.ReadFull(headerReader, headerBuffer)
		if err != nil {
			return E.Extend(ErrBadHeader, io.ErrShortBuffer)
		}
	} else {
		if requestBuffer.Len() < aeadMinHeaderLen {
			return ErrBadHeader
		}

		reader = conn

		const nonceIndex = 16 + headerLenBufferLen
		connectionNonce := requestBuffer.Range(nonceIndex, nonceIndex+8)

		lengthKey := KDF(cmdKey[:], KDFSaltConstVMessHeaderPayloadLengthAEADKey, authId, connectionNonce)[:16]
		lengthNonce := KDF(cmdKey[:], KDFSaltConstVMessHeaderPayloadLengthAEADIV, authId, connectionNonce)[:12]
		lengthBuffer, err := newAesGcm(lengthKey).Open(requestBuffer.Index(16), lengthNonce, requestBuffer.Range(16, nonceIndex), authId)
		if err != nil {
			return err
		}

		const headerIndex = nonceIndex + 8
		headerLength := int(binary.BigEndian.Uint16(lengthBuffer))
		needRead := headerLength + headerIndex + CipherOverhead - requestBuffer.Len()
		if needRead > 0 {
			_, err = requestBuffer.ReadFullFrom(conn, needRead)
			if err != nil {
				return err
			}
		}

		headerKey := KDF(cmdKey[:], KDFSaltConstVMessHeaderPayloadAEADKey, authId, connectionNonce)[:16]
		headerNonce := KDF(cmdKey[:], KDFSaltConstVMessHeaderPayloadAEADIV, authId, connectionNonce)[:12]
		headerBuffer, err = newAesGcm(headerKey).Open(requestBuffer.Index(headerIndex), headerNonce, requestBuffer.Range(headerIndex, headerIndex+headerLength+CipherOverhead), authId)
		if err != nil {
			return err
		}
		// replace with < if support mux
		if len(headerBuffer) <= 38 {
			return E.Extend(ErrBadHeader, io.ErrShortBuffer)
		}
		requestBuffer.Advance(headerIndex + headerLength + CipherOverhead)
		headerReader = bytes.NewReader(headerBuffer[38:])
	}

	version := headerBuffer[0]
	if version != Version {
		return E.Extend(ErrBadVersion, version)
	}

	requestBodyKey := make([]byte, 16)
	requestBodyNonce := make([]byte, 16)

	copy(requestBodyKey, headerBuffer[17:33])
	copy(requestBodyNonce, headerBuffer[1:17])

	responseHeader := headerBuffer[33]
	option := headerBuffer[34]
	paddingLen := int(headerBuffer[35] >> 4)
	security := headerBuffer[35] & 0x0F
	command := headerBuffer[37]
	switch command {
	case CommandTCP, CommandUDP, CommandMux:
	default:
		return E.New("unknown command: ", command)
	}
	if command == CommandUDP && option == 0 {
		return E.New("bad packet connection")
	}
	var destination M.Socksaddr
	if command != CommandMux {
		destination, err = AddressSerializer.ReadAddrPort(headerReader)
		if err != nil {
			return err
		}
	}
	if paddingLen > 0 {
		_, err = io.CopyN(io.Discard, headerReader, int64(paddingLen))
		if err != nil {
			return E.Extend(ErrBadHeader, "bad padding")
		}
	}
	err = rw.SkipN(headerReader, 4)
	if err != nil {
		return err
	}
	if !legacyProtocol && requestBuffer.Len() > 0 {
		reader = bufio.NewCachedReader(reader, requestBuffer.ToOwned())
	}
	reader = CreateReader(reader, nil, requestBodyKey, requestBodyNonce, requestBodyKey, requestBodyNonce, security, option)
	if option&RequestOptionChunkStream != 0 && command == CommandTCP || command == CommandMux {
		reader = bufio.NewChunkReader(reader, ReadChunkSize)
	}
	trackedConn, loaded := s.trackConnection(user, userRevision, conn)
	if !loaded {
		return ErrBadRequest
	}
	rawConn := rawServerConn{
		Conn:           trackedConn,
		legacyProtocol: legacyProtocol,
		requestKey:     requestBodyKey,
		requestNonce:   requestBodyNonce,
		responseHeader: responseHeader,
		security:       security,
		option:         option,
		reader:         bufio.NewExtendedReader(reader),
	}

	switch command {
	case CommandTCP:
		s.handler.NewConnectionEx(ctx, &serverConn{rawConn}, source, destination, trackedConn.onClose(onClose))
	case CommandUDP:
		s.handler.NewPacketConnectionEx(ctx, &serverPacketConn{rawConn, destination}, source, destination, trackedConn.onClose(onClose))
	case CommandMux:
		defer trackedConn.remove()
		return HandleMuxConnection(ctx, &serverConn{rawConn}, source, s.handler)
	default:
		return E.New("unknown command: ", command)
	}
	return nil
}

func (s *Service[U]) trackConnection(user U, revision uint64, conn net.Conn) (*trackedConnection[U], bool) {
	s.userAccess.Lock()
	defer s.userAccess.Unlock()
	if revision != s.userRevision {
		return nil, false
	}
	trackedConn := &trackedConnection[U]{
		Conn:    conn,
		service: s,
		user:    user,
	}
	connections := s.connections[user]
	if connections == nil {
		connections = make(map[*trackedConnection[U]]struct{})
		s.connections[user] = connections
	}
	connections[trackedConn] = struct{}{}
	return trackedConn, true
}

func (c *trackedConnection[U]) remove() {
	c.once.Do(func() {
		c.service.userAccess.Lock()
		connections := c.service.connections[c.user]
		delete(connections, c)
		if len(connections) == 0 {
			delete(c.service.connections, c.user)
		}
		c.service.userAccess.Unlock()
	})
}

func (c *trackedConnection[U]) onClose(parent N.CloseHandlerFunc) N.CloseHandlerFunc {
	return N.OnceClose(func(err error) {
		c.remove()
		if parent != nil {
			parent(err)
		}
	})
}

func (c *trackedConnection[U]) Close() error {
	c.remove()
	return c.Conn.Close()
}

var _ N.EarlyWriter = (*rawServerConn)(nil)

type rawServerConn struct {
	net.Conn
	legacyProtocol bool
	requestKey     []byte
	requestNonce   []byte
	responseHeader byte
	security       byte
	option         byte
	reader         N.ExtendedReader
	writer         N.ExtendedWriter
}

func (c *rawServerConn) writeResponse() error {
	if c.legacyProtocol {
		responseKey := md5.Sum(c.requestKey)
		responseNonce := md5.Sum(c.requestNonce)
		headerWriter := NewStreamWriter(c.Conn, responseKey[:], responseNonce[:])
		_, err := headerWriter.Write([]byte{c.responseHeader, c.option, 0, 0})
		if err != nil {
			return E.Cause(err, "write response")
		}
		c.writer = bufio.NewExtendedWriter(CreateWriter(c.Conn, headerWriter, c.requestKey, c.requestNonce, responseKey[:], responseNonce[:], c.security, c.option))
	} else {
		responseBuffer := buf.NewSize(2 + CipherOverhead + 4 + CipherOverhead)
		defer responseBuffer.Release()

		_responseKey := sha256.Sum256(c.requestKey[:])
		responseKey := _responseKey[:16]
		_responseNonce := sha256.Sum256(c.requestNonce[:])
		responseNonce := _responseNonce[:16]

		headerLenKey := KDF(responseKey, KDFSaltConstAEADRespHeaderLenKey)[:16]
		headerLenNonce := KDF(responseNonce, KDFSaltConstAEADRespHeaderLenIV)[:12]
		headerLenCipher := newAesGcm(headerLenKey)
		binary.BigEndian.PutUint16(responseBuffer.Extend(2), 4)
		headerLenCipher.Seal(responseBuffer.Index(0), headerLenNonce, responseBuffer.Bytes(), nil)
		responseBuffer.Extend(CipherOverhead)

		headerKey := KDF(responseKey, KDFSaltConstAEADRespHeaderPayloadKey)[:16]
		headerNonce := KDF(responseNonce, KDFSaltConstAEADRespHeaderPayloadIV)[:12]
		headerCipher := newAesGcm(headerKey)
		common.Must(
			responseBuffer.WriteByte(c.responseHeader),
			responseBuffer.WriteByte(c.option),
			responseBuffer.WriteZeroN(2),
		)
		const headerIndex = 2 + CipherOverhead
		headerCipher.Seal(responseBuffer.Index(headerIndex), headerNonce, responseBuffer.From(headerIndex), nil)
		responseBuffer.Extend(CipherOverhead)

		_, err := c.Conn.Write(responseBuffer.Bytes())
		if err != nil {
			return err
		}

		c.writer = bufio.NewExtendedWriter(CreateWriter(c.Conn, nil, c.requestKey, c.requestNonce, responseKey, responseNonce, c.security, c.option))
	}
	return nil
}

func (c *rawServerConn) Close() error {
	return common.Close(
		c.Conn,
		c.reader,
	)
}

func (c *rawServerConn) FrontHeadroom() int {
	return MaxFrontHeadroom
}

func (c *rawServerConn) RearHeadroom() int {
	return MaxRearHeadroom
}

func (c *rawServerConn) ReaderOverhead() int {
	return ReaderOverhead(c.security, c.option)
}

func (c *rawServerConn) NeedHandshakeForWrite() bool {
	return c.writer == nil
}

func (c *rawServerConn) NeedAdditionalReadDeadline() bool {
	return true
}

func (c *rawServerConn) Upstream() any {
	return c.Conn
}

type serverConn struct {
	rawServerConn
}

func (c *serverConn) Read(b []byte) (n int, err error) {
	return c.reader.Read(b)
}

func (c *serverConn) Write(b []byte) (n int, err error) {
	if c.writer == nil {
		err = c.writeResponse()
		if err != nil {
			return
		}
	}
	return c.writer.Write(b)
}

func (c *serverConn) ReadBuffer(buffer *buf.Buffer) error {
	return c.reader.ReadBuffer(buffer)
}

func (c *serverConn) WriteBuffer(buffer *buf.Buffer) error {
	if c.writer == nil {
		err := c.writeResponse()
		if err != nil {
			buffer.Release()
			return err
		}
	}
	return c.writer.WriteBuffer(buffer)
}

func (c *serverConn) WriteTo(w io.Writer) (n int64, err error) {
	return bufio.Copy(w, c.reader)
}

func (c *serverConn) ReadFrom(r io.Reader) (n int64, err error) {
	if c.writer == nil {
		err = c.writeResponse()
		if err != nil {
			return
		}
	}
	return bufio.Copy(c.writer, r)
}

var _ PacketConn = (*serverPacketConn)(nil)

type serverPacketConn struct {
	rawServerConn
	destination M.Socksaddr
}

func (c *serverPacketConn) ReadFrom(p []byte) (n int, addr net.Addr, err error) {
	n, err = c.reader.Read(p)
	if err != nil {
		return
	}
	addr = c.destination.UDPAddr()
	return
}

func (c *serverPacketConn) WriteTo(p []byte, addr net.Addr) (n int, err error) {
	if c.writer == nil {
		err = c.writeResponse()
		if err != nil {
			return
		}
	}
	return c.writer.Write(p)
}

func (c *serverPacketConn) ReadPacket(buffer *buf.Buffer) (destination M.Socksaddr, err error) {
	err = c.reader.ReadBuffer(buffer)
	if err != nil {
		return
	}
	destination = c.destination
	return
}

func (c *serverPacketConn) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	if c.writer == nil {
		err := c.writeResponse()
		if err != nil {
			return err
		}
	}
	return c.writer.WriteBuffer(buffer)
}
