//go:build !go1.24

package tls

import "testing"

func skipECDSATest(t *testing.T) {
	// because: https://github.com/golang/go/commit/9776d028f4b99b9a935dae9f63f32871b77c49af
	t.Skip("Skip ecdsa test for go<1.24")
}
