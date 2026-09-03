//go:build android

package libcore

import (
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"strconv"
	"strings"

	"github.com/sagernet/gomobile/asset"
)

const customAssetVersionPrefix = "custom:"

func extractAssets() bool {
	useOfficialAssets := intfNB4A.UseOfficialAssets()
	var changed bool

	extract := func(name string) {
		extracted, err := extractAssetName(name, useOfficialAssets)
		if err != nil {
			log.Println("Extract", name, "failed:", err)
			return
		}
		changed = changed || extracted
	}

	extract(geoipDat)
	extract(geositeDat)
	extract(throneRulesetDat)
	extract(itdogRulesetDat)
	extract(metacubexdDstFolder)
	return changed
}

func resetPanelAssets() error {
	if err := os.RemoveAll(internalAssetsPath + metacubexdDstFolder); err != nil {
		return err
	}
	if err := os.Remove(internalAssetsPath + metacubexdVersion); err != nil && !os.IsNotExist(err) {
		return err
	}
	_, err := extractAssetName(metacubexdDstFolder, false)
	return err
}

// 这里解压的是 apk 里面的
func extractAssetName(name string, useOfficialAssets bool) (bool, error) {
	// 支持非官方源的，就是 replaceable，放 Android 目录
	// 不支持非官方源的，就放 file 目录
	replaceable := true

	var version string
	var apkPrefix string
	switch name {
	case geoipDat:
		version = geoipVersion
		apkPrefix = apkAssetPrefixSingBox
	case geositeDat:
		version = geositeVersion
		apkPrefix = apkAssetPrefixSingBox
	case throneRulesetDat:
		version = throneRulesetVersion
		apkPrefix = apkAssetPrefixSingBox
	case itdogRulesetDat:
		version = itdogRulesetVersion
		apkPrefix = apkAssetPrefixSingBox
	case metacubexdDstFolder:
		version = metacubexdVersion
		replaceable = false
	}

	var dir string
	if !replaceable {
		dir = internalAssetsPath
	} else {
		dir = externalAssetsPath
	}
	dstName := dir + name
	tmpDstName := dstName + ".tmp"

	var localVersion string
	var assetVersion string

	// loadAssetVersion from APK
	loadAssetVersion := func() error {
		av, err := asset.Open(apkPrefix + version)
		if err != nil {
			return fmt.Errorf("open version in assets: %v", err)
		}
		b, err := io.ReadAll(av)
		av.Close()
		if err != nil {
			return fmt.Errorf("read internal version: %v", err)
		}
		assetVersion = string(b)
		return nil
	}
	if err := loadAssetVersion(); err != nil {
		return false, err
	}

	var doExtract bool

	if _, err := os.Stat(dstName); err != nil {
		// assetFileMissing
		doExtract = true
	} else if useOfficialAssets || !replaceable {
		// 官方源升级
		b, err := os.ReadFile(dir + version)
		if err != nil {
			// versionFileMissing
			doExtract = true
			_ = os.RemoveAll(dir + version)
		} else {
			localVersion = string(b)
			if strings.HasPrefix(localVersion, customAssetVersionPrefix) {
				doExtract = false
			} else {
				av, err := strconv.ParseUint(assetVersion, 10, 64)
				if err != nil {
					doExtract = assetVersion != localVersion
				} else {
					lv, err := strconv.ParseUint(localVersion, 10, 64)
					doExtract = err != nil || av > lv
				}
			}
		}
	} else {
		//非官方源不升级
	}

	if !doExtract {
		return false, nil
	}

	extractXz := func(f asset.File) error {
		tmpXzName := tmpDstName + ".xz"
		err := extractAsset(f, tmpXzName)
		if err == nil {
			err = Unxz(tmpXzName, tmpDstName)
			os.Remove(tmpXzName)
		}
		if err != nil {
			return fmt.Errorf("extract xz: %v", err)
		}
		return nil
	}

	extractTarGz := func(f asset.File, outDir string) error {
		tmpTarGzName := tmpDstName + ".tgz"
		err := extractAsset(f, tmpTarGzName)
		if err == nil {
			err = UntarGz(tmpTarGzName, outDir)
			os.Remove(tmpTarGzName)
		}
		if err != nil {
			return fmt.Errorf("extract tgz: %v", err)
		}
		return nil
	}

	_ = os.RemoveAll(tmpDstName)
	if f, err := asset.Open(apkPrefix + name + ".xz"); err == nil {
		if err := extractXz(f); err != nil {
			_ = os.RemoveAll(tmpDstName)
			return false, err
		}
	} else if f, err := asset.Open(apkPrefix + name); err == nil {
		err = extractAsset(f, tmpDstName)
		if err != nil {
			_ = os.RemoveAll(tmpDstName)
			return false, fmt.Errorf("extract asset: %v", err)
		}
	} else if f, err := asset.Open("metacubexd.tgz"); err == nil {
		_ = os.RemoveAll(tmpDstName)
		if err := os.MkdirAll(tmpDstName, 0o755); err != nil {
			return false, fmt.Errorf("mkdir metacubexd temp dir: %v", err)
		}
		if err := extractTarGz(f, tmpDstName); err != nil {
			_ = os.RemoveAll(tmpDstName)
			return false, err
		}
		os.RemoveAll(dstName)
	} else {
		return false, fmt.Errorf("asset not found: %s", apkPrefix+name)
	}

	if err := os.Rename(tmpDstName, dstName); err != nil {
		_ = os.RemoveAll(tmpDstName)
		return false, fmt.Errorf("commit extracted asset: %v", err)
	}

	versionName := dir + version
	tmpVersionName := versionName + ".tmp"
	o, err := os.Create(tmpVersionName)
	if err != nil {
		return false, fmt.Errorf("create version: %v", err)
	}
	_, err = io.WriteString(o, assetVersion)
	err = errors.Join(err, o.Close())
	if err != nil {
		_ = os.Remove(tmpVersionName)
		return false, err
	}
	if err = os.Rename(tmpVersionName, versionName); err != nil {
		_ = os.Remove(tmpVersionName)
		return false, fmt.Errorf("commit extracted version: %v", err)
	}
	return true, nil
}

func extractAsset(i asset.File, path string) error {
	defer i.Close()
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	defer o.Close()
	_, err = io.Copy(o, i)
	if err == nil {
		log.Println("Extract >>", path)
	}
	return err
}
