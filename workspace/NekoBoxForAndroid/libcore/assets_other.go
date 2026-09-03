//go:build !android

package libcore

func extractAssets() bool { return false }

func resetPanelAssets() error {
	return nil
}
