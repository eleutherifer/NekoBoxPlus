package vless

func needsEnhancedVision(vision bool, encrypted bool, transported bool) bool {
	return vision && (encrypted || transported)
}

func canDirectEnhancedVision(encrypted bool, fullRandomEncryption bool, transported bool) bool {
	if encrypted {
		return !fullRandomEncryption
	}
	return !transported
}
