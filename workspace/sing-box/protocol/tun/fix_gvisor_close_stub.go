//go:build !with_gvisor

package tun

import singtun "github.com/sagernet/sing-tun"

func forceCloseGVisorStack(stack singtun.Stack) {}
