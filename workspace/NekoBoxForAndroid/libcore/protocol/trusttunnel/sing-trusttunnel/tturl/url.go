// Package tturl parses and builds TrustTunnel URLs.
package tturl

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"io"
	"strings"

	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/buf"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/rw"
)

const (
	Schema        = "tt"
	Version       = Version1
	Version0 byte = 0
	Version1 byte = 1
)

func IsValidVersion(version byte) bool {
	switch version {
	case Version0, Version1:
		return true
	default:
		return false
	}
}

const (
	TagVersion            uint64 = 0x00
	TagHostname           uint64 = 0x01
	TagAddresses          uint64 = 0x02
	TagCustomSNI          uint64 = 0x03
	TagHasIPv6            uint64 = 0x04 // Always true in original implementation
	TagUsername           uint64 = 0x05
	TagPassword           uint64 = 0x06
	TagSkipVerification   uint64 = 0x07
	TagCertificate        uint64 = 0x08
	TagUpstreamProtocol   uint64 = 0x09
	TagAntiDPI            uint64 = 0x0A
	TagClientRandomPrefix uint64 = 0x0B // Naive and ridiculous design.
	TagName               uint64 = 0x0C
	TagDNSUpstreams       uint64 = 0x0D
)

type UpstreamProtocol byte

const (
	UpstreamProtocolHTTP2 = 0x01
	UpstreamProtocolHTTP3 = 0x02
)

func (u UpstreamProtocol) IsValid() bool {
	switch u {
	case UpstreamProtocolHTTP2, UpstreamProtocolHTTP3:
		return true
	default:
		return false
	}
}

type URL struct {
	Hostname           string
	Addresses          []M.Socksaddr
	CustomSNI          string
	Username           string
	Password           string
	SkipVerification   bool
	Certificate        []byte // der
	UpstreamProtocol   UpstreamProtocol
	AntiDPI            bool
	ClientRandomPrefix string
	Name               string
	DNSUpstreams       []string
}

func Parse(link string) (*URL, error) {
	base64String, found := strings.CutPrefix(link, Schema+"://")
	if !found {
		return nil, E.New("schema is not ", Schema)
	}
	// since draft 2
	// https://github.com/TrustTunnel/TrustTunnel/blob/984817f3b92f5769aedb15e3f90782bc88839825/DEEP_LINK.md?plain=1#L8
	base64String = strings.TrimPrefix(base64String, "?")
	buffer := buf.NewSize(base64.RawURLEncoding.DecodedLen(len(base64String)))
	defer buffer.Release()
	n, err := base64.RawURLEncoding.Decode(buffer.FreeBytes(), []byte(base64String))
	if err != nil {
		return nil, err
	}
	buffer.Truncate(n)

	url := new(URL)
	err = parseTLV(buffer, url)
	if err != nil {
		return nil, err
	}
	url.applyDefaults()
	err = url.requireValid()
	if err != nil {
		return nil, err
	}
	return url, nil
}

func parseTLV(buffer *buf.Buffer, url *URL) error {
	for {
		tag, err := readVarint(buffer)
		if err != nil {
			if err == io.EOF {
				break
			}
			return err
		}

		err = parseTag(buffer, url, tag)
		if err != nil {
			return err
		}
	}
	return nil
}

