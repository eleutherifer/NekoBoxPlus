package libcore

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"errors"
	"libcore/device"
	"log"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/adblock/adblockrust/resources"
	"github.com/sagernet/sing-box/experimental/adblock"
	"github.com/sagernet/sing-box/experimental/adblock/assets"
	adblockdb "github.com/sagernet/sing-box/experimental/adblock/db"
	boxLog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/filemanager"
)

const adblockCAName = "NekoBox+ Adblock CA"

func init() {
	assets.ErrorPagesGenerator = "NekoBox+"
}

type adblockStats struct {
	Total   uint64 `json:"total"`
	Blocked uint64 `json:"blocked"`
}

type adblockFilterMetadata struct {
	Title       string `json:"title"`
	Description string `json:"description"`
}

type adblockFilterUpdate struct {
	URL          string `json:"url"`
	LastUpdated  string `json:"lastUpdated"`
	LastModified string `json:"lastModified"`
	Error        string `json:"error"`
}

func AdblockStats(instance *BoxInstance) (results string) {
	defer device.DeferPanicToError("AdblockStats", func(err error) {
		log.Println(err)
		results = `{"total":0,"blocked":0}`
	})
	var stats adblockStats
	if instance != nil {
		instance.access.Lock()
		adblockService := service.FromContext[adapter.AdblockService](instance.ctx)
		if adblockService != nil {
			stats.Total = adblockService.Stats().TotalRequests()
			stats.Blocked = adblockService.Stats().BlockedRequests()
		}
		instance.access.Unlock()
	}
	content, err := json.Marshal(stats)
	if err != nil {
		return `{"total":0,"blocked":0}`
	}
	return string(content)
}

func AdblockStatsFromCache(databasePath string) (results string) {
	defer device.DeferPanicToError("AdblockStatsFromCache", func(err error) {
		log.Println(err)
		results = `{"total":0,"blocked":0}`
	})
	adblockBridgeMu.Lock()
	defer adblockBridgeMu.Unlock()
	ctx := filemanager.WithDefault(service.ContextWithDefaultRegistry(context.Background()), workingPath, tempPath, os.Getuid(), os.Getgid())
	store := adblockdb.NewReadOnly(ctx, databasePath)
	if err := store.Start(adapter.StartStateInitialize); err != nil {
		return `{"total":0,"blocked":0}`
	}
	defer store.Close()
	total, blocked, loaded := store.LoadAdblockStats()
	if !loaded {
		return `{"total":0,"blocked":0}`
	}
	content, err := json.Marshal(adblockStats{
		Total:   total,
		Blocked: blocked,
	})
	if err != nil {
		return `{"total":0,"blocked":0}`
	}
	return string(content)
}

func AdblockFilterMetadata(url string, databasePath string) (metadata string, err error) {
	defer device.DeferPanicToError("AdblockFilterMetadata", func(err_ error) {
		err = err_
		metadata = `{"title":"","description":""}`
	})
	if url == "" {
		return `{"title":"","description":""}`, nil
	}

	err = withAdblockService(databasePath, func(svc adapter.AdblockService) error {
		rawMetadata, err := svc.GetFilterMetadata(url)
		if err != nil {
			return err
		}
		content, err := json.Marshal(adblockFilterMetadata{
			Title:       rawMetadata.Title,
			Description: rawMetadata.Description,
		})
		if err != nil {
			return err
		}
		metadata = string(content)
		return nil
	})

	return
}

func AdblockFilterMetadataForInstance(instance *BoxInstance, url string, databasePath string) (metadata string, err error) {
	defer device.DeferPanicToError("AdblockFilterMetadataForInstance", func(err_ error) {
		err = err_
		metadata = `{"title":"","description":""}`
	})
	if url == "" {
		return `{"title":"","description":""}`, nil
	}
	err = withActiveAdblockService(instance, func(svc adapter.AdblockService) error {
		rawMetadata, err := svc.GetFilterMetadata(url)
		if err != nil {
			return err
		}
		content, err := json.Marshal(adblockFilterMetadata{
			Title:       rawMetadata.Title,
			Description: rawMetadata.Description,
		})
		if err != nil {
			return err
		}
		metadata = string(content)
		return nil
	})
	if errors.Is(err, os.ErrInvalid) || errors.Is(err, os.ErrNotExist) {
		return AdblockFilterMetadata(url, databasePath)
	}
	return
}

