# Adblock support audit

Scope: rule parsing and filtering capabilities in `common/adblock/` and
`experimental/adblock/`. This document focuses on **where support is lacking**,
with enough context on what already works to make the gaps actionable.

## Bundled filtering resources

Adblock builds require **both** resource repositories:

- `brave/adblock-resources` supplies Brave redirect and compatibility resources.
- `gorhill/uBlock` supplies uBlock Origin web-accessible resources and the
  scriptlet library used by `##+js(...)` rules in uBO-compatible filter lists.

Neither repository is a replacement for the other. In particular, bundling
only Brave resources leaves common uBO scriptlets such as `no-xhr-if` and
`nano-setTimeout-booster` unresolved even though their filter rules parse.
Commit `f45805b90` removed the separate uBlock checkout while migrating to
Brave resources; that exposed this distinction and is why the dual bundle is
required now.
`make -f Makefile.plus adblock-resources-generate` checks out the pinned
revisions of both repositories, combines their resources under the embedded
asset tree, and generates the current uBO scriptlet manifest. Only the Brave
manifest, generated scriptlets, uBlock's redirect map, and the web-accessible
resources declared by that map are embedded; the rest of the uBlock source and
UI are intentionally excluded.

Scriptlets receive a process-randomized, same-origin web-accessible-resource
endpoint and secret. The proxy serves declared resources at that endpoint,
matching uBlock's `warOrigin`/`warSecret` contract without exposing a stable
path or forwarding those requests upstream. Update the
`ADBLOCK_RESOURCES_COMMIT` and `UBLOCK_COMMIT` pins together with the relevant
compatibility tests when refreshing either dependency.

Findings are grounded in the code as of this audit:

- `common/adblock/bridge/src/lib.rs` — the Rust↔Go bridge over `adblock-rust`.
- `common/adblock/adblockrust/engine*.go` — the Go `Engine` interface exposed to the rest of the codebase.
- `experimental/adblock/` — the proxy service that applies the engine to HTTP / HTTPS / QUIC / DNS traffic and performs cosmetic (browser) filtering.

Upstream reference for what the engine can return: `adblock-rust/src/blocker.rs`
(`struct BlockerResult`).

---

## 1. Architecture in one paragraph

The Rust bridge wraps `adblock-rust` and exposes six queryable operations:
network match (`Check`), detailed match (`CheckDetailed` → matched / important /
redirect / rewritten_url / exception / filter), exception-only (`CheckException`),
CSP directives (`CSPDirectives`), cosmetic resources (`URLCosmeticResources`),
and generic class/id cosmetic selectors (`HiddenClassIDSelectors`). The
experimental `Service` sits in the data path: for every HTTP(S)/QUIC request it
classifies a request type, runs `CheckDetailed`, then dispatches to handlers
(exception → redirect → rewrite-url → block → forward) and optionally rewrites
HTML responses with cosmetic filters. DNS responses are matched by synthesizing
`http(s)://<domain>/` URLs.

### Regexp backends

The regexp package selects its engine by architecture, independently of the
`with_adblock` tag. It uses the default wasm2go RE2 backend on supported 64-bit
architectures and the standard Go `regexp` engine on 32-bit and unknown targets
to avoid exhausting their address space when RE2 initializes its WASM memory.
Builds using `re2_cgo` or `re2_wazero` also use the standard engine.

---

## 2. What works (so the gaps below are unambiguous)

