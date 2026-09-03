package cpu

import _ "unsafe" // for linkname

import "github.com/metacubex/utls/internal/cpu"

type CacheLinePad = cpu.CacheLinePad

var CacheLineSize = cpu.CacheLineSize

var X86 = cpu.X86
var ARM = cpu.ARM
var ARM64 = cpu.ARM64
var Loong64 = cpu.Loong64
var MIPS64X = cpu.MIPS64X
var PPC64 = cpu.PPC64
var S390X = cpu.S390X
var RISCV64 = cpu.RISCV64

// CPU feature variables are accessed by assembly code in various packages.
//go:linkname X86
//go:linkname ARM
//go:linkname ARM64
//go:linkname Loong64
//go:linkname MIPS64X
//go:linkname PPC64
//go:linkname S390X
//go:linkname RISCV64
