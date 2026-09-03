//go:build with_gvisor

package main

import (
	"fmt"
	"reflect"
	"unsafe"

	boxtun "github.com/sagernet/sing-box/protocol/tun"
	singtun "github.com/sagernet/sing-tun"
)

func main() {
	realType := reflect.TypeOf(singtun.GVisor{})
	mirrorType := reflect.TypeOf(boxtun.GVisorUnsafe{})

	if realType.NumField() != mirrorType.NumField() {
		fail("field count mismatch: real=%d mirror=%d", realType.NumField(), mirrorType.NumField())
	}

	if realType.Size() != mirrorType.Size() {
		fail("size mismatch: real=%d mirror=%d", realType.Size(), mirrorType.Size())
	}

	if realType.Align() != mirrorType.Align() {
		fail("align mismatch: real=%d mirror=%d", realType.Align(), mirrorType.Align())
	}

	for i := 0; i < realType.NumField(); i++ {
		rf := realType.Field(i)
		mf := mirrorType.Field(i)

		if rf.Name != mf.Name {
			fail("field %d name mismatch: real=%s mirror=%s", i, rf.Name, mf.Name)
		}

		if rf.Type != mf.Type {
			fail("field %d type mismatch: real=%s mirror=%s", i, rf.Type, mf.Type)
		}

		if rf.Offset != mf.Offset {
			fail("field %d offset mismatch: real=%s offset=%d mirror=%s offset=%d",
				i, rf.Name, rf.Offset, mf.Name, mf.Offset)
		}
	}

	// Optional redundant unsafe check.
	if unsafe.Sizeof(singtun.GVisor{}) != unsafe.Sizeof(boxtun.GVisorUnsafe{}) {
		fail("unsafe.Sizeof mismatch")
	}
}

func fail(format string, args ...any) {
	panic(fmt.Sprintf("sing-tun.GVisor layout incompatible: "+format+"\n", args...))
}