| Area | Support |
| --- | --- |
| **Network** | Plain/exception blocking, `$important`, type modifiers (`$script`, `$image`, `$xhr`, `$ping`, `$media`, `$font`, `$3p`/`$1p`, `$domain`, `$match-case`, `$all`), `$popup`, `$badfilter`, `$redirect`, `$redirect-rule`, `$rewrite`, `$removeparam` (all via `redirect`/`rewritten_url`), `$csp`. Request type inferred from `Sec-Fetch-Dest`, `Accept`, file extension, and method (`httputil.go`). |
| **Content (cosmetic)** | `##` hide, `#@#` exceptions, `##?#` procedural filters (has/has-text/matches-css/matches-attr/matches-prop/xpath/upward/others/shadow/spath/etc., see the runner in `browser_filter.go`), `##+js` scriptlets, `$generichide`/`$specifichide`, generic class/id selectors via DOM scan, injected `<style>`/`<script>` with CSP nonce/hash patching on both HTTP headers and `<meta>` tags. |
| **DNS** | Domain blocking (returns `0.0.0.0`/`::`), CNAME uncloaking with a CDN/infrastructure allowlist and registrable-domain heuristics (`dns.go`). |
| **Protocols** | HTTP/1.1, HTTP/2, HTTP/3 (QUIC, behind `with_quic`), WebSocket upgrade hijack forwarding, plain and TLS interception (with optional uTLS / Cronet). |
| **Uploads** | Bodies streamed through untouched; Cronet path has a full `UploadDataProvider` with `GetBody` rewind (`httpconn/cronet.go`). |

---

## 3. Gaps in the engine bridge (`common/adblock/bridge/src/lib.rs`)

These are features `adblock-rust` can do but the bridge does **not** surface, so
the service cannot use them regardless of the rules in a list.

1. **`$replace` — response body regex replacement.**
   `BlockerResult` in `adblock-rust/src/blocker.rs` has **no `replace` field**
   (only `matched`, `important`, `redirect`, `rewritten_url`, `exception`,
   `filter`), and the bridge mirrors exactly those. The service has no code path
   that rewrites a response body from a `$replace` rule. `$replace` rules are
   therefore silently parsed and then discarded. A test rule in
   `service_test.go:706` uses `replace=`, but it only exercises list
   *preprocessing*, not replacement application. **This is the single biggest
   content-filtering gap** for lists that rely on it (notably YouTube ad-skip
   rules such as `trusted-replace-fetch-response` / `replace=`).

2. **Tags are not enabled at runtime.**
   `adblock-rust` exposes `Engine::enable_tags` / `tag_exists`
   (`adblock-rust/src/engine.rs:181,197`). The bridge never calls them; it only
   forwards a static `permissions` mask at build time (`rule_set.permissions`).
   Consequence: any `$tag`, `^$tag=`, or content-type-tagged rule set is inert,
   and there is no way to attach request context (client, inbound, app) to the
   engine dynamically.

3. **`$permissions` (Permissions-Policy), `$header`, `$cookie`.**
   None of these modifiers appear in `BlockerResult` and none are surfaced.
   `$csp` is supported via a dedicated `get_csp_directives` path, but the
   analogous Permissions-Policy injection, request/response header matching, and
   cookie filtering are unavailable.

4. **Stealth / anti-fingerprinting.**
   Not present in this integration (adblock-rust does not ship Brave's stealth
   module here). Any list lines that depend on stealth scriptlets cannot work.

5. **AdGuard snippets (`#$#`).**
   Only uBlock Origin scriptlets (`##+js`) are assembled and injected (via
   `injected_script`). AdGuard-style snippet filters are not parsed/injected.

---

## 4. Content (cosmetic / HTML) filtering gaps

Beyond the missing `$replace` above:

1. **Streaming ("realtime") injection only runs for `$generichide` documents.**
   In `browser_filter.go`, `streamBrowserFilters` early-returns unless
   `state.cosmetic.GenericHide` is true. For the common case (page is *not*
   `$generichide`), the service falls back to `injectBrowserFilters`, which
   **buffers the entire response** (`io.ReadAll`) before injecting. So genuine
   zero-buffer streaming is limited to generichide pages; everything else is
   buffered, increasing TTFB and memory on large documents.

2. **Limited native HTML filtering.**
   `cap_html_filtering` is enabled for the default Firefox-compatible
   preprocessor environment, and uBO `##^` HTML filters are applied to decoded
   HTML responses. The native server-side selector support is intentionally
   narrower than uBO's DOM engine: plain CSS-style tag/class/id/attribute
   selectors and `:has-text(...)` are supported, while more complex procedural
   HTML operators are ignored.

