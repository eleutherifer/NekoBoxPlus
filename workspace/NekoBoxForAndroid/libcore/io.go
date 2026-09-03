package libcore

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/ulikunitz/xz"
)

func Unxz(archive string, path string) (err error) {
	i, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer func() {
		err = E.Errors(err, common.Close(i))
	}()
	r, err := xz.NewReader(i)
	if err != nil {
		return err
	}
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	defer func() {
		err = E.Errors(err, common.Close(o))
	}()
	_, err = io.Copy(o, r)
	return err
}

func Unzip(archive string, path string) (err error) {
	r, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer func() {
		err = E.Errors(err, common.Close(r))
	}()

	err = os.MkdirAll(path, os.ModePerm)
	if err != nil {
		return err
	}

	for _, file := range r.File {
		filePath := filepath.Join(path, file.Name)

		if file.FileInfo().IsDir() {
			err = os.MkdirAll(filePath, os.ModePerm)
			if err != nil {
				return err
			}
			continue
		}

		newFile, err := os.Create(filePath)
		if err != nil {
			return err
		}

		zipFile, err := file.Open()
		if err != nil {
			return E.Errors(err, common.Close(newFile))
		}

		var errs error
		_, err = io.Copy(newFile, zipFile)
		errs = E.Errors(errs, err)
		errs = E.Errors(errs, common.Close(zipFile, newFile))
		if errs != nil {
			return errs
		}
	}

	return nil
}

func UntarGz(archive string, path string) (err error) {
	file, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer func() {
		err = E.Errors(err, common.Close(file))
	}()

	gzr, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer func() {
		err = E.Errors(err, common.Close(gzr))
	}()

	tr := tar.NewReader(gzr)

	err = os.MkdirAll(path, os.ModePerm)
	if err != nil {
		return err
	}

	for {
		header, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}

		name := filepath.Clean(header.Name)
		if name == "." {
			continue
		}
		target := filepath.Join(path, name)

		// Prevent ZipSlip/TarSlip
		cleanPath := filepath.Clean(path) + string(os.PathSeparator)
		cleanTarget := filepath.Clean(target)
		if !strings.HasPrefix(cleanTarget, cleanPath) {
			return E.New("invalid tar entry path: ", header.Name)
		}

		switch header.Typeflag {
		case tar.TypeDir:
			err = os.MkdirAll(cleanTarget, os.FileMode(header.Mode))
			if err != nil {
				return err
			}

		case tar.TypeReg:
			err = os.MkdirAll(filepath.Dir(cleanTarget), os.ModePerm)
			if err != nil {
				return err
			}

			outFile, err := os.OpenFile(
				cleanTarget,
				os.O_CREATE|os.O_WRONLY|os.O_TRUNC,
				os.FileMode(header.Mode),
			)
			if err != nil {
				return err
			}

			var errs error
			_, err = io.Copy(outFile, tr)
			errs = E.Errors(errs, err)
			errs = E.Errors(errs, outFile.Close())
			if errs != nil {
				return errs
			}
		}
	}

	return nil
}
