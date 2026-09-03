//go:build with_adblock

package adblock

import (
	"bytes"
	"compress/flate"
	"compress/gzip"
	"io"
	"net/http"
	"strings"

	"github.com/andybalholm/brotli"
	"github.com/klauspost/compress/zstd"
)

// decompressReadCloser pairs a decompressing reader with the closer of the
// original response body, so that closing the wrapper returns the underlying
// transport connection to its pool. The decoders themselves (gzip/flate/brotli
// readers) hold no extra resources beyond the bytes they read from the source.
type decompressReadCloser struct {
	reader io.Reader
	closer io.Closer
	source io.Closer
}

func (d decompressReadCloser) Read(p []byte) (int, error) {
	return d.reader.Read(p)
}

func (d decompressReadCloser) Close() error {
	if d.closer != nil {
		_ = d.closer.Close()
	}
	return d.source.Close()
}

// prepareRewritableBody ensures response.Body is plain, decodable bytes that
// HTML rewriting can operate on.
//
// Cosmetic filtering must be applied to a document whenever the browser will
// render it as a document -- regardless of how it was requested (a top-level
// navigation, an iframe, or HTML fetched by a service worker and handed back via
// respondWith). The only reliable network-layer signal that a response *is* a
// document is its Content-Type; the request type is not. Non-document request
// types are forwarded with their original Accept-Encoding, so the upstream often
// delivers the document compressed (gzip/deflate/brotli). This method wraps such
// bodies in a transparent decompressor and drops the now-stale Content-Encoding
// and Content-Length headers so the rewritten bytes are emitted verbatim.
//
// It returns true when the body is rewritable (it was never compressed or it was
// just decompressed), and false when the response uses an encoding the proxy
// cannot decode (layered or unsupported), in which case the body is left
// untouched and rewriting must be skipped to avoid corrupting it.
func (s *Service) prepareRewritableBody(response *http.Response) bool {
	encoding := strings.ToLower(strings.TrimSpace(response.Header.Get("Content-Encoding")))
	if encoding == "" || encoding == "identity" {
		return true
	}
	if strings.Contains(encoding, ",") {
		s.debug("skipping browser filtering: layered content-encoding not supported: ", encoding)
		return false
	}
	var decoder io.Reader
	var decoderCloser io.Closer
	switch encoding {
	case "gzip", "x-gzip":
		gzReader, err := gzip.NewReader(response.Body)
		if err != nil {
			s.debug("skipping browser filtering: gzip decoder init failed: ", err)
			return false
		}
		decoder = gzReader
		decoderCloser = gzReader
	case "deflate":
		// Per RFC 7230 "deflate" means zlib-wrapped deflate (RFC 1950),
		// which flate.NewReader decodes. Raw deflate is rare and unsupported here.
		flateReader := flate.NewReader(response.Body)
		decoder = flateReader
		decoderCloser = flateReader
	case "br":
		decoder = brotli.NewReader(response.Body)
	case "zstd":
		zstdReader, err := zstd.NewReader(response.Body)
		if err != nil {
			s.debug("skipping browser filtering: zstd decoder init failed: ", err)
			return false
		}
		decoder = zstdReader.IOReadCloser()
		decoderCloser = decoder.(io.Closer)
	default:
		s.debug("skipping browser filtering: unsupported content-encoding: ", encoding)
		return false
	}
	response.Body = decompressReadCloser{reader: decoder, closer: decoderCloser, source: response.Body}
	response.ContentLength = -1
	response.Header.Del("Content-Encoding")
	response.Header.Del("Content-Length")
	return true
}

type boundedRewritableBody struct {
	content       []byte
	original      []byte
	header        http.Header
	contentLength int64
	remainder     io.ReadCloser
}

type replayReadCloser struct {
	reader io.Reader
	closer io.Closer
}

func (r replayReadCloser) Read(p []byte) (int, error) {
	return r.reader.Read(p)
}

func (r replayReadCloser) Close() error {
	if r.closer == nil {
		return nil
	}
	return r.closer.Close()
}

func (s *Service) readBoundedRewritableBody(requestMethod string, response *http.Response, limit int64, allowUnknownLength bool) (*boundedRewritableBody, bool, error) {
	if response.Body == nil || !httpResponseAllowsBody(requestMethod, response.StatusCode) {
		return nil, false, nil
	}
	if limit < 0 || response.ContentLength > limit || (response.ContentLength < 0 && !allowUnknownLength) {
		return nil, false, nil
	}
	body := &boundedRewritableBody{
		header:        response.Header.Clone(),
		contentLength: response.ContentLength,
	}
	var err error
	body.original, err = readBodyUpTo(response.Body, limit)
	if err != nil {
		return nil, false, err
	}
	if int64(len(body.original)) > limit {
		body.remainder = response.Body
		body.restore(response)
		return nil, false, nil
	}
	_ = response.Body.Close()
	response.Body = io.NopCloser(bytes.NewReader(body.original))
	response.ContentLength = int64(len(body.original))
	if !s.prepareRewritableBody(response) {
		body.restore(response)
		return nil, false, nil
	}
	body.content, err = readBodyUpTo(response.Body, limit)
	if err != nil {
		body.restore(response)
		return nil, false, err
	}
	if int64(len(body.content)) > limit {
		_ = response.Body.Close()
		body.restore(response)
		return nil, false, nil
	}
	_ = response.Body.Close()
	return body, true, nil
}

func readBodyUpTo(reader io.Reader, limit int64) ([]byte, error) {
	if limit < 0 {
		return nil, nil
	}
	return io.ReadAll(io.LimitReader(reader, limit+1))
}

func (b *boundedRewritableBody) restore(response *http.Response) {
	response.Header = b.header.Clone()
	reader := io.Reader(bytes.NewReader(b.original))
	if b.remainder != nil {
		reader = io.MultiReader(reader, b.remainder)
	}
	response.Body = replayReadCloser{reader: reader, closer: b.remainder}
	response.ContentLength = b.contentLength
}

func (b *boundedRewritableBody) replace(response *http.Response, content []byte) {
	response.Body = io.NopCloser(bytes.NewReader(content))
	response.ContentLength = int64(len(content))
	response.Header.Del("Content-Encoding")
	response.Header.Del("Content-Length")
}
