//go:build with_adblock

package resources

import (
	"embed"
	"hash/crc32"
	"io/fs"
	"mime"
	"os"
	"path/filepath"
	"strings"
	"sync"

	E "github.com/sagernet/sing/common/exceptions"
)

var (
	extracted   bool
	extractLock sync.Mutex
	extractPath string
)

//go:embed files/placeholder.txt files/dist files/resources files/src/js/redirect-resources.js files/src/web_accessible_resources
var bundledAdblockResources embed.FS

var crc32cTable = crc32.MakeTable(crc32.Castagnoli)

type webAccessibleResource struct {
	content     []byte
	contentType string
}

var bundledWebAccessibleResources = sync.OnceValue(func() map[string]webAccessibleResource {
	const root = "files/src/web_accessible_resources"
	result := make(map[string]webAccessibleResource)
	_ = fs.WalkDir(bundledAdblockResources, root, func(path string, entry fs.DirEntry, err error) error {
		if err != nil || entry.IsDir() {
			return err
		}
		name := strings.TrimPrefix(path, root+"/")
		if name == "" || strings.Contains(name, "/") {
			return nil
		}
		content, err := fs.ReadFile(bundledAdblockResources, path)
		if err != nil {
			return err
		}
		result[name] = webAccessibleResource{
			content:     content,
			contentType: webAccessibleResourceContentType(name),
		}
		return nil
	})
	return result
})

func webAccessibleResourceContentType(name string) string {
	switch strings.ToLower(filepath.Ext(name)) {
	case ".css":
		return "text/css"
	case ".gif":
		return "image/gif"
	case ".html":
		return "text/html"
	case ".js":
		return "application/javascript"
	case ".json":
		return "application/json"
	case ".mp3":
		return "audio/mp3"
	case ".mp4":
		return "video/mp4"
	case ".png":
		return "image/png"
	case ".txt", "":
		return "text/plain"
	case ".xml":
		return "text/xml"
	default:
		if contentType := mime.TypeByExtension(filepath.Ext(name)); contentType != "" {
			return contentType
		}
		return "application/octet-stream"
	}
}

func GetWebAccessibleResource(name string) ([]byte, string, bool) {
	resource, loaded := bundledWebAccessibleResources()[name]
	if !loaded {
		return nil, "", false
	}
	return resource.content, resource.contentType, true
}

func ExtractBundledAssets(targetDir string) (string, error) {
	extractLock.Lock()
	defer extractLock.Unlock()

	if extracted {
		return extractPath, nil
	}

	if targetDir == "" {
		targetDir = os.TempDir()
	}

	destination := filepath.Join(targetDir, "sing-box-adblock-resources")
	filesDestination := filepath.Join(destination, "files")

	if err := syncEmbeddedFS(bundledAdblockResources, "files", filesDestination); err != nil {
		return "", err
	}

	extractPath, extracted = filesDestination, true
	return extractPath, nil
}

func syncEmbeddedFS(sourceFS fs.FS, sourceRoot string, destinationRoot string) error {
	embeddedFiles := make(map[string]struct{})

	if err := fs.WalkDir(sourceFS, sourceRoot, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}

		relPath, err := filepath.Rel(sourceRoot, path)
		if err != nil {
			return err
		}
		if relPath == "." {
			return os.MkdirAll(destinationRoot, 0o755)
		}

		target := filepath.Join(destinationRoot, filepath.FromSlash(relPath))

		if entry.IsDir() {
			return os.MkdirAll(target, 0o755)
		}

		if entry.Type()&fs.ModeType != 0 {
			// Skip non-regular embedded entries if any ever appear.
			return nil
		}

		embeddedFiles[filepath.Clean(target)] = struct{}{}

		content, err := fs.ReadFile(sourceFS, path)
		if err != nil {
			return err
		}

		needsWrite, err := fileContentDiffers(target, content)
		if err != nil {
			return err
		}
		if !needsWrite {
			return nil
		}

		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}

		return writeFileAtomic(target, content, 0o644)
	}); err != nil {
		return err
	}

	return removeStaleFiles(destinationRoot, embeddedFiles)
}

func fileContentDiffers(path string, expected []byte) (bool, error) {
	info, err := os.Stat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return true, nil
		}
		return false, err
	}

	if info.IsDir() {
		return true, nil
	}

	if info.Size() != int64(len(expected)) {
		return true, nil
	}

	actual, err := os.ReadFile(path)
	if err != nil {
		return false, err
	}

	return crc32.Checksum(actual, crc32cTable) != crc32.Checksum(expected, crc32cTable), nil
}

func removeStaleFiles(destinationRoot string, embeddedFiles map[string]struct{}) error {
	if _, err := os.Stat(destinationRoot); os.IsNotExist(err) {
		return nil
	} else if err != nil {
		return err
	}

	return filepath.WalkDir(destinationRoot, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}

		if entry.IsDir() {
			return nil
		}

		cleanPath := filepath.Clean(path)
		if _, ok := embeddedFiles[cleanPath]; ok {
			return nil
		}

		return os.Remove(cleanPath)
	})
}

func writeFileAtomic(path string, content []byte, perm fs.FileMode) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}

	tmpName := tmp.Name()
	defer os.Remove(tmpName)

	if _, err := tmp.Write(content); err != nil {
		_ = tmp.Close()
		return err
	}

	if err := tmp.Chmod(perm); err != nil {
		_ = tmp.Close()
		return err
	}

	if err := tmp.Close(); err != nil {
		return err
	}

	return os.Rename(tmpName, path)
}

func GetBundledAssets(targetDir string) (string, error) {
	path, err := ExtractBundledAssets(targetDir)
	if err != nil {
		return "", E.Cause(err, "extract bundled adblock resources")
	}
	if _, err := os.Stat(filepath.Join(path, "dist", "resources.json")); err == nil {
		return path, nil
	}
	if _, err := os.Stat(filepath.Join(path, "resources", "resources.json")); err == nil {
		return path, nil
	}
	if _, err := os.Stat(filepath.Join(path, "src", "js", "redirect-resources.js")); err == nil {
		return path, nil
	}
	return "", E.New("bundled adblock resources are not generated")
}
