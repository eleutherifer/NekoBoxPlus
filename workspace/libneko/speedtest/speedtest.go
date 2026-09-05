package speedtest

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptrace"
	"time"
)

const UrlTestStandard_RTT = 0
const UrlTestStandard_Handshake = 1
const UrlTestStandard_FirstHandshake = 2

// UrlTestStandard_FisrtHandshake is kept for source compatibility.
const UrlTestStandard_FisrtHandshake = UrlTestStandard_FirstHandshake

func URLTest(ctx context.Context, client *http.Client, link string) (int32, error) {
	probeClient, err := prepareURLTestClient(client, false)
	if err != nil {
		return 0, err
	}
	defer probeClient.CloseIdleConnections()

	start := time.Now()
	wroteHeaders, firstByte, err := performURLTestRequest(ctx, probeClient, link)
	if err != nil {
		return 0, err
	}
	return durationMilliseconds(responseRTT(time.Since(start), wroteHeaders, firstByte)), nil
}

func UrlTest(ctx context.Context, client *http.Client, link string, standard int) (int32, error) {
	disableKeepAlives := standard == UrlTestStandard_Handshake
	probeClient, err := prepareURLTestClient(client, disableKeepAlives)
	if err != nil {
		return 0, err
	}
	defer probeClient.CloseIdleConnections()

	attempts := 2
	switch standard {
	case UrlTestStandard_FirstHandshake:
		attempts = 1
	case UrlTestStandard_Handshake:
	case UrlTestStandard_RTT:
	default:
		return 0, errors.New("unknown urltest standard")
	}

	var elapsed time.Duration
	for range attempts {
		start := time.Now()
		wroteHeaders, firstByte, requestErr := performURLTestRequest(ctx, probeClient, link)
		if requestErr != nil {
			return 0, requestErr
		}
		elapsed = time.Since(start)
		if standard == UrlTestStandard_RTT {
			elapsed = responseRTT(elapsed, wroteHeaders, firstByte)
		}
	}

	return durationMilliseconds(elapsed), nil
}

func prepareURLTestClient(client *http.Client, disableKeepAlives bool) (*http.Client, error) {
	if client == nil {
		return nil, errors.New("no client")
	}
	probeClient := *client
	probeClient.CheckRedirect = func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}
	if transport, ok := client.Transport.(*http.Transport); ok {
		probeTransport := transport.Clone()
		probeTransport.DisableKeepAlives = disableKeepAlives
		probeClient.Transport = probeTransport
	} else if disableKeepAlives {
		return nil, fmt.Errorf("URLTest handshake mode requires *http.Transport, got %T", client.Transport)
	}
	return &probeClient, nil
}

func performURLTestRequest(ctx context.Context, client *http.Client, link string) (time.Time, time.Time, error) {
	var wroteHeaders time.Time
	var firstByte time.Time
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, link, nil)
	if err != nil {
		return wroteHeaders, firstByte, err
	}
	req = req.WithContext(httptrace.WithClientTrace(req.Context(), &httptrace.ClientTrace{
		WroteHeaders: func() {
			wroteHeaders = time.Now()
		},
		GotFirstResponseByte: func() {
			firstByte = time.Now()
		},
	}))
	resp, err := client.Do(req)
	if err != nil {
		return wroteHeaders, firstByte, err
	}
	if resp.Body != nil {
		err = resp.Body.Close()
	}
	return wroteHeaders, firstByte, err
}

func responseRTT(fallback time.Duration, wroteHeaders, firstByte time.Time) time.Duration {
	if wroteHeaders.IsZero() || firstByte.IsZero() || firstByte.Before(wroteHeaders) {
		return fallback
	}
	return firstByte.Sub(wroteHeaders)
}

func TCPPing(
	ctx context.Context,
	dialContext func(context.Context, string, string) (net.Conn, error),
	address string,
) (int32, error) {
	if dialContext == nil {
		return 0, errors.New("no dialer")
	}
	start := time.Now()
	conn, err := dialContext(ctx, "tcp", address)
	if err != nil {
		return 0, err
	}
	elapsed := time.Since(start)
	if closeErr := conn.Close(); closeErr != nil {
		return 0, closeErr
	}
	return durationMilliseconds(elapsed), nil
}

func durationMilliseconds(duration time.Duration) int32 {
	milliseconds := max(duration.Milliseconds(), 0)
	return int32(min(milliseconds, int64(^uint32(0)>>1)))
}
