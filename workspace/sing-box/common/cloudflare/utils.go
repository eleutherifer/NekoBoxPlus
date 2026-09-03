package cloudflare

import (
	"crypto/rand"
	"encoding/hex"
	"time"
)

func GenerateRandomAndroidSerial() (string, error) {
	serial := make([]byte, 8)
	if _, err := rand.Read(serial); err != nil {
		return "", err
	}
	return hex.EncodeToString(serial), nil
}

func TimeAsCfString(t time.Time) string {
	return t.Format("2006-01-02T15:04:05.000-07:00")
}
