package byedpi

import "slices"

func sanitizeArgs(args []string) []string {
	skipWithValue := map[string]struct{}{
		"-i":             {},
		"--ip":           {},
		"-p":             {},
		"--port":         {},
		"-P":             {},
		"--protect-path": {},
		"-w":             {},
		"--pidfile":      {},
		"-y":             {},
		"--cache-dump":   {},
		"-B":             {},
		"--copy":         {},
		"-x":             {},
		"--debug":        {},
	}
	skipStandalone := []string{
		"-D",
		"--daemon",
		"-h",
		"--help",
		"-v",
		"--version",
	}

	filtered := make([]string, 0, len(args))
	for index := 0; index < len(args); index++ {
		arg := args[index]
		if _, ok := skipWithValue[arg]; ok {
			if index+1 < len(args) {
				index++
			}
			continue
		}
		if slices.Contains(skipStandalone, arg) {
			continue
		}
		filtered = append(filtered, arg)
	}
	return filtered
}
