//go:build with_adblock

package adblock

import (
	"net/http"
	"time"

	"github.com/goccy/go-json"
)

const (
	cosmeticSelectorMaxBodySize = 64 * 1024
	cosmeticSelectorMaxNames    = 1024
	cosmeticSelectorSessionTTL  = 30 * time.Minute
)

var cosmeticSelectorEndpoint = "/." + runBlockHash()

type cosmeticSession struct {
	exceptions []string
	expires    time.Time
}

type cosmeticSelectorRequest struct {
	Token   string   `json:"token"`
	Classes []string `json:"classes"`
	IDs     []string `json:"ids"`
}

type cosmeticSelectorResponse struct {
	CSS string `json:"css"`
}

func (s *Service) newCosmeticSession(exceptions []string) (string, error) {
	token, err := randomCSPNonce()
	if err != nil {
		return "", err
	}
	s.cosmeticSessionsAccess.Lock()
	defer s.cosmeticSessionsAccess.Unlock()
	if s.cosmeticSessions == nil {
		s.cosmeticSessions = make(map[string]cosmeticSession)
	}
	now := time.Now()
	s.cleanupCosmeticSessionsLocked(now)
	s.cosmeticSessions[token] = cosmeticSession{
		exceptions: compactStrings(exceptions),
		expires:    now.Add(cosmeticSelectorSessionTTL),
	}
	return token, nil
}

func (s *Service) cosmeticSession(token string) (cosmeticSession, bool) {
	now := time.Now()
	s.cosmeticSessionsAccess.Lock()
	defer s.cosmeticSessionsAccess.Unlock()
	session, loaded := s.cosmeticSessions[token]
	if !loaded {
		return cosmeticSession{}, false
	}
	if now.After(session.expires) {
		delete(s.cosmeticSessions, token)
		return cosmeticSession{}, false
	}
	session.expires = now.Add(cosmeticSelectorSessionTTL)
	s.cosmeticSessions[token] = session
	return session, true
}

func (s *Service) cleanupCosmeticSessionsLocked(now time.Time) {
	for token, session := range s.cosmeticSessions {
		if now.After(session.expires) {
			delete(s.cosmeticSessions, token)
		}
	}
}

func (s *Service) handleCosmeticSelectorRequest(writer http.ResponseWriter, request *http.Request) bool {
	if request.URL == nil || request.URL.EscapedPath() != cosmeticSelectorEndpoint {
		return false
	}
	if request.Method != http.MethodPost {
		writer.Header().Set("Allow", http.MethodPost)
		http.Error(writer, "method not allowed", http.StatusMethodNotAllowed)
		return true
	}
	if request.Body == nil {
		http.Error(writer, "missing request body", http.StatusBadRequest)
		return true
	}
	defer request.Body.Close()

	var payload cosmeticSelectorRequest
	decoder := json.NewDecoder(http.MaxBytesReader(writer, request.Body, cosmeticSelectorMaxBodySize))
	if err := decoder.Decode(&payload); err != nil {
		http.Error(writer, "invalid request body", http.StatusBadRequest)
		return true
	}
	if payload.Token == "" || len(payload.Classes)+len(payload.IDs) == 0 || len(payload.Classes)+len(payload.IDs) > cosmeticSelectorMaxNames {
		http.Error(writer, "invalid selector request", http.StatusBadRequest)
		return true
	}
	session, loaded := s.cosmeticSession(payload.Token)
	if !loaded {
		http.Error(writer, "invalid cosmetic session", http.StatusForbidden)
		return true
	}
	engineRef, engine := s.readyEngine()
	if engine == nil {
		http.Error(writer, "adblock engine is not ready", http.StatusServiceUnavailable)
		return true
	}
	defer engineRef.release()

	selectors, err := engine.HiddenClassIDSelectors(compactStrings(payload.Classes), compactStrings(payload.IDs), session.exceptions)
	if err != nil {
		s.debug("dynamic cosmetic selector query failed: ", err)
		http.Error(writer, "selector query failed", http.StatusInternalServerError)
		return true
	}
	if len(selectors) == 0 {
		writer.WriteHeader(http.StatusNoContent)
		return true
	}
	writer.Header().Set("Content-Type", "application/json")
	if err = json.NewEncoder(writer).Encode(cosmeticSelectorResponse{CSS: cosmeticStyle(selectors)}); err != nil {
		s.debug("dynamic cosmetic selector response failed: ", err)
	}
	return true
}