func parseTag(buffer *buf.Buffer, url *URL, tag uint64) error {
	switch tag {
	case TagVersion:
		version, err := readTLVByte(buffer, tag)
		if err != nil {
			return err
		}
		if !IsValidVersion(version) {
			return E.New("unexpected version: ", version)
		}
	case TagHostname:
		value, err := readTLVString(buffer, tag)
		if err != nil {
			return err
		}
		url.Hostname = value
	case TagAddresses:
		value, err := readTLVString(buffer, tag)
		if err != nil {
			return err
		}
		address := M.ParseSocksaddr(value)
		if !address.IsValid() || address.Port == 0 {
			return E.New("invalid address: ", value)
		}
		url.Addresses = append(url.Addresses, address)
	case TagCustomSNI:
		value, err := readTLVString(buffer, tag)
		if err != nil {
			return err
		}
		url.CustomSNI = value
	case TagUsername:
		value, err := readTLVString(buffer, tag)
		if err != nil {
			return err
		}
		url.Username = value
	case TagPassword:
		value, err := readTLVString(buffer, tag)
		if err != nil {
			return err
		}
		url.Password = value
	case TagSkipVerification:
		value, err := readTLVBool(buffer, tag)
		if err != nil {
			return err
		}
		url.SkipVerification = value
	case TagCertificate:
		value, err := readTLVBytes(buffer, tag)
		if err != nil {
			return err
		}
		url.Certificate = value
	case TagUpstreamProtocol:
		value, err := readTLVByte(buffer, tag)
		if err != nil {
			return err
		}
		upstreamProtocol := UpstreamProtocol(value)
		if !upstreamProtocol.IsValid() {
			return E.New("invalid upstream protocol: ", byte(upstreamProtocol))
		}
		url.UpstreamProtocol = upstreamProtocol
	case TagAntiDPI:
		value, err := readTLVBool(buffer, tag)
		if err != nil {
			return err
		}
		url.AntiDPI = value
	case TagClientRandomPrefix:
		value, err := readTLVString(buffer, tag)
		if err != nil {
			return err
		}
		url.ClientRandomPrefix = value
	case TagName:
		value, err := readTLVString(buffer, tag)
		if err != nil {
			return err
		}
		url.Name = value
	case TagDNSUpstreams:
		value, err := readTLVStringArray(buffer, tag)
		if err != nil {
			return err
		}
		url.DNSUpstreams = value
	case TagHasIPv6:
		fallthrough
	default:
		return skipTLV(buffer, tag)
	}
	return nil
}

func readTLVString(buffer *buf.Buffer, tag uint64) (string, error) {
	value, err := readTLVBytes(buffer, tag)
	if err != nil {
		return "", err
	}
	return string(value), nil
}

func readTLVBool(buffer *buf.Buffer, tag uint64) (bool, error) {
	value, err := readTLVByte(buffer, tag)
	if err != nil {
		return false, err
	}
	switch value {
	case 0x00:
		return false, nil
	case 0x01:
		return true, nil
	default:
		return false, E.New("invalid boolean value of tag ", tag, ": ", value)
	}
}

func readTLVByte(buffer *buf.Buffer, tag uint64) (byte, error) {
	value, err := readTLVFixedBytes(buffer, tag, 1)
	if err != nil {
		return 0, err
	}
	return value[0], nil
}

func readTLVFixedBytes(buffer *buf.Buffer, tag uint64, expectLength uint64) ([]byte, error) {
	length, err := readTLVLength(buffer, tag)
	if err != nil {
		return nil, err
	}
	if length != expectLength {
		if tag == TagVersion {
			return nil, E.New("invalid version length: ", length)
		}
		return nil, E.New("invalid length of tag ", tag, ": ", length)
	}
	readBuffer := make([]byte, int(length))
	_, err = io.ReadFull(buffer, readBuffer)
	if err != nil {
		return nil, err
	}
	return readBuffer, nil
}

func readTLVBytes(buffer *buf.Buffer, tag uint64) ([]byte, error) {
	length, err := readTLVLength(buffer, tag)
	if err != nil {
		return nil, err
	}
	if length == 0 {
		return nil, nil
	}
	readBuffer := make([]byte, int(length))
	_, err = io.ReadFull(buffer, readBuffer)
	if err != nil {
		return nil, err
	}
	return readBuffer, nil
}

func readTLVStringArray(buffer *buf.Buffer, tag uint64) ([]string, error) {
	data, err := readTLVBytes(buffer, tag)
	if err != nil {
		return nil, err
	}
	if len(data) == 0 {
		return nil, nil
	}
	reader := bytes.NewReader(data)
	var result []string
	for reader.Len() > 0 {
		length, err := readVarint(reader)
		if err != nil {
			return nil, err
		}
		if length > uint64(reader.Len()) {
			return nil, E.New("invalid string array length of tag ", tag, ": ", length, ", remaining: ", reader.Len())
		}
		item := make([]byte, int(length))
		_, err = io.ReadFull(reader, item)
		if err != nil {
			return nil, err
		}
		result = append(result, string(item))
	}
	return result, nil
}

