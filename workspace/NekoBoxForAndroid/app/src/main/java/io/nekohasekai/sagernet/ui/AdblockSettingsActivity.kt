package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.Param
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ui.compose.AdblockBundledFilterListItem
import io.nekohasekai.sagernet.ui.compose.AdblockBundledFiltersScreen
import io.nekohasekai.sagernet.ui.compose.AdblockBundledListItem
import io.nekohasekai.sagernet.ui.compose.AdblockCustomFilterEditorScreen
import io.nekohasekai.sagernet.ui.compose.AdblockCustomFilterEditorState
import io.nekohasekai.sagernet.ui.compose.AdblockCustomFilterListItem
import io.nekohasekai.sagernet.ui.compose.AdblockCustomFiltersScreen
import io.nekohasekai.sagernet.ui.compose.AdblockCustomRulesEditorScreen
import io.nekohasekai.sagernet.ui.compose.AdblockSetting
import io.nekohasekai.sagernet.ui.compose.AdblockSettingsScreen
import io.nekohasekai.sagernet.ui.compose.AdblockSettingsState
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.showComposeItemDialog
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog
import io.nekohasekai.sagernet.ui.compose.showComposeSingleChoiceDialog
import io.nekohasekai.sagernet.utils.AdblockRepository
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.Libcore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.NGUtil
import java.io.File

