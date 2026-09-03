package wireguard

import "testing"

func TestDetouredEndpointSkipsPhysicalInterfaceUpdate(t *testing.T) {
	endpoint := &Endpoint{detoured: true}
	endpoint.started.Store(true)

	// A nil transport would panic if InterfaceUpdated attempted BindUpdate.
	endpoint.InterfaceUpdated(t.Context())
}