func readTLVLength(buffer *buf.Buffer, tag uint64) (uint64, error) {
	length, err := readVarint(buffer)
	if err != nil {
		return 0, err
	}
	remaining := buffer.Len()
	if length > uint64(buffer.Len()) {
		return 0, E.New("invalid length of tag ", tag, ": ", length, ", remaining: ", remaining)
	}
	return length, nil
}

func skipTLV(buffer *buf.Buffer, tag uint64) error {
	length, err := readTLVLength(buffer, tag)
	if err != nil {
		return err
	}
	return rw.SkipN(buffer, int(length))
}

func (u *URL) applyDefaults() {
	if u.UpstreamProtocol == 0 {
		u.UpstreamProtocol = UpstreamProtocolHTTP2
	}
}

func validateClientRandomPrefix(value string) error {
	if value == "" {
		return nil
	}
	prefix, mask, hasMask := strings.Cut(value, "/")
	if prefix == "" {
		return E.New("client random prefix is empty")
	}
	prefixBytes, err := hex.DecodeString(prefix)
	if err != nil {
		return E.Cause(err, "decode client random prefix")
	}
	if len(prefixBytes) > 32 {
		return E.New("client random prefix is too long: ", len(prefixBytes))
	}
	if !hasMask {
		return nil
	}
	if mask == "" {
		return E.New("client random mask is empty")
	}
	maskBytes, err := hex.DecodeString(mask)
	if err != nil {
		return E.Cause(err, "decode client random mask")
	}
	if len(maskBytes) > 32 {
		return E.New("client random mask is too long: ", len(maskBytes))
	}
	if len(maskBytes) != len(prefixBytes) {
		return E.New("client random prefix and mask length mismatch: ", len(prefixBytes), " != ", len(maskBytes))
	}
	return nil
}

func (u URL) requireValid() error {
	if u.Hostname == "" {
		return E.New("missing hostname")
	}
	if len(u.Addresses) == 0 {
		return E.New("missing addresses")
	}
	if invalidIndex := common.Index(u.Addresses, func(it M.Socksaddr) bool {
		return !it.IsValid() || it.Port == 0
	}); invalidIndex >= 0 {
		return E.New("address [", invalidIndex, "] is invalid")
	}
	if u.Username == "" {
		return E.New("missing username")
	}
	if u.Password == "" {
		return E.New("missing password")
	}
	if !u.UpstreamProtocol.IsValid() {
		return E.New("invalid upstream protocol ", byte(u.UpstreamProtocol))
	}
	if err := validateClientRandomPrefix(u.ClientRandomPrefix); err != nil {
		return err
	}
	return nil
}

func (u URL) Build() (string, error) {
	u.applyDefaults()
	err := u.requireValid()
	if err != nil {
		return "", err
	}
	builder := bytes.NewBuffer(nil)
	err = writeTLV(builder, TagVersion, Version)
	if err != nil {
		return "", E.Cause(err, "write version")
	}
	err = writeTLV(builder, TagHostname, u.Hostname)
	if err != nil {
		return "", E.Cause(err, "write hostname")
	}
	for i, address := range u.Addresses {
		err = writeTLV(builder, TagAddresses, address.String())
		if err != nil {
			return "", E.Cause(err, "write address ", i)
		}
	}
	if u.CustomSNI != "" {
		err = writeTLV(builder, TagCustomSNI, u.CustomSNI)
		if err != nil {
			return "", E.Cause(err, "write custom sni")
		}
	}
	err = writeTLV(builder, TagUsername, u.Username)
	if err != nil {
		return "", E.Cause(err, "write username")
	}
	err = writeTLV(builder, TagPassword, u.Password)
	if err != nil {
		return "", E.Cause(err, "write password")
	}
	if u.ClientRandomPrefix != "" {
		err = writeTLV(builder, TagClientRandomPrefix, u.ClientRandomPrefix)
		if err != nil {
			return "", E.Cause(err, "write client random prefix")
		}
	}
	if u.SkipVerification {
		err = writeTLV(builder, TagSkipVerification, true)
		if err != nil {
			return "", E.Cause(err, "write skip verification")
		}
	}
	if cert := u.Certificate; len(cert) > 0 {
		err = writeTLV(builder, TagCertificate, cert)
		if err != nil {
			return "", E.Cause(err, "write certificate")
		}
	}
	if u.UpstreamProtocol != UpstreamProtocolHTTP2 {
		err = writeTLV(builder, TagUpstreamProtocol, byte(u.UpstreamProtocol))
		if err != nil {
			return "", E.Cause(err, "write upstream protocol")
		}
	}
	if u.AntiDPI {
		err = writeTLV(builder, TagAntiDPI, true)
		if err != nil {
			return "", E.Cause(err, "write anti-dpi")
		}
	}
	if name := u.Name; name != "" {
		err = writeTLV(builder, TagName, name)
		if err != nil {
			return "", E.Cause(err, "write name")
		}
	}
	if len(u.DNSUpstreams) > 0 {
		err = writeTLV(builder, TagDNSUpstreams, u.DNSUpstreams)
		if err != nil {
			return "", E.Cause(err, "write dns upstreams")
		}
	}

	return Schema + "://?" + base64.RawURLEncoding.EncodeToString(builder.Bytes()), nil
}