func adblockFilterMetadataInto(svc adapter.AdblockService, urls []string, metadataByURL map[string]adblockFilterMetadata) {
	for _, url := range urls {
		rawMetadata, err := svc.GetFilterMetadata(url)
		if err != nil {
			continue
		}
		metadataByURL[url] = adblockFilterMetadata{
			Title:       rawMetadata.Title,
			Description: rawMetadata.Description,
		}
	}
}

func marshalFilterMetadata(metadataByURL map[string]adblockFilterMetadata) string {
	if metadataByURL == nil {
		return "{}"
	}
	content, err := json.Marshal(metadataByURL)
	if err != nil {
		return "{}"
	}
	return string(content)
}

func AdblockFilterMetadataMap(joinedUrls string, databasePath string) (results string, err error) {
	defer device.DeferPanicToError("AdblockFilterMetadataMap", func(err_ error) {
		err = err_
		results = "{}"
	})
	urls := parseAdblockURLs(joinedUrls)
	if len(urls) == 0 {
		return "{}", nil
	}
	metadataByURL := make(map[string]adblockFilterMetadata, len(urls))
	err = withAdblockService(databasePath, func(svc adapter.AdblockService) error {
		adblockFilterMetadataInto(svc, urls, metadataByURL)
		return nil
	})
	if err != nil {
		return marshalFilterMetadata(metadataByURL), nil
	}
	return marshalFilterMetadata(metadataByURL), nil
}

func AdblockFilterMetadataMapForInstance(instance *BoxInstance, joinedUrls string, databasePath string) (results string, err error) {
	defer device.DeferPanicToError("AdblockFilterMetadataMapForInstance", func(err_ error) {
		err = err_
		results = "{}"
	})
	urls := parseAdblockURLs(joinedUrls)
	if len(urls) == 0 {
		return "{}", nil
	}
	metadataByURL := make(map[string]adblockFilterMetadata, len(urls))
	err = withActiveAdblockService(instance, func(svc adapter.AdblockService) error {
		adblockFilterMetadataInto(svc, urls, metadataByURL)
		return nil
	})
	if errors.Is(err, os.ErrInvalid) || errors.Is(err, os.ErrNotExist) {
		return AdblockFilterMetadataMap(joinedUrls, databasePath)
	}
	if err != nil {
		return marshalFilterMetadata(metadataByURL), nil
	}
	return marshalFilterMetadata(metadataByURL), nil
}

func AdblockStoredFilterVersion(url string, databasePath string) (version string, err error) {
	defer device.DeferPanicToError("AdblockStoredFilterVersion", func(err_ error) { err = err_ })
	if url == "" {
		return "", nil
	}
	err = withAdblockService(databasePath, func(svc adapter.AdblockService) error {
		concrete, ok := svc.(*adblock.Service)
		if !ok {
			return nil
		}
		meta, err := concrete.GetStoredFilterMetadata(url, databasePath)
		if err != nil {
			return err
		}
		if meta == nil || meta.LastUpdated.IsZero() || adblock.FilterLastModified(meta.LastModified) == "" {
			return nil
		}
		version = meta.LastModified
		if adblock.FilterLastModified(version) == "" {
			version = meta.LastUpdated.Format(time.DateTime)
		}
		return nil
	})
	return
}

func AdblockStoredFilterVersionForInstance(instance *BoxInstance, url string, databasePath string) (version string, err error) {
	defer device.DeferPanicToError("AdblockStoredFilterVersionForInstance", func(err_ error) { err = err_ })
	if url == "" {
		return "", nil
	}
	err = withActiveAdblockService(instance, func(svc adapter.AdblockService) error {
		concrete, ok := svc.(*adblock.Service)
		if !ok {
			return nil
		}
		meta, err := concrete.GetStoredFilterMetadata(url, databasePath)
		if err != nil {
			return err
		}
		if meta == nil || meta.LastUpdated.IsZero() || adblock.FilterLastModified(meta.LastModified) == "" {
			return nil
		}
		version = meta.LastModified
		if adblock.FilterLastModified(version) == "" {
			version = meta.LastUpdated.Format(time.DateTime)
		}
		return nil
	})
	if errors.Is(err, os.ErrInvalid) || errors.Is(err, os.ErrNotExist) {
		return AdblockStoredFilterVersion(url, databasePath)
	}
	return
}

