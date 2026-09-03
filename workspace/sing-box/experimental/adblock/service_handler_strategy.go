//go:build with_adblock

package adblock

type adblockRequestHandler interface {
	Handle(*adblockRequestContext) (bool, error)
}

func (s *Service) handleAdblockRequest(requestContext *adblockRequestContext) error {
	handlers := []adblockRequestHandler{
		exceptionHandler{service: s},
		redirectHandler{service: s},
		rewriteURLHandler{service: s},
		blockHandler{service: s},
		forwardHandler{service: s},
	}
	for _, handler := range handlers {
		handled, err := handler.Handle(requestContext)
		if handled || err != nil {
			return err
		}
	}
	return nil
}
