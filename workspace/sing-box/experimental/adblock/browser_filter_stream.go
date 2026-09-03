//go:build with_adblock

package adblock

import (
	"bytes"
	"io"
	"sync"
)

const (
	streamingHTMLInjectionLookahead = 64 * 1024
	streamingHTMLMetaTagLimit       = 64 * 1024
	streamingHTMLReadSize           = 32 * 1024
)

// ASCII tokens searched for while streaming HTML. Kept as package-level
// []byte values so the hot path never pays for a string->[]byte conversion.
var (
	streamingMetaToken           = []byte("<meta")
	streamingHTTPEquivToken      = []byte("http-equiv")
	streamingCSPToken            = []byte("content-security-policy")
	streamingContentToken        = []byte("content=")
	streamingGtByte         byte = '>'
)

var streamingHTMLInsertionMarkers = []struct {
	marker          []byte
	insertAfterOpen bool
}{
	{marker: []byte("<head"), insertAfterOpen: true},
	{marker: []byte("<html"), insertAfterOpen: true},
	{marker: []byte("<body"), insertAfterOpen: true},
	{marker: []byte("</head>")},
}

// streamingReadBufPool reuses the 32 KiB read buffer across responses. The
// buffer's bytes are always copied into the streaming "pending" slice before
// the next read, so pooling it is safe and removes one allocation per document.
var streamingReadBufPool = sync.Pool{
	New: func() any { b := make([]byte, streamingHTMLReadSize); return &b },
}

func newStreamingHTMLFilterReadCloser(source io.ReadCloser, injection []byte, styleSource string, scriptSource string) io.ReadCloser {
	reader, writer := io.Pipe()
	go func() {
		err := streamHTMLFilter(writer, source, injection, styleSource, scriptSource)
		closeErr := source.Close()
		if err == nil {
			err = closeErr
		}
		_ = writer.CloseWithError(err)
	}()
	return reader
}

func streamHTMLFilter(writer io.Writer, source io.Reader, injection []byte, styleSource string, scriptSource string) error {
	patcher := streamingMetaCSPPatcher{
		writer:       writer,
		styleSource:  styleSource,
		scriptSource: scriptSource,
	}
	bufPtr := streamingReadBufPool.Get().(*[]byte)
	readBuffer := *bufPtr
	// Return the scratch buffer exactly once when streaming finishes. The
	// buffer contents are never retained (bytes are copied into pending), so it
	// is safe to reuse across responses.
	defer func() {
		*bufPtr = readBuffer
		streamingReadBufPool.Put(bufPtr)
	}()
	var pending []byte
	injected := false
	for {
		n, readErr := source.Read(readBuffer)
		if n > 0 {
			chunk := readBuffer[:n]
			if !injected {
				pending = append(pending, chunk...)
				index, waitForMore := streamingHTMLInjectionIndex(pending)
				if (waitForMore || index < 0) && len(pending) <= streamingHTMLInjectionLookahead {
					chunk = nil
				} else if index < 0 {
					if len(injection) > 0 {
						if _, err := writer.Write(injection); err != nil {
							return err
						}
					}
					injected = true
					chunk = pending
					pending = nil
				} else {
					if err := patcher.Write(pending[:index]); err != nil {
						return err
					}
					if err := patcher.Flush(); err != nil {
						return err
					}
					if len(injection) > 0 {
						if _, err := writer.Write(injection); err != nil {
							return err
						}
					}
					injected = true
					chunk = pending[index:]
					pending = nil
				}
			}
			if injected && len(chunk) > 0 {
				if err := patcher.Write(chunk); err != nil {
					return err
				}
			}
		}
		if readErr != nil {
			if readErr != io.EOF {
				return readErr
			}
			if !injected {
				if len(injection) > 0 {
					if _, err := writer.Write(injection); err != nil {
						return err
					}
				}
				if err := patcher.Write(pending); err != nil {
					return err
				}
			}
			return patcher.Close()
		}
	}
}

// streamingHTMLInjectionIndex locates the first injection marker in content.
// Matching is ASCII case-insensitive so that "<HEAD" / "<Head" are recognised
// without lowercasing (and thus allocating) the whole buffer.
func streamingHTMLInjectionIndex(content []byte) (int, bool) {
	for _, marker := range streamingHTMLInsertionMarkers {
		index := indexASCIIFold(content, marker.marker)
		if index < 0 {
			if streamingHTMLMayContainPartialMarker(content, marker.marker) {
				return -1, true
			}
			continue
		}
		if !marker.insertAfterOpen {
			return index, false
		}
		closeIndex := bytes.IndexByte(content[index:], streamingGtByte)
		if closeIndex < 0 {
			return -1, true
		}
		return index + closeIndex + 1, false
	}
	return -1, false
}

// streamingHTMLMayContainPartialMarker reports whether content ends with a
// (case-insensitive) prefix of marker, in which case more bytes must be read
// before the marker can be ruled out.
func streamingHTMLMayContainPartialMarker(content []byte, marker []byte) bool {
	keep := min(len(content), len(marker)-1)
	tail := content[len(content)-keep:]
	for length := 1; length < len(marker) && length <= len(tail); length++ {
		if foldEqual(tail[len(tail)-length:], marker[:length]) {
			return true
		}
	}
	return false
}

