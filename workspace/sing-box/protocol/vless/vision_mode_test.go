package vless

import "testing"

func TestNeedsEnhancedVision(t *testing.T) {
	tests := []struct {
		name        string
		vision      bool
		encrypted   bool
		transported bool
		expected    bool
	}{
		{"plain VLESS", false, false, false, false},
		{"encrypted VLESS", false, true, false, false},
		{"upstream Vision", true, false, false, false},
		{"encrypted Vision", true, true, false, true},
		{"transported Vision", true, false, true, true},
		{"encrypted transported Vision", true, true, true, true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			actual := needsEnhancedVision(test.vision, test.encrypted, test.transported)
			if actual != test.expected {
				t.Fatalf("needsEnhancedVision() = %v, expected %v", actual, test.expected)
			}
		})
	}
}

func TestCanSpliceEnhancedVision(t *testing.T) {
	tests := []struct {
		name                 string
		fullRandomEncryption bool
		transported          bool
		expected             bool
	}{
		{"direct", false, false, true},
		{"full random encryption", true, false, false},
		{"transport", false, true, false},
		{"full random encryption over transport", true, true, false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			actual := canSpliceEnhancedVision(test.fullRandomEncryption, test.transported)
			if actual != test.expected {
				t.Fatalf("canSpliceEnhancedVision() = %v, expected %v", actual, test.expected)
			}
		})
	}
}
