package masterdnsvpn

import (
	"bytes"
	"strings"

	"github.com/sagernet/sing/common/logger"
)

type masterDnsVPNSingBoxLogWriter struct {
	logger logger.ContextLogger
}

func (w masterDnsVPNSingBoxLogWriter) Write(p []byte) (int, error) {
	for _, raw := range bytes.Split(p, []byte{'\n'}) {
		line := strings.TrimSpace(string(raw))
		if line == "" {
			continue
		}
		w.logger.Info("[MasterDnsVPN] ", trimMasterDnsVPNLogPrefix(line))
	}
	return len(p), nil
}

func trimMasterDnsVPNLogPrefix(line string) string {
	if idx := strings.LastIndex(line, "] "); idx >= 0 && idx+2 < len(line) {
		return strings.TrimSpace(line[idx+2:])
	}
	return line
}