// adblockStoredFilterVersionsInto loads the stored last-updated version for each
// url from the provided service into versions. Errors for individual urls are
// ignored so one missing entry does not blank out the rest.
func adblockStoredFilterVersionsInto(svc adapter.AdblockService, urls []string, databasePath string, versions map[string]string) {
	concrete, ok := svc.(*adblock.Service)
	if !ok {
		return
	}
	for _, url := range urls {
		meta, err := concrete.GetStoredFilterMetadata(url, databasePath)
		if err != nil || meta == nil || meta.LastUpdated.IsZero() {
			continue
		}
		version := adblock.FilterLastModified(meta.LastModified)
		if version == "" {
			version = meta.LastUpdated.Format(time.DateTime)
		}
		versions[url] = version
	}
}

func marshalVersions(versions map[string]string) string {
	if versions == nil {
		return "{}"
	}
	content, err := json.Marshal(versions)
	if err != nil {
		return "{}"
	}
	return string(content)
}

// AdblockStoredFilterVersions resolves the stored version for every url in
// joinedUrls in a single pass, returning a JSON object {"url": "version"}.
// Batching avoids one gobind cgo callback per filter (which, under GC pressure
// from a running proxy, triggered write-barrier corruption during the per-call
// return marshaling).
func AdblockStoredFilterVersions(joinedUrls string, databasePath string) (results string, err error) {
	defer device.DeferPanicToError("AdblockStoredFilterVersions", func(err_ error) {
		err = err_
		results = "{}"
	})
	urls := parseAdblockURLs(joinedUrls)
	if len(urls) == 0 {
		return "{}", nil
	}
	versions := make(map[string]string, len(urls))
	err = withAdblockService(databasePath, func(svc adapter.AdblockService) error {
		adblockStoredFilterVersionsInto(svc, urls, databasePath, versions)
		return nil
	})
	if err != nil {
		return marshalVersions(versions), nil
	}
	return marshalVersions(versions), nil
}

func AdblockStoredFilterVersionsForInstance(instance *BoxInstance, joinedUrls string, databasePath string) (results string, err error) {
	defer device.DeferPanicToError("AdblockStoredFilterVersionsForInstance", func(err_ error) {
		err = err_
		results = "{}"
	})
	urls := parseAdblockURLs(joinedUrls)
	if len(urls) == 0 {
		return "{}", nil
	}
	versions := make(map[string]string, len(urls))
	err = withActiveAdblockService(instance, func(svc adapter.AdblockService) error {
		adblockStoredFilterVersionsInto(svc, urls, databasePath, versions)
		return nil
	})
	if errors.Is(err, os.ErrInvalid) || errors.Is(err, os.ErrNotExist) {
		return AdblockStoredFilterVersions(joinedUrls, databasePath)
	}
	if err != nil {
		return marshalVersions(versions), nil
	}
	return marshalVersions(versions), nil
}

func AdblockPreCacheFilter(url string, databasePath string) (version string, err error) {
	defer device.DeferPanicToError("AdblockPreCacheFilter", func(err_ error) { err = err_ })
	if url == "" {
		return "", nil
	}
	err = withAdblockService(databasePath, func(svc adapter.AdblockService) error {
		v, err := svc.PreCacheFilter(url, databasePath)
		if err != nil {
			return err
		}
		version = v
		return nil
	})
	return
}

func AdblockDeleteCachedFilter(url string, databasePath string) error {
	return AdblockDeleteCachedFilters(url, databasePath)
}

func AdblockDeleteCachedFilters(joinedUrls string, databasePath string) (err error) {
	defer device.DeferPanicToError("AdblockDeleteCachedFilters", func(err_ error) { err = err_ })
	urls := parseAdblockURLs(joinedUrls)
	if len(urls) == 0 {
		return nil
	}
	return withAdblockService(databasePath, func(svc adapter.AdblockService) error {
		var joined error
		for _, url := range urls {
			joined = errors.Join(joined, svc.DeleteCachedFilter(url, databasePath))
		}
		return joined
	})
}

func AdblockDeleteCachedFilterForInstance(instance *BoxInstance, url string, databasePath string) error {
	return AdblockDeleteCachedFiltersForInstance(instance, url, databasePath)
}

