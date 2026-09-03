package cronetbidistream

import (
	"testing"
	"time"

	"github.com/sagernet/cronet-go"
)

func TestDestroyStreamAsyncSignalsCompletionForEmptyStream(t *testing.T) {
	t.Parallel()

	destroyed := make(chan struct{})
	destroyStreamAsync(cronet.BidirectionalStream{}, destroyed)

	select {
	case <-destroyed:
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for Cronet stream destruction signal")
	}
}