type streamingMetaCSPPatcher struct {
	writer       io.Writer
	styleSource  string
	scriptSource string
	pending      []byte
}

func (p *streamingMetaCSPPatcher) Write(content []byte) error {
	if len(content) == 0 {
		return nil
	}
	if len(p.pending) > 0 {
		p.pending = append(p.pending, content...)
		return p.flushPending(false)
	}
	return p.write(content, false)
}

func (p *streamingMetaCSPPatcher) Close() error {
	if len(p.pending) == 0 {
		return nil
	}
	return p.flushPending(true)
}

func (p *streamingMetaCSPPatcher) Flush() error {
	if len(p.pending) == 0 {
		return nil
	}
	return p.flushPending(true)
}

// flush streams pending bytes to the underlying writer, rewriting any
// <meta http-equiv="content-security-policy" ...> tag in place. It performs
// ASCII case-insensitive matching directly over pending, so unlike a
// lowercased-copy approach it allocates nothing on the common path (no CSP meta
// tag) and only allocates around the CSP content value when it actually needs
// patching.
func (p *streamingMetaCSPPatcher) flushPending(final bool) error {
	content := p.pending
	p.pending = nil
	if err := p.write(content, final); err != nil {
		return err
	}
	if len(p.pending) == 0 && cap(content) <= streamingHTMLMetaTagLimit {
		p.pending = content[:0]
	}
	return nil
}

func (p *streamingMetaCSPPatcher) write(content []byte, final bool) error {
	for len(content) > 0 {
		index := indexASCIIFold(content, streamingMetaToken)
		if index < 0 {
			flushLength := len(content)
			if !final {
				flushLength -= min(flushLength, len(streamingMetaToken)-1)
			}
			if flushLength == 0 {
				p.pending = append(p.pending[:0], content...)
				return nil
			}
			if _, err := p.writer.Write(content[:flushLength]); err != nil {
				return err
			}
			if flushLength < len(content) {
				p.pending = append(p.pending[:0], content[flushLength:]...)
			}
			return nil
		}
		if index > 0 {
			if _, err := p.writer.Write(content[:index]); err != nil {
				return err
			}
			content = content[index:]
			continue
		}
		// content starts with a (case-insensitive) "<meta".
		end := bytes.IndexByte(content, streamingGtByte)
		if end < 0 {
			if final || len(content) > streamingHTMLMetaTagLimit {
				if _, err := p.writer.Write(content); err != nil {
					return err
				}
			} else {
				p.pending = append(p.pending[:0], content...)
			}
			return nil
		}
		end++
		tagBytes := content[:end]
		if containsASCIIFold(tagBytes, streamingHTTPEquivToken) && containsASCIIFold(tagBytes, streamingCSPToken) {
			if err := writePatchedMetaCSPTag(p.writer, tagBytes, p.styleSource, p.scriptSource); err != nil {
				return err
			}
		} else {
			if _, err := p.writer.Write(tagBytes); err != nil {
				return err
			}
		}
		content = content[end:]
	}
	return nil
}

// foldASCII returns the ASCII lower-case form of b. Bytes outside 'A'-'Z' are
// returned unchanged. This is sufficient for matching HTML tag/attribute names
// and the fixed ASCII tokens ("<meta", "http-equiv", ...) used by the streamer;
// non-ASCII bytes in the haystack can never equal an ASCII needle byte.
func foldASCII(b byte) byte {
	if b >= 'A' && b <= 'Z' {
		return b + ('a' - 'A')
	}
	return b
}

// foldEqual reports whether a and b are equal under ASCII case-folding.
func foldEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if foldASCII(a[i]) != foldASCII(b[i]) {
			return false
		}
	}
	return true
}

// indexASCIIFold returns the index of the first ASCII case-insensitive
// occurrence of needle in haystack, or -1. It allocates nothing, which keeps
// the HTML streaming hot path free of the per-chunk lowercased copies that
// previously dominated its allocation profile. Needles here are short fixed
// tokens ("<meta", "http-equiv", ...), so the on-the-fly folding is cheap and
// cache-friendly.
func indexASCIIFold(haystack, needle []byte) int {
	n := len(needle)
	if n == 0 {
		return 0
	}
	if n > len(haystack) {
		return -1
	}
	first := foldASCII(needle[0])
	end := len(haystack) - n
	if !isASCIIAlpha(needle[0]) {
		for offset := 0; offset <= end; {
			relIndex := bytes.IndexByte(haystack[offset:end+1], needle[0])
			if relIndex < 0 {
				return -1
			}
			i := offset + relIndex
			j := 1
			for j < n && foldASCII(haystack[i+j]) == foldASCII(needle[j]) {
				j++
			}
			if j == n {
				return i
			}
			offset = i + 1
		}
		return -1
	}
	for i := 0; i <= end; i++ {
		if foldASCII(haystack[i]) != first {
			continue
		}
		j := 1
		for j < n && foldASCII(haystack[i+j]) == foldASCII(needle[j]) {
			j++
		}
		if j == n {
			return i
		}
	}
	return -1
}

func isASCIIAlpha(b byte) bool {
	return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
}

// containsASCIIFold reports whether haystack contains needle under ASCII
// case-folding, without allocating.
func containsASCIIFold(haystack, needle []byte) bool {
	return indexASCIIFold(haystack, needle) >= 0
}