3. **CSP patching can only *add* nonces/hashes; it never weakens existing policy
   enough for scriptlets when a strict policy lacks a fallback.** This is mostly
   handled, but `$csp`-driven weakening and `$permissions` injection are absent
   (see §3).

4. **Cosmetic runner limitations** (`singBoxAdblockRunner`): no `:xpath` + `has`
   combinator validation against the page beyond what the inlined JS does, and
   procedural `watch-attr`/`watch-attr` style actions are parsed but become
   no-ops (the runner stubs `watch-attr`). Cosmetic rules depending on
   attribute-change observers will not stay applied.

---

## 5. DNS filtering gaps (`experimental/adblock/dns.go`)

1. **Type- and context-blind.** `dnsCheck` synthesizes
   `http://<domain>/` and `https://<domain>/` with request type **`other`** and
   an **empty source URL**. Therefore:
   - rules with a type modifier (`$image`, `$script`, …) never match at DNS;
   - `$domain=` / third-party context rules never fire (no source domain);
   - only plain `||host^`-style block rules and their `@@` exceptions are
     effective at the DNS layer.

2. **Only one block shape.** `blockDNSResponse` always returns `0.0.0.0` / `::`
   with a fixed 60s TTL. There is **no** NXDOMAIN option, no custom-IP /
   `$dnsrewrite`, no per-client or `$client=` exception, and the TTL is not
   configurable.

3. **CNAME uncloaking heuristics are static.** The infra allowlist
   (`knownInfrastructureSuffixes`) and the "skip same registrable domain" rule
   (`shouldSkipCNAMECandidate`) are hardcoded. Real CNAME-cloaked trackers on
   non-allowlisted CDNs are caught, but there is no way for users to extend or
   override the allowlist, and apex/2-label domains are always skipped
   (`isSecondLevelDomain`).

---

## 6. Browser-specific behavior gaps

### Realtime / streaming

- See §4.1: true streaming injection is gated on `$generichide`. For all other
  documents the response is fully buffered.
- Non-HTML streams (e.g. `text/event-stream`, chunked JSON APIs) are never
  filtered — only rendered HTML documents are eligible
  (`shouldRewriteHTML` in `service_handlers_util.go`).
- Cloudflare challenge paths are intentionally bypassed
  (`shouldSkipResponseFiltering`); other anti-bot/challenge platforms are not.

### Uploads (incl. multipart forms)

- **Passthrough only.** Both the standard `http.Transport` forwarder and the
  Cronet `UploadDataProvider` stream the request body end-to-end without
  inspection. Multipart, chunked, and rewindable uploads work correctly
  *mechanically*.
- **But nothing about a request body is ever matched.** There is no
  request-body inspection, so:
  - form-field / multipart-payload blocking is impossible;
  - `$header` on request headers is not applied (not surfaced, see §3);
  - the only inputs to the decision are URL, `Sec-Fetch-Dest`/`Accept`/method,
    `Referer`/`Origin`, and the synthesized source URL.

### Other browser flows

- **WebSocket**: classified and forwarded via upgrade hijack, but it is treated
  as a connection-level passthrough after the initial request check — no
  per-message inspection (expected, but worth stating).
- **HTTP/3 (QUIC)**: fully supported only when built with `with_quic`; otherwise
  `handleQUICHTTP`/`forwardHTTP3RequestURL` return `ErrQUICNotIncluded`
  (`service_handlers_h3_stub.go`). Response cosmetic filtering *does* run on the
  H3 path, sharing the same pipeline (and the same streaming limitation as §4.1).
- **uTLS / Cronet**: mutually exclusive and build-gated
  (`with_utls` / `with_adblock_cronet`). When Cronet is enabled, uTLS is forced
  off (`consts.Invalid`).

---

## 7. Filter-list preprocessor gaps (`experimental/adblock/filter_parser.go`)

The `!#if`/`!#else`/`!#endif` preprocessor defaults to a Firefox/uBO-like
environment:

