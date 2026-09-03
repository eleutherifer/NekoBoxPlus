package masterdnsvpnbridge

import "sync/atomic"

type Reporter func(found int32, total int32, ready bool)
type FailureReporter func(noWorkingDNS bool, message string)

var reporter atomic.Value
var failureReporter atomic.Value

func SetReporter(next Reporter) {
	reporter.Store(next)
}

func SetFailureReporter(next FailureReporter) {
	failureReporter.Store(next)
}

func Report(found int32, total int32, ready bool) {
	current, _ := reporter.Load().(Reporter)
	if current == nil {
		return
	}
	current(found, total, ready)
}

func ReportFailure(noWorkingDNS bool, message string) {
	current, _ := failureReporter.Load().(FailureReporter)
	if current == nil {
		return
	}
	current(noWorkingDNS, message)
}