class AdblockSettingsFragment : ToolbarFragment(),
    SagerConnection.Callback {
    private companion object {
        const val CA_HELP_URL = "https://adguard.com/kb/adguard-for-android/solving-problems/manual-certificate/"

        const val DEFAULT_APPS = "routing/browsers.txt"

        const val DEFAULT_CERT_NAME = "nekobox-adblock-ca.crt"
    }

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
    private var screenState by mutableStateOf(AdblockSettingsState(
        false, "", true, false, true, false, "", "", false, true,
        false, "", false, false, "", "",
    ))
    private var reloadPrompt by mutableIntStateOf(0)
    private var statsJob: Job? = null
    private val saveCertificate = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-x509-ca-cert")
    ) { uri ->
        if (uri != null) exportCertificate(uri)
    }
    private val selectIncludedApps = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshScreen()
        showReloadPrompt()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        AdblockRepository.ensureBundledDefaults()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NekoComposeTheme {
                    AdblockSettingsScreen(
                        state = screenState,
                        reloadPrompt = reloadPrompt,
                        onOpenDrawer = { (requireActivity() as MainActivity).openDrawer() },
                        onToggle = ::toggleSetting,
                        onSaveCertificate = ::requestCertificateExport,
                        onFingerprint = ::selectFingerprint,
                        onIncludedApps = ::openIncludedApps,
                        onBundledFilters = {
                            startActivity(Intent(requireContext(), AdblockBundledFiltersActivity::class.java))
                        },
                        onCustomFilters = {
                            startActivity(Intent(requireContext(), AdblockCustomFiltersActivity::class.java))
                        },
                        onCustomRules = {
                            startActivity(Intent(requireContext(), AdblockCustomRulesActivity::class.java))
                        },
                        onApplyReload = SagerNet::reloadService,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.connect(requireContext(), this)
    }

    override fun onStop() {
        statsJob?.cancel()
        connection.disconnect(requireContext())
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshScreen()
        refreshStats()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        refreshStats()
    }

    override fun onServiceConnected(service: io.nekohasekai.sagernet.aidl.ISagerNetService) {
        refreshStats()
    }

    private fun createScreenState(stats: String = screenState.stats): AdblockSettingsState {
        val fingerprintValues = resources.getStringArray(R.array.adblock_https_fingerprint_value)
        val fingerprintEntries = resources.getStringArray(R.array.adblock_https_fingerprint_entry)
        val fingerprint = DataStore.adblockHttpsFingerprint
        val fingerprintLabel = fingerprintEntries.getOrElse(fingerprintValues.indexOf(fingerprint)) {
            fingerprint.ifBlank { getString(R.string.none) }
        }
        val apps = DataStore.adblockIncludedPackages.split('\n').filter { it.isNotBlank() }.map {
            PackageCache.installedPackages[it]?.applicationInfo?.loadLabel(requireContext().packageManager)
                ?: PackageCache.installedPluginPackages[it]?.applicationInfo?.loadLabel(requireContext().packageManager)
                ?: it
        }
        val appsSummary = when {
            apps.isEmpty() -> getString(R.string.not_set)
            apps.size <= 5 -> apps.joinToString("\n")
            else -> getString(R.string.apps_message, apps.size)
        }
        val bundledSummary = AdblockRepository.catalog
            .filter { it.id in AdblockRepository.ensureBundledDefaults() }
            .joinToString { it.title }
            .ifBlank { getString(R.string.filter_disabled) }
        val customSummary = AdblockRepository.customFilters()
            .filter(AdblockRepository::customFilterEnabled)
            .joinToString { AdblockRepository.filterDisplayTitle(it) }
            .ifBlank { getString(R.string.adblock_custom_filters_empty) }
        val mixedAvailable = DataStore.appendHttpProxy || DataStore.serviceMode == Key.MODE_PROXY
        return AdblockSettingsState(
            enabled = DataStore.adblockEnabled,
            stats = stats.ifBlank { getString(R.string.adblock_stats_unavailable) },
            dnsFiltering = DataStore.adblockDnsFiltering,
            cnameUncloaking = DataStore.adblockDnsFiltering && DataStore.adblockCnameUncloaking,
            httpFiltering = DataStore.adblockHttpFiltering,
            httpsFiltering = DataStore.adblockHttpsFiltering,
            httpsFingerprint = fingerprint,
            httpsFingerprintLabel = fingerprintLabel,
            httpsCronet = DataStore.adblockHttpsCronet,
            skipEvCerts = DataStore.adblockSkipEvCerts,
            systemWideFilter = DataStore.adblockSystemWideFilter,
            includedAppsSummary = appsSummary,
            mixedLanFiltering = mixedAvailable && DataStore.adblockMixedLanFiltering,
            mixedLanFilteringAvailable = mixedAvailable,
            bundledFiltersSummary = bundledSummary,
            customFiltersSummary = customSummary,
        )
    }

    private fun refreshScreen() {
        screenState = createScreenState()
    }

    private fun toggleSetting(setting: AdblockSetting, checked: Boolean) {
        when (setting) {
            AdblockSetting.ENABLED -> {
                DataStore.adblockEnabled = checked
                if (checked && DataStore.adblockIncludedPackages.isEmpty()) {
                    val packages = PackageCache.installedPackages.keys
                    DataStore.adblockIncludedPackages = NGUtil.readTextFromAssets(requireContext(), DEFAULT_APPS)
                        .lines().filter { it.isNotBlank() && it in packages }.joinToString("\n")
                }
            }
            AdblockSetting.DNS -> {
                DataStore.adblockDnsFiltering = checked
                if (!checked) DataStore.adblockCnameUncloaking = false
            }
            AdblockSetting.CNAME -> DataStore.adblockCnameUncloaking = checked
            AdblockSetting.HTTP -> DataStore.adblockHttpFiltering = checked
            AdblockSetting.HTTPS -> {
                if (checked) {
                    runCatching { ensureCertificate() }.onFailure {
                        Toast.makeText(requireContext(), it.message ?: getString(R.string.adblock_ca_generate_failed), Toast.LENGTH_SHORT).show()
                        return
                    }
                }
                DataStore.adblockHttpsFiltering = checked
            }
            AdblockSetting.CRONET -> DataStore.adblockHttpsCronet = checked
            AdblockSetting.SKIP_EV -> DataStore.adblockSkipEvCerts = checked
            AdblockSetting.SYSTEM_WIDE -> DataStore.adblockSystemWideFilter = checked
            AdblockSetting.MIXED_LAN -> DataStore.adblockMixedLanFiltering = checked
        }
        refreshScreen()
        showReloadPrompt()
    }

    private fun selectFingerprint() {
        val entries = resources.getStringArray(R.array.adblock_https_fingerprint_entry).toList()
        val values = resources.getStringArray(R.array.adblock_https_fingerprint_value)
        requireContext().showComposeSingleChoiceDialog(
            title = getString(R.string.adblock_https_fingerprint),
            items = entries,
            selectedIndex = values.indexOf(DataStore.adblockHttpsFingerprint),
            onItemSelected = { index ->
                DataStore.adblockHttpsFingerprint = values.getOrElse(index) { "" }
                refreshScreen()
                showReloadPrompt()
            },
        )
    }

    private fun openIncludedApps() {
        selectIncludedApps.launch(Intent(requireContext(), AppListActivity::class.java).apply {
            putExtra(AppListActivity.EXTRA_PACKAGE_LIST_KEY, Key.ADBLOCK_INCLUDED_PACKAGES)
            putExtra(AppListActivity.EXTRA_TITLE, getString(R.string.adblock_included_apps))
        })
    }

    private fun refreshStats() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) { currentAdblockStats() }
            screenState = createScreenState(formatStats(stats))
        }
    }

    private fun formatStats(statsJson: String): String = runCatching {
        val obj = JsonParser.parseString(statsJson).asJsonObject
        val total = obj["total"]?.asLong ?: 0L
        val blocked = obj["blocked"]?.asLong ?: 0L
        if (total == 0L) getString(R.string.adblock_stats_empty)
        else getString(R.string.adblock_stats, blocked, total)
    }.getOrDefault(getString(R.string.adblock_stats_unavailable))

    private fun showReloadPrompt() {
        if (DataStore.serviceState.started) reloadPrompt++
    }

    fun currentAdblockStats(): String = connection.service?.adblockStats()
        ?: Libcore.adblockStatsFromCache(Param.LIBCORE_ADBLOCK_DB_FILE_PATH)

    fun requestCertificateExport() {
        requireContext().showComposeMessageDialog(
            title = getText(R.string.adblock_save_ca_certificate),
            message = getText(R.string.adblock_save_ca_certificate_message),
            positiveButton = getText(R.string.save),
            negativeButton = getText(android.R.string.cancel),
            neutralButton = getText(R.string.adblock_save_ca_help),
            onPositive = { saveCertificate.launch(DEFAULT_CERT_NAME) },
            onNeutral = { requireContext().launchCustomTab(CA_HELP_URL) },
        )
    }

    private fun exportCertificate(uri: Uri) {
        try {
            val certificate = ensureCertificate()
            requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                certificate.inputStream().use { input -> input.copyTo(output) }
            } ?: error(getString(R.string.adblock_save_ca_failed))
            Toast.makeText(requireContext(), R.string.adblock_save_ca_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.message ?: getString(R.string.adblock_save_ca_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun ensureCertificate(): File {
        val caDir = File(requireContext().noBackupFilesDir, "adblock")
        val certFile = File(caDir, "ca.crt")
        val keyFile = File(caDir, "ca.key")
        Libcore.ensureAdblockCA(certFile.absolutePath, keyFile.absolutePath)
        DataStore.adblockCaCertificate = certFile.absolutePath
        DataStore.adblockCaKey = keyFile.absolutePath
        return certFile
    }

}

private fun composeFilterSummary(base: String, versionLine: String): String = when {
    versionLine.isBlank() -> base
    base.isBlank() -> versionLine
    else -> "$base\n$versionLine"
}

class AdblockBundledFiltersActivity : ThemedActivity(), SagerConnection.Callback {
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
    private var items by mutableStateOf<List<AdblockBundledListItem>>(emptyList())
    private var reloadPrompt by mutableIntStateOf(0)
    private val entryBaseSummary = mutableMapOf<String, String>()
    private val entryTitles = mutableMapOf<String, String>()
    private val entryDisplayUrl = mutableMapOf<String, String>()
    private val entryCachedVersion = mutableMapOf<String, String>()
    private val urlToEntryId = mutableMapOf<String, String>()
    private val updateJobs = mutableMapOf<String, Job>()
    private val updatingEntries = mutableSetOf<String>()
    private var updateAllJob: Job? = null
    private var selected = mutableSetOf<String>()

    @Volatile
    private var needReload = false

    private val adblockService: ISagerNetService?
        get() = connection.service

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeCatalog()
        setContent {
            NekoComposeTheme {
                AdblockBundledFiltersScreen(
                    items = items,
                    reloadPrompt = reloadPrompt,
                    onClose = ::finish,
                    onUpdateAll = ::updateAll,
                    onToggle = ::toggleEntry,
                    onUpdate = { updateOne(it.id) },
                    onApplyReload = SagerNet::reloadService,
                )
            }
        }
        connection.connect(this, this)
    }

    override fun onDestroy() {
        updateJobs.values.forEach { it.cancel() }
        updateJobs.clear()
        updateAllJob?.cancel()
        val pendingReload = needReload
        needReload = false
        val service = adblockService
        connection.disconnect(this)
        super.onDestroy()
        // Flush a batched engine rebuild when the user is done with the screen
        // (back/close, or the app swiped from recents), but not on a plain
        // config-change recreate. Reloads are throttled on the core side.
        if (pendingReload && !isChangingConfigurations && service != null) {
            Thread { runCatching { AdblockRepository.reloadEngine(service) } }.start()
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
    }

    override fun onServiceConnected(service: ISagerNetService) {
        loadStoredVersions()
    }

    private fun initializeCatalog() {
        selected = AdblockRepository.ensureBundledDefaults().toMutableSet()
        AdblockRepository.catalog.forEach { entry ->
            entryBaseSummary[entry.id] = entry.desc
            entryTitles[entry.id] = entry.title
            entryDisplayUrl[entry.id] = entry.sources
                .firstOrNull { it.url.isNotBlank() }?.url?.trim().orEmpty()
            entry.sources.forEach { source ->
                source.url.trim().takeIf { it.isNotBlank() }?.let { urlToEntryId[it] = entry.id }
            }
        }
        refreshItems()
        loadStoredVersions()
    }

    private fun refreshItems() {
        items = buildList {
            AdblockRepository.groupedCatalog().forEach { (category, entries) ->
                add(AdblockBundledListItem.Category(AdblockRepository.categoryTitle(category)))
                entries.forEach { entry ->
                    val versionLine = entryCachedVersion[entry.id]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { getString(R.string.adblock_filter_version, it) }
                        .orEmpty()
                    add(AdblockBundledListItem.Filter(AdblockBundledFilterListItem(
                        id = entry.id,
                        title = entry.title,
                        summary = composeFilterSummary(entry.desc, versionLine),
                        enabled = entry.id in selected,
                        updating = entry.id in updatingEntries,
                    )))
                }
            }
        }
    }

    private fun toggleEntry(item: AdblockBundledFilterListItem) {
        val checked = item.id !in selected
        if (checked) {
            selected.add(item.id)
        } else {
            selected.remove(item.id)
            entryCachedVersion[item.id] = ""
            deleteCachedEntry(item.id)
        }
        AdblockRepository.saveBundledFilters(selected)
        setEntryUpdating(item.id, false)
        needReload = true
        showReloadPrompt()
        refreshItems()
        if (checked && entryCachedVersion[item.id].isNullOrBlank()) updateOne(item.id)
    }

    private fun loadStoredVersions() {
        val urls = entryDisplayUrl.values.filter { it.isNotBlank() }
        if (urls.isEmpty()) return
        val service = adblockService
        lifecycleScope.launch(Dispatchers.IO) {
            val versions = AdblockRepository.fetchStoredFilterVersions(urls, service)
            withContext(Dispatchers.Main) {
                versions.forEach { (url, version) ->
                    urlToEntryId[url]?.let { entryCachedVersion[it] = version }
                }
                refreshItems()
            }
        }
    }

    private fun updateOne(entryId: String) {
        if (updateJobs.containsKey(entryId) || updateAllJob?.isActive == true) return
        val urls = urlToEntryId.filterValues { it == entryId }.keys.toList()
        if (urls.isEmpty() || entryId !in selected) return
        setEntryUpdating(entryId, true)
        val service = adblockService
        updateJobs[entryId] = lifecycleScope.launch(Dispatchers.IO) {
            val results = AdblockRepository.preCacheFilters(urls, service)
            needReload = true
            withContext(Dispatchers.Main) {
                updateJobs.remove(entryId)
                setEntryUpdating(entryId, false)
                applyUpdateResults(entryId, results)
            }
        }
    }

    private fun deleteCachedEntry(entryId: String) {
        val urls = urlToEntryId.filterValues { it == entryId }.keys.toList()
        if (urls.isEmpty()) return
        val service = adblockService
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { AdblockRepository.deleteCachedFilters(urls, service) }
        }
    }

    private fun updateAll() {
        if (updateAllJob?.isActive == true) return
        val targets = selected.filter { it in entryTitles && it !in updatingEntries }
        val urls = urlToEntryId.filterValues { it in targets }.keys.toList()
        if (urls.isEmpty()) return
        targets.forEach { setEntryUpdating(it, true) }
        val service = adblockService
        updateAllJob = lifecycleScope.launch(Dispatchers.IO) {
            val results = AdblockRepository.preCacheFilters(urls, service)
            needReload = true
            withContext(Dispatchers.Main) {
                targets.forEach { setEntryUpdating(it, false) }
                updateAllJob = null
                results.groupBy { urlToEntryId[it.url] }.forEach { (entryId, entryResults) ->
                    if (entryId != null) applyUpdateResults(entryId, entryResults)
                }
            }
        }
    }

    private fun setEntryUpdating(entryId: String, updating: Boolean) {
        if (updating) updatingEntries.add(entryId) else updatingEntries.remove(entryId)
        refreshItems()
    }

    private fun applyUpdateResults(
        entryId: String,
        results: List<AdblockRepository.FilterUpdateResult>,
    ) {
        val version = results
            .firstOrNull { it.error.isNullOrEmpty() && it.lastModified.isNotBlank() }
            ?.lastModified
            ?: results.firstOrNull { it.error.isNullOrEmpty() && it.lastUpdated.isNotBlank() }
                ?.lastUpdated
        if (!version.isNullOrBlank()) {
            entryCachedVersion[entryId] = version
            refreshItems()
        }
        val title = entryTitles[entryId] ?: entryId
        results.filter { !it.error.isNullOrEmpty() }.forEach {
            Toast.makeText(this, getString(R.string.adblock_filter_update_failed, title), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showReloadPrompt() {
        if (DataStore.serviceState.started) reloadPrompt++
    }

}


class AdblockCustomFiltersActivity : ThemedActivity(), SagerConnection.Callback {
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
    private var filters by mutableStateOf<List<AdblockCustomFilterListItem>>(emptyList())
    private var reloadPrompt by mutableIntStateOf(0)
    private var metadataJob: Job? = null
    private val filterBaseSummary = mutableMapOf<String, String>()
    private val filterTitles = mutableMapOf<String, String>()
    private val filterCachedVersion = mutableMapOf<String, String>()
    private val updateJobs = mutableMapOf<String, Job>()
    private val updatingUrls = mutableSetOf<String>()
    private var updateAllJob: Job? = null

    @Volatile
    private var needReload = false

    private val adblockService: ISagerNetService?
        get() = connection.service

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NekoComposeTheme {
                AdblockCustomFiltersScreen(
                    filters = filters,
                    reloadPrompt = reloadPrompt,
                    onClose = ::finish,
                    onAdd = {
                        startActivity(Intent(this, AdblockCustomFilterActivity::class.java))
                    },
                    onUpdateAll = ::updateAll,
                    onToggle = ::toggleFilter,
                    onUpdate = { item ->
                        AdblockRepository.customFilters().getOrNull(item.index)?.let(::updateOne)
                    },
                    onLongClick = ::showFilterActions,
                    onApplyReload = SagerNet::reloadService,
                )
            }
        }
        connection.connect(this, this)
    }

    override fun onDestroy() {
        metadataJob?.cancel()
        updateJobs.values.forEach { it.cancel() }
        updateJobs.clear()
        updateAllJob?.cancel()
        val pendingReload = needReload
        needReload = false
        val service = adblockService
        connection.disconnect(this)
        super.onDestroy()
        // Flush a batched engine rebuild when the user is done with the screen
        // (back/close, or the app swiped from recents), but not on a plain
        // config-change recreate. Reloads are throttled on the core side.
        if (pendingReload && !isChangingConfigurations && service != null) {
            Thread { runCatching { AdblockRepository.reloadEngine(service) } }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
    }

    override fun onServiceConnected(service: ISagerNetService) {
        loadStoredVersions()
    }

    private fun reload() {
        filterBaseSummary.clear()
        filterTitles.clear()
        filterCachedVersion.clear()
        val storedFilters = AdblockRepository.customFilters()
        storedFilters.forEach { filter ->
            val url = filter.url.trim()
            if (url.isNotBlank()) {
                filterBaseSummary[url] = AdblockRepository.filterDisplaySummary(filter)
                filterTitles[url] = AdblockRepository.filterDisplayTitle(filter)
            }
        }
        refreshRows(storedFilters)
        refreshMetadata(storedFilters)
        loadStoredVersions()
    }

    private fun refreshRows(
        storedFilters: List<AdblockRepository.CustomFilter> = AdblockRepository.customFilters(),
    ) {
        filters = storedFilters.mapIndexed { index, filter ->
            val url = filter.url.trim()
            val baseSummary = AdblockRepository.filterDisplaySummary(filter)
            val version = filterCachedVersion[url].orEmpty()
            AdblockCustomFilterListItem(
                index = index,
                url = url,
                title = AdblockRepository.filterDisplayTitle(filter),
                summary = composeFilterSummary(
                    baseSummary,
                    version.takeIf { it.isNotBlank() }
                        ?.let { getString(R.string.adblock_filter_version, it) }
                        .orEmpty(),
                ),
                enabled = AdblockRepository.customFilterEnabled(filter),
                updating = url in updatingUrls,
            )
        }
    }

    private fun toggleFilter(item: AdblockCustomFilterListItem) {
        val storedFilters = AdblockRepository.customFilters()
        val current = storedFilters.getOrNull(item.index) ?: return
        val enabled = !AdblockRepository.customFilterEnabled(current)
        storedFilters[item.index] = current.copy(enabled = enabled)
        AdblockRepository.saveCustomFilters(storedFilters)
        setUrlUpdating(current.url.trim(), false)
        needReload = true
        showReloadPrompt()
        refreshRows(storedFilters)
        if (enabled && filterCachedVersion[current.url.trim()].isNullOrBlank()) {
            updateOne(current.copy(enabled = true))
        }
    }

    // Keep this lookup batched: concurrent cgo callbacks through gobind can
    // corrupt the Go runtime while the proxy is running.
    private fun loadStoredVersions() {
        val urls = filters.map { it.url }.filter { it.isNotBlank() }
        if (urls.isEmpty()) return
        val service = adblockService
        lifecycleScope.launch(Dispatchers.IO) {
            val versions = AdblockRepository.fetchStoredFilterVersions(urls, service)
            withContext(Dispatchers.Main) {
                filterCachedVersion.putAll(versions)
                refreshRows()
            }
        }
    }

    private fun setVersionSummary(url: String, version: String) {
        if (filters.none { it.url == url }) return
        if (version.isNotBlank()) {
            filterCachedVersion[url] = version
        }
        refreshRows()
    }

    private fun updateOne(filter: AdblockRepository.CustomFilter) {
        val url = filter.url.trim()
        if (url.isBlank() || updateJobs.containsKey(url) || updateAllJob?.isActive == true) return
        if (!AdblockRepository.customFilterEnabled(filter)) return
        val title = filterTitles[url] ?: url
        setUrlUpdating(url, true)
        val service = adblockService
        updateJobs[url] = lifecycleScope.launch(Dispatchers.IO) {
            val result = AdblockRepository.preCacheFilters(listOf(url), service).firstOrNull()
            needReload = true
            withContext(Dispatchers.Main) {
                updateJobs.remove(url)
                setUrlUpdating(url, false)
                if (result == null || !result.error.isNullOrEmpty()) {
                    Toast.makeText(this@AdblockCustomFiltersActivity, getString(R.string.adblock_filter_update_failed, title), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                when {
                    result.lastModified.isNotBlank() -> setVersionSummary(url, result.lastModified)
                    result.lastUpdated.isNotBlank() -> setVersionSummary(url, result.lastUpdated)
                }
            }
        }
    }

    private fun updateAll() {
        if (updateAllJob?.isActive == true) return
        val urls = AdblockRepository.customFilters()
            .filter { AdblockRepository.customFilterEnabled(it) }
            .map { it.url.trim() }
            .filter { it.isNotBlank() && filters.any { row -> row.url == it } && it !in updatingUrls }
        if (urls.isEmpty()) return
        urls.forEach { setUrlUpdating(it, true) }
        val service = adblockService
        updateAllJob = lifecycleScope.launch(Dispatchers.IO) {
            val results = AdblockRepository.preCacheFilters(urls, service)
            needReload = true
            withContext(Dispatchers.Main) {
                urls.forEach { setUrlUpdating(it, false) }
                updateAllJob = null
                for (result in results) {
                    val url = result.url
                    if (!result.error.isNullOrEmpty()) {
                        val title = filterTitles[url] ?: url
                        Toast.makeText(this@AdblockCustomFiltersActivity, getString(R.string.adblock_filter_update_failed, title), Toast.LENGTH_SHORT).show()
                    } else {
                        when {
                            result.lastModified.isNotBlank() -> setVersionSummary(url, result.lastModified)
                            result.lastUpdated.isNotBlank() -> setVersionSummary(url, result.lastUpdated)
                        }
                    }
                }
            }
        }
    }

    private fun setUrlUpdating(url: String, updating: Boolean) {
        if (updating) {
            updatingUrls.add(url)
        } else {
            updatingUrls.remove(url)
        }
        refreshRows()
    }

    private fun showFilterActions(item: AdblockCustomFilterListItem) {
        val filter = AdblockRepository.customFilters().getOrNull(item.index) ?: return
        showComposeItemDialog(
            title = AdblockRepository.filterDisplayTitle(filter),
            items = listOf(getString(R.string.edit), getString(R.string.delete)),
            onItemSelected = { which ->
                when (which) {
                    0 -> startActivity(Intent(this, AdblockCustomFilterActivity::class.java).apply {
                        putExtra(AdblockCustomFilterActivity.EXTRA_INDEX, item.index)
                    })
                    1 -> confirmDelete(item.index)
                }
            },
        )
    }

    private fun refreshMetadata(storedFilters: List<AdblockRepository.CustomFilter>) {
        val urls = storedFilters
            .filter { it.metadataFetched != true }
            .map { it.url.trim() }
            .filter { it.isNotBlank() }
        if (urls.isEmpty() || metadataJob?.isActive == true) return
        val service = adblockService
        metadataJob = lifecycleScope.launch(Dispatchers.IO) {
            val metadata = AdblockRepository.fetchFilterMetadataMap(urls, service)
            val changed = AdblockRepository.saveCustomFilterMetadataMap(metadata)
            withContext(Dispatchers.Main) {
                metadataJob = null
                if (changed) {
                    reload()
                }
            }
        }
    }

    private fun confirmDelete(index: Int) {
        showComposeMessageDialog(
            title = getText(R.string.delete),
            message = getText(R.string.adblock_delete_filter_message),
            positiveButton = getText(R.string.delete),
            negativeButton = getText(android.R.string.cancel),
            onPositive = {
                val storedFilters = AdblockRepository.customFilters()
                if (index in storedFilters.indices) {
                    val url = storedFilters[index].url.trim()
                    val service = adblockService
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { AdblockRepository.deleteCachedFilters(listOf(url), service) }
                    }
                    storedFilters.removeAt(index)
                    AdblockRepository.saveCustomFilters(storedFilters)
                    needReload = true
                    showReloadPrompt()
                    reload()
                }
            },
        )
    }

    private fun showReloadPrompt() {
        if (DataStore.serviceState.started) {
            reloadPrompt++
        }
    }
}

class AdblockCustomFilterActivity : ThemedActivity() {
    companion object {
        const val EXTRA_INDEX = "index"
    }

    private var index = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        val current = AdblockRepository.customFilters().getOrNull(index)
        setContent {
            NekoComposeTheme {
                AdblockCustomFilterEditorScreen(
                    titleRes = if (index >= 0) {
                        R.string.adblock_edit_filter
                    } else {
                        R.string.adblock_add_filter
                    },
                    initialState = AdblockCustomFilterEditorState(
                        url = current?.url.orEmpty(),
                        trust = current?.trust == true,
                        enabled = current?.let(AdblockRepository::customFilterEnabled) != false,
                    ),
                    onClose = ::finish,
                    onSave = ::saveAndFinish,
                )
            }
        }
    }

    private fun saveAndFinish(state: AdblockCustomFilterEditorState) {
        val url = state.url.trim()
        if (url.isBlank()) {
            Toast.makeText(this, R.string.adblock_filter_url_required, Toast.LENGTH_SHORT).show()
            return
        }
        val filters = AdblockRepository.customFilters()
        val existing = filters.getOrNull(index)
        val keepMetadata = existing?.url == url
        val filter = AdblockRepository.CustomFilter(
            url = url,
            trust = state.trust,
            enabled = state.enabled,
            title = existing?.title.orEmpty().takeIf { keepMetadata }.orEmpty(),
            description = existing?.description.orEmpty().takeIf { keepMetadata }.orEmpty(),
            metadataFetched = existing?.metadataFetched?.takeIf { keepMetadata },
        )
        if (index in filters.indices) {
            filters[index] = filter
        } else {
            filters.add(filter)
        }
        AdblockRepository.saveCustomFilters(filters)
        finish()
    }
}

class AdblockCustomRulesActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NekoComposeTheme {
                AdblockCustomRulesEditorScreen(
                    initialRules = DataStore.adblockCustomRules,
                    onClose = ::finish,
                    onSave = {
                        DataStore.adblockCustomRules = it
                        finish()
                    },
                )
            }
        }
    }
}
