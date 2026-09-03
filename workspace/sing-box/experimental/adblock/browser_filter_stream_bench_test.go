//go:build with_adblock

package adblock

import (
	"bytes"
	"io"
	"strings"
	"testing"
)

// TestStreamHTMLFilterAllocationIsBounded asserts the streaming HTML/CSP
// patcher does not allocate proportionally to the document size on the common
// path (no CSP meta tag). Before the ASCII case-fold rewrite it lowercased the
// whole pending buffer on every chunk, causing O(n) bytes allocated per chunk
// and ~O(n^2) total for large documents. The budget below leaves generous room
// for the pipe/bookkeeping but would fail loudly if a per-chunk full-copy
// regression returned.
func TestStreamHTMLFilterAllocationIsBounded(t *testing.T) {
	// ~512 KiB document with many ordinary <meta ...> tags (none CSP) plus
	// some class/id attributes, so the marker scanner and patcher both run.
	var doc strings.Builder
	doc.WriteString("<html><head>")
	doc.WriteString(`<meta name="a" content="b">`)
	for i := 0; i < 4000; i++ {
		doc.WriteString(`<div class="x" id="y"><meta name="n" content="c"></div>`)
	}
	doc.WriteString("</head><body>")
	for i := 0; i < 4000; i++ {
		doc.WriteString("<p>hello world</p>")
	}
	doc.WriteString("</body></html>")
	content := []byte(doc.String())
	injection := []byte("<style>.ad{display:none}</style>")

	body := newStreamingHTMLFilterReadCloser(
		io.NopCloser(bytes.NewReader(content)),
		injection,
		"",
		"",
	)
	n, err := io.Copy(io.Discard, body)
	if err != nil {
		t.Fatal(err)
	}
	if n < int64(len(content)) {
		t.Fatalf("output shorter than input: %d < %d", n, len(content))
	}

	// Measure the filter itself synchronously. Measuring the io.Pipe wrapper
	// here makes the process-wide allocation counter sensitive to unrelated
	// background goroutines started by other tagged packages in the full suite.
	// A lowercased-copy implementation still allocates once per chunk on this
	// path, while the ASCII case-fold matcher and pooled read buffer remain
	// bounded independently of document size.
	allocs := testing.AllocsPerRun(20, func() {
		err := streamHTMLFilter(
			io.Discard,
			bytes.NewReader(content),
			injection,
			"",
			"",
		)
		if err != nil {
			t.Fatal(err)
		}
	})
	const maxAllocs = 32.0
	if allocs > maxAllocs {
		t.Fatalf("streaming HTML filter allocated %.0f objects for %d bytes; budget %.0f",
			allocs, len(content), maxAllocs)
	}
}

func BenchmarkStreamHTMLFilter(b *testing.B) {
	// Representative mixed document: a head with one CSP meta tag to patch,
	// many ordinary meta tags, and a sizable body.
	var doc strings.Builder
	doc.WriteString("<html><head>")
	doc.WriteString(`<meta http-equiv="Content-Security-Policy" content="default-src 'self'">`)
	for i := 0; i < 200; i++ {
		doc.WriteString(`<meta name="n" content="c">`)
	}
	doc.WriteString("</head><body>")
	for i := 0; i < 4000; i++ {
		doc.WriteString("<p>hello world 1234567890</p>")
	}
	doc.WriteString("</body></html>")
	src := []byte(doc.String())
	injection := []byte("<style>.ad{display:none}</style>")
	styleSource := "'nonce-style'"

	b.ReportAllocs()
	b.SetBytes(int64(len(src)))
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		body := newStreamingHTMLFilterReadCloser(
			io.NopCloser(bytes.NewReader(src)),
			injection,
			styleSource,
			"",
		)
		if _, err := io.Copy(io.Discard, body); err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkPatchMetaCSP(b *testing.B) {
	var doc strings.Builder
	doc.WriteString("<html><head>")
	doc.WriteString(`<meta http-equiv="Content-Security-Policy" content="default-src 'self'">`)
	for i := 0; i < 200; i++ {
		doc.WriteString(`<meta name="n" content="c">`)
	}
	doc.WriteString("</head><body>")
	for i := 0; i < 4000; i++ {
		doc.WriteString("<p>hello world 1234567890</p>")
	}
	doc.WriteString("</body></html>")
	src := []byte(doc.String())

	b.ReportAllocs()
	b.SetBytes(int64(len(src)))
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = patchMetaCSP(src, "'nonce-style'", "'nonce-script'")
	}
}