func AdblockDeleteCachedFiltersForInstance(instance *BoxInstance, joinedUrls string, databasePath string) (err error) {
	defer device.DeferPanicToError("AdblockDeleteCachedFiltersForInstance", func(err_ error) { err = err_ })
	urls := parseAdblockURLs(joinedUrls)
	if len(urls) == 0 {
		return nil
	}
	err = withActiveAdblockService(instance, func(svc adapter.AdblockService) error {
		var joined error
		for _, url := range urls {
			joined = errors.Join(joined, svc.DeleteCachedFilter(url, databasePath))
		}
		return joined
	})
	if errors.Is(err, os.ErrInvalid) || errors.Is(err, os.ErrNotExist) {
		return AdblockDeleteCachedFilters(joinedUrls, databasePath)
	}
	return err
}

func adblockDeleteCachedFilterLegacy(url string, databasePath string) error {
	if url == "" {
		return nil
	}
	return withAdblockService(databasePath, func(svc adapter.AdblockService) error {
		return svc.DeleteCachedFilter(url, databasePath)
	})
}

// AdblockReloadEngine triggers a throttled reload of the running adblock
// engine from the cached filter database, without a proxy restart. It only
// affects the live engine of the supplied instance; if the box is not running
// there is nothing to reload and the call is a safe no-op (the next start will
// read the database).
func AdblockReloadEngine(instance *BoxInstance) (err error) {
	defer device.DeferPanicToError("AdblockReloadEngine", func(err_ error) { err = err_ })
	if instance == nil {
		return os.ErrInvalid
	}
	return withActiveAdblockService(instance, func(svc adapter.AdblockService) error {
		concrete, ok := svc.(*adblock.Service)
		if !ok {
			return nil
		}
		concrete.ReloadEngine()
		return nil
	})
}

func AdblockPreCacheFilterForInstance(instance *BoxInstance, url string, databasePath string) (version string, err error) {
	defer device.DeferPanicToError("AdblockPreCacheFilterForInstance", func(err_ error) { err = err_ })
	if url == "" {
		return "", nil
	}
	err = withActiveAdblockService(instance, func(svc adapter.AdblockService) error {
		v, err := svc.PreCacheFilter(url, databasePath)
		if err != nil {
			return err
		}
		version = v
		return nil
	})
	if errors.Is(err, os.ErrInvalid) || errors.Is(err, os.ErrNotExist) {
		return AdblockPreCacheFilter(url, databasePath)
	}
	return
}

