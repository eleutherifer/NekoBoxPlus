package libcore

// This blank import pins google.golang.org/genproto to a post-split version
// (>= v0.0.0-20240123012728-ef4313101c80) so that `go mod tidy` keeps the
// explicit require in go.mod.
//
// Background: gvisor.dev/gvisor (indirect dep) requires the old pre-split
// google.golang.org/genproto monorepo (v0.0.0-20230110181048-76db0878b65f),
// which still contains the googleapis/rpc/* packages.  Those same packages
// also live in the google.golang.org/genproto/googleapis/rpc submodule that
// grpc v1.73+ depends on.  When gomobile runs `go mod tidy` on its synthetic
// build module, Go sees both modules providing e.g. `googleapis/rpc/status`
// and aborts with "ambiguous import".
//
// Pinning to any version released after the monorepo split (~Jan 2024) is
// enough: those versions no longer contain the googleapis/rpc sub-tree, so
// the ambiguity disappears.  The blank import here keeps the require from
// being pruned by `go mod tidy`.
import _ "google.golang.org/genproto/googleapis/type/date"
