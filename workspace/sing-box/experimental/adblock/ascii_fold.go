//go:build with_adblock

package adblock

func indexASCIIStringFold(value string, lowerPattern string) int {
	if lowerPattern == "" {
		return 0
	}
	last := len(value) - len(lowerPattern)
	for start := 0; start <= last; start++ {
		matched := true
		for index := range len(lowerPattern) {
			char := value[start+index]
			if char >= 'A' && char <= 'Z' {
				char += 'a' - 'A'
			}
			if char != lowerPattern[index] {
				matched = false
				break
			}
		}
		if matched {
			return start
		}
	}
	return -1
}