func AdblockPreCacheFilters(joinedUrls string, databasePath string) (results string, err error) {
	defer device.DeferPanicToError("AdblockPreCacheFilters", func(err_ error) {
		err = err_
		results = "[]"
	})
	urls := parseAdblockURLs(joinedUrls)
	if len(urls) == 0 {
		return "[]", nil
	}
	var updates []adblockFilterUpdate
	err = withAdblockService(databasePath, func(svc adapter.AdblockService) error {
		concrete, ok := svc.(*adblock.Service)
		if !ok {
			return nil
		}
		ch := concrete.PreCacheFilters(urls, databasePath)
		for r := range ch {
			item := adblockFilterUpdate{
				URL:          r.URL(),
				LastUpdated:  r.LastUpdated(),
				LastModified: r.LastModified(),
			}
			if r.Error() != nil {
				item.Error = r.Error().Error()
			}
			updates = append(updates, item)
		}
		return nil
	})
	if err != nil {
		return "", err
	}
	if updates == nil {
		return "[]", nil
	}
	content, err := json.Marshal(updates)
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func AdblockPreCacheFiltersForInstance(instance *BoxInstance, joinedUrls string, databasePath string) (results string, err error) {
	defer device.DeferPanicToError("AdblockPreCacheFiltersForInstance", func(err_ error) {
		err = err_
		results = "[]"
	})
	urls := parseAdblockURLs(joinedUrls)
	if len(urls) == 0 {
		return "[]", nil
	}
	var updates []adblockFilterUpdate
	err = withActiveAdblockService(instance, func(svc adapter.AdblockService) error {
		concrete, ok := svc.(*adblock.Service)
		if !ok {
			return nil
		}
		ch := concrete.PreCacheFilters(urls, databasePath)
		for r := range ch {
			item := adblockFilterUpdate{
				URL:          r.URL(),
				LastUpdated:  r.LastUpdated(),
				LastModified: r.LastModified(),
			}
			if r.Error() != nil {
				item.Error = r.Error().Error()
			}
			updates = append(updates, item)
		}
		return nil
	})
	if errors.Is(err, os.ErrInvalid) || errors.Is(err, os.ErrNotExist) {
		return AdblockPreCacheFilters(joinedUrls, databasePath)
	}
	if err != nil {
		return "", err
	}
	if updates == nil {
		return "[]", nil
	}
	content, err := json.Marshal(updates)
	if err != nil {
		return "", err
	}
	return string(content), nil
}

// adblockBridgeMu serializes temporary bridge services that open the adblock
// database outside the main box lifecycle.
var adblockBridgeMu sync.Mutex

func parseAdblockURLs(joinedUrls string) []string {
	var urls []string
	for _, line := range strings.Split(joinedUrls, "\n") {
		line = strings.TrimSpace(line)
		if line != "" {
			urls = append(urls, line)
		}
	}
	return urls
}

// withActiveAdblockService resolves the running box's adblock service and runs
// cb against it. instance.access is held only long enough to fetch the service
// reference, then released so bbolt reads and (for PreCacheFilters) network I/O
// never block box management operations. If the box is reloaded concurrently,
// the service's store is closed and its methods return os.ErrClosed, which is a
// safe error rather than corruption.
func withActiveAdblockService(instance *BoxInstance, cb func(svc adapter.AdblockService) error) error {
	if instance == nil {
		return os.ErrInvalid
	}
	instance.access.Lock()
	adblockService := service.FromContext[adapter.AdblockService](instance.ctx)
	instance.access.Unlock()
	if adblockService == nil {
		return os.ErrNotExist
	}
	return cb(adblockService)
}

func withAdblockService(databasePath string, cb func(svc adapter.AdblockService) error) error {
	adblockBridgeMu.Lock()
	defer adblockBridgeMu.Unlock()
	ctx := filemanager.WithDefault(service.ContextWithDefaultRegistry(context.Background()), workingPath, tempPath, os.Getuid(), os.Getgid())
	adblockService, err := adblock.New(ctx, boxLog.NewNOPFactory().Logger(), option.AdblockOptions{
		Enabled:      true,
		DatabasePath: databasePath,
		Filters: &option.AdblockFilters{
			Rules: []string{"||metadata.invalid^"},
		},
	})
	if err != nil {
		return err
	}
	if err = adblockService.Start(adapter.StartStateInitialize); err != nil {
		return err
	}
	if err = adblockService.Start(adapter.StartStateStart); err != nil {
		return err
	}
	defer adblockService.Close()
	return cb(adblockService)
}

func AdblockBundledResourcesPath() (string, error) {
	return resources.GetBundledAssets(internalAssetsPath)
}

func EnsureAdblockCA(certificatePath string, keyPath string) error {
	if certificatePath != "" && keyPath != "" {
		if validAdblockCA(certificatePath) && fileExists(keyPath) {
			return nil
		}
	}

	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return err
	}
	serialNumberLimit := new(big.Int).Lsh(big.NewInt(1), 128)
	serialNumber, err := rand.Int(rand.Reader, serialNumberLimit)
	if err != nil {
		return err
	}
	now := time.Now()
	template := x509.Certificate{
		SerialNumber: serialNumber,
		Subject: pkix.Name{
			CommonName: adblockCAName,
		},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	certificateDER, err := x509.CreateCertificate(rand.Reader, &template, &template, &privateKey.PublicKey, privateKey)
	if err != nil {
		return err
	}
	if err = os.MkdirAll(filepath.Dir(certificatePath), 0o755); err != nil {
		return err
	}
	if err = os.MkdirAll(filepath.Dir(keyPath), 0o755); err != nil {
		return err
	}
	certificatePEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certificateDER})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(privateKey)})
	if err = os.WriteFile(certificatePath, certificatePEM, 0o644); err != nil {
		return err
	}
	return os.WriteFile(keyPath, keyPEM, 0o600)
}

func validAdblockCA(certificatePath string) bool {
	content, err := os.ReadFile(certificatePath)
	if err != nil {
		return false
	}
	block, _ := pem.Decode(content)
	if block == nil {
		return false
	}
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return false
	}
	return certificate.IsCA &&
		certificate.Subject.CommonName == adblockCAName &&
		certificate.KeyUsage&x509.KeyUsageCertSign != 0 &&
		time.Now().Before(certificate.NotAfter)
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