func writeTLV(writer io.Writer, tag uint64, data any) (err error) {
	err = writeVarint(writer, tag)
	if err != nil {
		return
	}
	switch data := data.(type) {
	case byte:
		_, err = writer.Write([]byte{1, data})
		return
	case bool:
		buffer := [2]byte{1, 0}
		if data {
			buffer[1] = 0x01
		} else {
			buffer[1] = 0x00
		}
		_, err = writer.Write(buffer[:])
		return
	case string:
		length := uint64(len(data))
		err = writeVarint(writer, length)
		if err != nil {
			return
		}
		_, err = io.WriteString(writer, data)
		return
	case []byte:
		length := uint64(len(data))
		err = writeVarint(writer, length)
		if err != nil {
			return
		}
		_, err = writer.Write(data)
		return
	case []string:
		var buffer bytes.Buffer
		for i, value := range data {
			err = writeVarint(&buffer, uint64(len(value)))
			if err != nil {
				return E.Cause(err, "write string array length ", i)
			}
			_, err = io.WriteString(&buffer, value)
			if err != nil {
				return E.Cause(err, "write string array ", i)
			}
		}
		err = writeVarint(writer, uint64(buffer.Len()))
		if err != nil {
			return E.Cause(err, "write string array tlv length")
		}
		_, err = writer.Write(buffer.Bytes())
		return
	default:
		panic("unexpected data type")
	}
}

const (
	maxVarInt1 = 63
	maxVarInt2 = 16383
	maxVarInt4 = 1073741823
	maxVarInt8 = 4611686018427387903
)

func writeVarint(writer io.Writer, value uint64) error {
	var encodedBytes []byte
	var scratch [8]byte
	switch {
	case value <= maxVarInt1:
		scratch[0] = byte(value)
		encodedBytes = scratch[:1]
	case value <= maxVarInt2:
		binary.BigEndian.PutUint16(scratch[:2], uint16(value))
		scratch[0] |= 0x40 // 01xxxxxx
		encodedBytes = scratch[:2]
	case value <= maxVarInt4:
		binary.BigEndian.PutUint32(scratch[:4], uint32(value))
		scratch[0] |= 0x80 // 10xxxxxx
		encodedBytes = scratch[:4]
	case value <= maxVarInt8:
		binary.BigEndian.PutUint64(scratch[:8], value)
		scratch[0] |= 0xc0 // 11xxxxxx
		encodedBytes = scratch[:8]
	default:
		return E.New("varint too large: ", value)
	}
	return common.Error(writer.Write(encodedBytes))
}

func readVarint(reader io.Reader) (uint64, error) {
	var scratch [8]byte
	_, err := io.ReadFull(reader, scratch[:1])
	if err != nil {
		return 0, err
	}
	sizeCode := scratch[0] >> 6
	byteLength := 1 << sizeCode
	scratch[0] &= 0x3f
	if byteLength == 1 {
		return uint64(scratch[0]), nil
	}
	_, err = io.ReadFull(reader, scratch[1:byteLength])
	if err != nil {
		return 0, err
	}
	switch byteLength {
	case 2:
		return uint64(binary.BigEndian.Uint16(scratch[:2])), nil
	case 4:
		return uint64(binary.BigEndian.Uint32(scratch[:4])), nil
	case 8:
		return binary.BigEndian.Uint64(scratch[:8]), nil
	default:
		return 0, E.New("impossible varint length: ", byteLength)
	}
}
