//go:build !windows && !darwin

package syscallw

import "syscall"

func Flock(fd int, how int) (err error) {
	return syscall.Flock(fd, how)
}

func Dup3(oldfd int, newfd int, flags int) (err error) {
	return syscall.Dup3(oldfd, newfd, flags)
}
