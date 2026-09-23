package vless

func needsEnhancedVision(vision bool, encrypted bool, transported bool) bool {
	return vision && (encrypted || transported)
}

func canSpliceEnhancedVision(fullRandomEncryption bool, transported bool) bool {
	return !fullRandomEncryption && !transported
}
