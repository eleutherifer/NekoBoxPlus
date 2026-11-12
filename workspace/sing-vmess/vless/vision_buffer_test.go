package vless

import (
	"bytes"
	"reflect"
	"testing"
	"unsafe"
)

func TestVisionRawInputLayouts(t *testing.T) {
	old := &struct{ rawInput bytes.Buffer }{}
	old.rawInput.WriteString("old")
	pointer := &struct{ rawInput *bytes.Buffer }{}
	for _, test := range []struct {
		name     string
		value    any
		expected string
	}{
		{"embedded", old, "old"}, {"nil pointer", pointer, ""},
	} {
		t.Run(test.name, func(t *testing.T) {
			v := reflect.ValueOf(test.value)
			raw := visionRawInput(v.Type().Elem(), v.UnsafePointer())
			data, err := readVisionRawInput(raw)
			if err != nil || string(data) != test.expected {
				t.Fatalf("got %q, %v", data, err)
			}
		})
	}
	raw := visionRawInput(reflect.TypeOf(pointer).Elem(), unsafe.Pointer(pointer))
	pointer.rawInput = bytes.NewBufferString("allocated later")
	data, err := readVisionRawInput(raw)
	if err != nil || string(data) != "allocated later" {
		t.Fatalf("lost lazy allocation: %q, %v", data, err)
	}
}
