package libcore

import "libcore/device"

func runWithPanicError[T any](name string, run func() (T, error)) (result T, err error) {
	defer device.DeferPanicToError(name, func(panicErr error) {
		var zero T
		result = zero
		err = panicErr
	})
	return run()
}