| Flag | Resolved as |
| --- | --- |
| `env_firefox` | `true` |
| `ext_ublock` / `ext_ubo` | `true` |
| `ext_devbuild` | `true` |
| `cap_html_filtering` | `true` |
| `cap_ipaddress` | `true` |
| `cap_user_stylesheet` | `true` |
| `adguard_ext_firefox` | `true` |
| Chromium/Edge/Safari/Opera/MV3/mobile/ABP/uBOL/AdGuard app tokens | `false` |
| unknown `cap_*` / other unknown tokens | `false` |

`filterPreprocessorCapability` returns `false` for any unrecognized token. Real
The environment can be overridden through `adblock.environment` for
non-Firefox-compatible behavior.

Other list-handling notes:

- `! Title`/`! Description`/`! Last Modified`/`! Expires`/`! License`/
  `! Homepage`/`! Forums` are parsed, but `! Homepage` variants and
  `! Variant-Of` / `! Policy` / `! CDN` are ignored.
- Only `standard` and `hosts` list formats are accepted
  (`option/adblock.go`, `adblockRuleFormat`). AdGuard/RPZ/URL-redirect formats
  are rejected.
- Redirect resources (`$redirect=<name>`) must resolve to `data:` URIs;
  `decodeRedirectResource` in `service_handlers_util.go` rejects anything else,
  so absolute-URL `$redirect` is not honored.

---

## 8. Suggested priorities

Ranked by impact-to-effort for "make real-world lists actually work":

1. **Surface `$replace` end-to-end.** Requires a bridge field for the matched
   replace rule + a response-body rewriting step in the forward path (with
   content-encoding handling already partly present via `prepareRewritableBody`).
   High impact for ad-skip / sponsor-segment lists.
2. **Fix the streaming-injection gate** so non-`$generichide` documents also
   stream (inject into the first buffered chunk, then stream the rest). Removes
   the large-document buffering regression.
3. **Broaden native HTML filtering selectors** beyond the current server-side
   subset so more uBO procedural HTML filters can run without scriptlet
   fallbacks.
4. **Enable engine tags at runtime** (bridge `enable_tags` + per-request tag set
   from inbound/process context) so tagged lists and per-app filtering work.
5. **DNS options**: add block mode (NXDOMAIN vs zero-IP) and a configurable
   block TTL, plus a user-overridable CNAME allowlist.
6. **`$permissions` / `$header` surfacing** — lower frequency in mainstream
   lists but cheap to add given the existing CSP plumbing.

---

## 9. Key file/line references

| Concern | Location |
| --- | --- |
| Engine interface (Go) | `common/adblock/adblockrust/engine.go` |
| Bridge result fields (what is surfaced) | `common/adblock/bridge/src/lib.rs` (`DetailedCheckResult`, `sing_box_adblock_engine_check_detailed`) |
| Upstream result shape (ground truth) | `adblock-rust/src/blocker.rs` (`struct BlockerResult`) |
| Request classification | `experimental/adblock/httputil.go` (`classifyAdblockRequest`) |
| Handler dispatch | `experimental/adblock/service_handler_strategy.go` |
| Network check + inferred-type retry | `experimental/adblock/service_request_check.go` |
| Cosmetic injection (buffered + streaming) | `experimental/adblock/browser_filter.go`, `browser_filter_stream.go` |
| HTML rewrite decision | `experimental/adblock/service_handlers_util.go` (`shouldRewriteHTML`, `rewriteHTMLResponse`) |
| DNS filtering | `experimental/adblock/dns.go` |
| Filter-list preprocessor | `experimental/adblock/filter_parser.go` (`filterPreprocessorCapability`) |
| Upload/Cronet body handling | `experimental/adblock/httpconn/cronet.go` (`cronetBodyUploadProvider`) |
| HTTP/3 path / stub | `experimental/adblock/service_handlers_h3.go`, `service_handlers_h3_stub.go` |
| Options schema | `option/adblock.go` |
