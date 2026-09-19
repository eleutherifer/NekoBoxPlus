package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
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
import io.nekohasekai.sagernet.utils.AdblockRepository
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.AppListPreference
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.MaterialSwitchPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import moe.matsuri.nb4a.ui.showMaterialEditTextPreferenceDialog
import moe.matsuri.nb4a.utils.NGUtil
import java.io.File

class AdblockSettingsActivity : ThemedActivity(),
    SagerConnection.Callback {
    private companion object {
        const val CA_HELP_URL = "https://adguard.com/kb/adguard-for-android/solving-problems/manual-certificate/"

        const val DEFAULT_APPS = "routing/browsers.txt"

        const val DEFAULT_CERT_NAME = "nekobox-adblock-ca.crt"
    }

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
    private var fragment: AdblockSettingsFragment? = null
    private val saveCertificate = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-x509-ca-cert")
    ) { uri ->
        if (uri != null) exportCertificate(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_settings_activity)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.adblock)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        fragment = AdblockSettingsFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, fragment!!)
            .commit()
        connection.connect(this, this)
    }

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        fragment?.refreshStats()
    }

    override fun onServiceConnected(service: io.nekohasekai.sagernet.aidl.ISagerNetService) {
        fragment?.refreshStats()
    }

    fun currentAdblockStats(): String = connection.service?.adblockStats()
        ?: Libcore.adblockStatsFromCache(Param.LIBCORE_ADBLOCK_DB_FILE_PATH)

    fun requestCertificateExport() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.adblock_save_ca_certificate)
            .setMessage(R.string.adblock_save_ca_certificate_message)
            .setPositiveButton(R.string.save) { _, _ ->
                saveCertificate.launch(DEFAULT_CERT_NAME)
            }
            .setNeutralButton(R.string.adblock_save_ca_help) { _, _ ->
                launchCustomTab(CA_HELP_URL)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportCertificate(uri: Uri) {
        try {
            val certificate = ensureCertificate()
            contentResolver.openOutputStream(uri)?.use { output ->
                certificate.inputStream().use { input -> input.copyTo(output) }
            } ?: error(getString(R.string.adblock_save_ca_failed))
            Toast.makeText(this, R.string.adblock_save_ca_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: getString(R.string.adblock_save_ca_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun ensureCertificate(): File {
        val caDir = File(noBackupFilesDir, "adblock")
        val certFile = File(caDir, "ca.crt")
        val keyFile = File(caDir, "ca.key")
        Libcore.ensureAdblockCA(certFile.absolutePath, keyFile.absolutePath)
        DataStore.adblockCaCertificate = certFile.absolutePath
        DataStore.adblockCaKey = keyFile.absolutePath
        return certFile
    }

    class AdblockSettingsFragment : PreferenceFragmentCompat() {
        private lateinit var filteringSection: PreferenceCategory
        private lateinit var perAppSection: PreferenceCategory
        private lateinit var filterListsSection: PreferenceCategory
        private lateinit var enableAdblock: MaterialSwitchPreference
        private lateinit var dnsFiltering: MaterialSwitchPreference
        private lateinit var cnameUncloaking: MaterialSwitchPreference
        private lateinit var mixedLanFiltering: MaterialSwitchPreference
        private lateinit var httpsFiltering: MaterialSwitchPreference
        private lateinit var saveCaCertificate: Preference
        private lateinit var httpsFingerprint: SimpleMenuPreference
        private lateinit var httpsCronet: MaterialSwitchPreference
        private lateinit var skipEvCerts: MaterialSwitchPreference
        private lateinit var systemWideFilter: MaterialSwitchPreference
        private lateinit var includedApps: AppListPreference
        private lateinit var bundledFilters: Preference
        private lateinit var customFilters: Preference
        private var statsJob: Job? = null
        private val selectExcludedApps = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            includedApps.postUpdate()
            showReloadPrompt()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.configurationStore
            addPreferencesFromResource(R.xml.adblock_preferences)

            filteringSection = findPreference(Key.ADBLOCK_FILTERING_SECTION)!!
            perAppSection = findPreference(Key.ADBLOCK_PER_APP_SECTION)!!
            filterListsSection = findPreference(Key.ADBLOCK_FILTER_LISTS_SECTION)!!

            filteringSection.isVisible = false
            perAppSection.isVisible = false
            filterListsSection.isVisible = false

            enableAdblock = findPreference(Key.ADBLOCK_ENABLED)!!
            dnsFiltering = findPreference(Key.ADBLOCK_DNS_FILTERING)!!
            cnameUncloaking = findPreference(Key.ADBLOCK_CNAME_UNCLOAKING)!!
            mixedLanFiltering = findPreference(Key.ADBLOCK_MIXED_LAN_FILTERING)!!
            httpsFiltering = findPreference(Key.ADBLOCK_HTTPS_FILTERING)!!
            saveCaCertificate = findPreference("adblockSaveCaCertificate")!!
            httpsFingerprint = findPreference(Key.ADBLOCK_HTTPS_FINGERPRINT)!!
            httpsCronet = findPreference(Key.ADBLOCK_HTTPS_CRONET)!!
            skipEvCerts = findPreference(Key.ADBLOCK_SKIP_EV_CERTS)!!
            systemWideFilter = findPreference(Key.ADBLOCK_SYSTEM_WIDE_FILTER)!!
            includedApps = findPreference(Key.ADBLOCK_INCLUDED_PACKAGES)!!
            bundledFilters = findPreference("adblockBundledFiltersScreen")!!
            customFilters = findPreference("adblockCustomFiltersScreen")!!

            AdblockRepository.ensureBundledDefaults()
            refreshView()

            val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
                showReloadPrompt()
                true
            }

            enableAdblock.setOnPreferenceChangeListener{ _, newValue ->
                val visible = newValue as Boolean
                filteringSection.isVisible = visible
                perAppSection.isVisible = visible
                filterListsSection.isVisible = visible

                if (newValue && DataStore.adblockIncludedPackages.isEmpty()) {
                    val packages = PackageCache.installedPackages.keys
                    DataStore.adblockIncludedPackages = NGUtil
                        .readTextFromAssets(this.requireContext(), DEFAULT_APPS)
                        .lines().filter { it.isNotBlank() && packages.contains(it) }.joinToString("\n")

                    includedApps.postUpdate()
                }

                showReloadPrompt()
                true
            }

            dnsFiltering.setOnPreferenceChangeListener { _, newValue ->
                syncCname(newValue as Boolean)
                showReloadPrompt()
                true
            }
            cnameUncloaking.setOnPreferenceChangeListener(reloadListener)
            mixedLanFiltering.setOnPreferenceChangeListener(reloadListener)
            httpsFiltering.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (enabled) {
                    runCatching {
                        (activity as AdblockSettingsActivity).ensureCertificate()
                    }.onFailure {
                        Toast.makeText(
                            requireContext(),
                            it.message ?: getString(R.string.adblock_ca_generate_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }
                }
                syncHttps(enabled)
                showReloadPrompt()
                true
            }
            skipEvCerts.setOnPreferenceChangeListener(reloadListener)
            httpsFingerprint.setOnPreferenceChangeListener(reloadListener)
            httpsCronet.setOnPreferenceChangeListener { _, newValue ->
                httpsFingerprint.isEnabled = !(newValue as Boolean)
                showReloadPrompt()
                true
            }

            systemWideFilter.setOnPreferenceChangeListener(reloadListener)

            bundledFilters.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), AdblockBundledFiltersActivity::class.java))
                true
            }
            customFilters.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), AdblockCustomFiltersActivity::class.java))
                true
            }
            findPreference<Preference>("adblockCustomRulesScreen")!!.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), AdblockCustomRulesActivity::class.java))
                true
            }
            includedApps.setOnPreferenceClickListener {
                selectExcludedApps.launch(Intent(requireContext(), AppListActivity::class.java).apply {
                    putExtra(AppListActivity.EXTRA_PACKAGE_LIST_KEY, Key.ADBLOCK_INCLUDED_PACKAGES)
                    putExtra(AppListActivity.EXTRA_TITLE, getString(R.string.adblock_included_apps))
                })
                true
            }
            saveCaCertificate.setOnPreferenceClickListener {
                (activity as? AdblockSettingsActivity)?.requestCertificateExport()
                true
            }

            syncCname(DataStore.adblockDnsFiltering)
            syncHttps(DataStore.adblockHttpsFiltering)
            refreshFilterSummaries()
            refreshStats()
        }

        override fun onResume() {
            super.onResume()
            refreshView()
            refreshFilterSummaries()
            refreshStats()
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (showMaterialEditTextPreferenceDialog(preference)) return
            super.onDisplayPreferenceDialog(preference)
        }

        override fun onDestroyView() {
            statsJob?.cancel()
            statsJob = null
            super.onDestroyView()
        }

        fun refreshView() {
            if (
                !::filteringSection.isInitialized ||
                !::perAppSection.isInitialized ||
                !::filterListsSection.isInitialized ||
                !::mixedLanFiltering.isInitialized ||
                !::httpsFingerprint.isInitialized ||
                !::httpsCronet.isInitialized ||
                view == null) return

            filteringSection.isVisible = DataStore.adblockEnabled
            perAppSection.isVisible = DataStore.adblockEnabled
            filterListsSection.isVisible = DataStore.adblockEnabled

            if (DataStore.appendHttpProxy || DataStore.serviceMode == Key.MODE_PROXY) {
                mixedLanFiltering.isEnabled = true
            } else {
                mixedLanFiltering.isEnabled = false
                mixedLanFiltering.isChecked = false
            }

            httpsFingerprint.isEnabled = !DataStore.adblockHttpsCronet
        }

        fun refreshStats() {
            if (!::enableAdblock.isInitialized || view == null) return
            statsJob?.cancel()
            statsJob = viewLifecycleOwner.lifecycleScope.launch {
                val stats = withContext(Dispatchers.IO) {
                    (activity as? AdblockSettingsActivity)?.currentAdblockStats()
                }
                if (!isAdded || !::enableAdblock.isInitialized) return@launch
                enableAdblock.summary = stats?.let { formatStats(it) }
                    ?: getString(R.string.adblock_stats_unavailable)
            }
        }

        private fun formatStats(statsJson: String): String {
            return runCatching {
                val obj = JsonParser.parseString(statsJson).asJsonObject
                val total = obj["total"]?.asLong ?: 0L
                val blocked = obj["blocked"]?.asLong ?: 0L
                if (total == 0L) {
                    getString(R.string.adblock_stats_empty)
                } else {
                    getString(R.string.adblock_stats, blocked, total)
                }
            }.getOrDefault(getString(R.string.adblock_stats_unavailable))
        }

        private fun refreshFilterSummaries() {
            if (!::bundledFilters.isInitialized || !::customFilters.isInitialized) return
            val selected = AdblockRepository.ensureBundledDefaults()
            bundledFilters.summary = AdblockRepository.catalog
                .filter { it.id in selected }
                .joinToString { it.title }
                .ifBlank { getString(R.string.filter_disabled) }
            customFilters.summary = AdblockRepository.customFilters()
                .filter { AdblockRepository.customFilterEnabled(it) }
                .joinToString { AdblockRepository.filterDisplayTitle(it) }
                .ifBlank { getString(R.string.adblock_custom_filters_empty) }
        }

        private fun syncCname(dnsEnabled: Boolean) {
            cnameUncloaking.isEnabled = dnsEnabled
            cnameUncloaking.isChecked = dnsEnabled && DataStore.adblockCnameUncloaking
        }

        private fun syncHttps(httpsEnabled: Boolean) {
            saveCaCertificate.isEnabled = httpsEnabled
            httpsFingerprint.isEnabled = httpsEnabled
            skipEvCerts.isEnabled = httpsEnabled
        }

        private fun showReloadPrompt() {
            if (DataStore.serviceState.started && view != null) {
                Snackbar.make(requireView(), R.string.need_reload, Snackbar.LENGTH_LONG)
                    .setAction(R.string.apply) { SagerNet.reloadService() }
                    .show()
            }
        }
    }
}

private fun composeFilterSummary(base: String, versionLine: String): String = when {
    versionLine.isBlank() -> base
    base.isBlank() -> versionLine
    else -> "$base\n$versionLine"
}

private fun ImageButton.setFilterUpdateState(enabled: Boolean, running: Boolean) {
    isEnabled = enabled && !running
    alpha = if (enabled) 1F else 0.38F
    if (!running) {
        clearAnimation()
        rotation = 0F
        return
    }
    if (animation != null) return
    startAnimation(RotateAnimation(
        0F,
        360F,
        RotateAnimation.RELATIVE_TO_SELF,
        0.5F,
        RotateAnimation.RELATIVE_TO_SELF,
        0.5F,
    ).apply {
        duration = 800L
        interpolator = LinearInterpolator()
        repeatCount = RotateAnimation.INFINITE
    })
}

private class AdblockFilterPreference(
    context: Context,
    private val bindWidget: (PreferenceViewHolder) -> Unit,
) : CheckBoxPreference(context) {
    init {
        isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        bindWidget(holder)
    }

    fun refreshWidget() {
        notifyChanged()
    }
}

class AdblockBundledFiltersActivity : ThemedActivity(), SagerConnection.Callback {
    private lateinit var fragment: BundledFiltersFragment
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)

    val adblockService: ISagerNetService?
        get() = connection.service

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_settings_activity)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.adblock_bundled_filters)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        fragment = BundledFiltersFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, fragment)
            .commit()
        connection.connect(this, this)
    }

    override fun onDestroy() {
        val pendingReload = ::fragment.isInitialized && fragment.consumePendingReload()
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.adblock_bundled_filters_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_update_all) {
            fragment.updateAll()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
    }

    override fun onServiceConnected(service: ISagerNetService) {
        fragment.reloadStoredVersions()
    }

    class BundledFiltersFragment : PreferenceFragmentCompat() {
        private val entryPrefs = mutableMapOf<String, AdblockFilterPreference>()
        private val entryBaseSummary = mutableMapOf<String, String>()
        private val entryTitles = mutableMapOf<String, String>()
        private val entryDisplayUrl = mutableMapOf<String, String>()
        private val entryCachedVersion = mutableMapOf<String, String>()
        private val urlToEntryId = mutableMapOf<String, String>()
        private val updateJobs = mutableMapOf<String, Job>()
        private val updatingEntries = mutableSetOf<String>()
        private var updateAllJob: Job? = null
        private var selected = mutableSetOf<String>()

        // Lazily-batched engine reload: set when the user toggles a filter or
        // refreshes its content, and flushed once when the activity is left.
        @Volatile
        private var needReload = false

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.configurationStore
            entryPrefs.clear()
            entryBaseSummary.clear()
            entryTitles.clear()
            entryDisplayUrl.clear()
            entryCachedVersion.clear()
            urlToEntryId.clear()
            val screen = preferenceManager.createPreferenceScreen(requireContext())
            preferenceScreen = screen
            selected = AdblockRepository.ensureBundledDefaults().toMutableSet()
            for ((category, entries) in AdblockRepository.groupedCatalog()) {
                screen.addPreference(PreferenceCategory(requireContext()).apply {
                    title = AdblockRepository.categoryTitle(category)
                    isIconSpaceReserved = false
                })
                for (entry in entries) {
                    val entryId = entry.id
                    val displayUrl = entry.sources
                        .firstOrNull { it.url.isNotBlank() }?.url?.trim().orEmpty()
                    entryBaseSummary[entryId] = entry.desc
                    entryTitles[entryId] = entry.title
                    entryDisplayUrl[entryId] = displayUrl
                    entry.sources.forEach { source ->
                        val url = source.url.trim()
                        if (url.isNotBlank()) urlToEntryId[url] = entryId
                    }
                    val preference = AdblockFilterPreference(requireContext()) { holder ->
                        val updateButton = holder.findViewById(R.id.btn_filter_update) as? ImageButton
                        updateButton?.setFilterUpdateState(
                            enabled = entryId in selected,
                            running = entryId in updatingEntries,
                        )
                        updateButton?.setOnClickListener {
                            updateOne(entryId)
                        }
                    }.apply {
                        widgetLayoutResource = R.layout.widget_adblock_filter_update
                        key = "adblockBundledFilter.$entryId"
                        title = entry.title
                        summary = entry.desc
                        isPersistent = false
                        setDefaultValue(entryId in selected)
                        setOnPreferenceChangeListener { _, newValue ->
                            val checked = newValue as Boolean
                            if (checked) {
                                selected.add(entryId)
                            } else {
                                selected.remove(entryId)
                                clearStoredVersion(entryId)
                                deleteCachedEntry(entryId)
                            }
                            AdblockRepository.saveBundledFilters(selected)
                            setEntryUpdating(entryId, false)
                            needReload = true
                            showReloadPrompt()
                            if (checked && entryCachedVersion[entryId].isNullOrBlank()) {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    updateOne(entryId)
                                }
                            }
                            true
                        }
                    }
                    screen.addPreference(preference)
                    preference.isChecked = entryId in selected
                    entryPrefs[entryId] = preference
                }
            }
            loadStoredVersions()
        }

        override fun onDestroyView() {
            updateJobs.values.forEach { it.cancel() }
            updateJobs.clear()
            updatingEntries.clear()
            updateAllJob?.cancel()
            updateAllJob = null
            super.onDestroyView()
        }

        fun reloadStoredVersions() {
            if (!isAdded) return
            loadStoredVersions()
        }

        fun consumePendingReload(): Boolean {
            val pending = needReload
            needReload = false
            return pending
        }

        private fun service(): ISagerNetService? {
            return (activity as? AdblockBundledFiltersActivity)?.adblockService
        }

        // Batched version lookup: one gobind/binder call for all entries instead of
        // one per filter. See libcore/adblock.go AdblockStoredFilterVersions for why
        // fanning out concurrent cgo callbacks while the proxy is running corrupts
        // the Go runtime write barrier.
        private fun loadStoredVersions() {
            val urls = entryDisplayUrl.values.filter { it.isNotBlank() }
            if (urls.isEmpty()) return
            val service = service()
            lifecycleScope.launch(Dispatchers.IO) {
                val versions = AdblockRepository.fetchStoredFilterVersions(urls, service)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    applyStoredVersions(versions)
                }
            }
        }

        private fun applyStoredVersions(versions: Map<String, String>) {
            versions.forEach { (url, version) ->
                val entryId = urlToEntryId[url] ?: return@forEach
                entryCachedVersion[entryId] = version
                val versionLine = version.takeIf { it.isNotBlank() }
                    ?.let { getString(R.string.adblock_filter_version, it) }.orEmpty()
                entryPrefs[entryId]?.summary = composeFilterSummary(entryBaseSummary[entryId].orEmpty(), versionLine)
            }
        }

        private fun updateOne(entryId: String) {
            if (updateJobs.containsKey(entryId) || updateAllJob?.isActive == true) return
            val urls = urlToEntryId.entries.filter { it.value == entryId }.map { it.key }
            if (urls.isEmpty()) return
            if (entryId !in selected) return
            setEntryUpdating(entryId, true)
            val service = service()
            updateJobs[entryId] = lifecycleScope.launch(Dispatchers.IO) {
                val results = AdblockRepository.preCacheFilters(urls, service)
                needReload = true
                withContext(Dispatchers.Main) {
                    updateJobs.remove(entryId)
                    setEntryUpdating(entryId, false)
                    if (!isAdded) return@withContext
                    applyUpdateResults(entryId, results)
                }
            }
        }

        private fun deleteCachedEntry(entryId: String) {
            val urls = urlToEntryId.entries.filter { it.value == entryId }.map { it.key }
            if (urls.isEmpty()) return
            val service = service()
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { AdblockRepository.deleteCachedFilters(urls, service) }
            }
        }

        private fun clearStoredVersion(entryId: String) {
            entryCachedVersion[entryId] = ""
            entryPrefs[entryId]?.summary = entryBaseSummary[entryId].orEmpty()
        }

        fun updateAll() {
            if (updateAllJob?.isActive == true) return
            val targetEntries = selected.filter { entryId ->
                entryPrefs.containsKey(entryId) && entryId !in updatingEntries
            }
            val allUrls = urlToEntryId.entries
                .filter { it.value in targetEntries }
                .map { it.key }
            if (allUrls.isEmpty()) return
            targetEntries.forEach { setEntryUpdating(it, true) }
            val service = service()
            updateAllJob = lifecycleScope.launch(Dispatchers.IO) {
                val results = AdblockRepository.preCacheFilters(allUrls, service)
                needReload = true
                withContext(Dispatchers.Main) {
                    targetEntries.forEach { setEntryUpdating(it, false) }
                    updateAllJob = null
                    if (!isAdded) return@withContext
                    for ((entryId, entryResults) in results.groupBy { urlToEntryId[it.url] }) {
                        if (entryId != null) applyUpdateResults(entryId, entryResults)
                    }
                }
            }
        }

        private fun setEntryUpdating(entryId: String, updating: Boolean) {
            if (updating) {
                updatingEntries.add(entryId)
            } else {
                updatingEntries.remove(entryId)
            }
            entryPrefs[entryId]?.refreshWidget()
        }

        private fun applyUpdateResults(entryId: String, results: List<AdblockRepository.FilterUpdateResult>) {
            val title = entryTitles[entryId] ?: entryId
            val successfulVersion = results
                .firstOrNull { it.error.isNullOrEmpty() && it.lastModified.isNotBlank() }
                ?.lastModified
                ?: results
                    .firstOrNull { it.error.isNullOrEmpty() && it.lastUpdated.isNotBlank() }
                    ?.lastUpdated
            if (!successfulVersion.isNullOrBlank()) {
                val versionLine = getString(R.string.adblock_filter_version, successfulVersion)
                entryCachedVersion[entryId] = successfulVersion
                entryPrefs[entryId]?.summary = composeFilterSummary(entryBaseSummary[entryId].orEmpty(), versionLine)
            }
            results.filter { !it.error.isNullOrEmpty() }.forEach {
                Toast.makeText(requireContext(), getString(R.string.adblock_filter_update_failed, title), Toast.LENGTH_SHORT).show()
            }
        }

        private fun showReloadPrompt() {
            if (DataStore.serviceState.started && view != null) {
                Snackbar.make(requireView(), R.string.need_reload, Snackbar.LENGTH_LONG)
                    .setAction(R.string.apply) { SagerNet.reloadService() }
                    .show()
            }
        }
    }
}


class AdblockCustomFiltersActivity : ThemedActivity(), SagerConnection.Callback {
    private lateinit var fragment: CustomFiltersFragment
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)

    val adblockService: ISagerNetService?
        get() = connection.service

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_settings_activity)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.adblock_custom_filters)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        fragment = CustomFiltersFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, fragment)
            .commit()
        connection.connect(this, this)
    }

    override fun onDestroy() {
        val pendingReload = ::fragment.isInitialized && fragment.consumePendingReload()
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.adblock_custom_filters_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_add) {
            startActivity(Intent(this, AdblockCustomFilterActivity::class.java))
            return true
        }
        if (item.itemId == R.id.action_update_all) {
            fragment.updateAll()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (::fragment.isInitialized) fragment.reload()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
    }

    override fun onServiceConnected(service: ISagerNetService) {
        fragment.reloadStoredVersions()
    }

    class CustomFiltersFragment : PreferenceFragmentCompat() {
        private var metadataJob: Job? = null
        private val filterPrefs = mutableMapOf<String, AdblockFilterPreference>()
        private val filterBaseSummary = mutableMapOf<String, String>()
        private val filterTitles = mutableMapOf<String, String>()
        private val filterCachedVersion = mutableMapOf<String, String>()
        private val updateJobs = mutableMapOf<String, Job>()
        private val updatingUrls = mutableSetOf<String>()
        private var updateAllJob: Job? = null

        // Lazily-batched engine reload: set when the user toggles a filter or
        // refreshes its content, and flushed once when the activity is left.
        @Volatile
        private var needReload = false

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            reload()
        }

        override fun onDestroyView() {
            metadataJob?.cancel()
            metadataJob = null
            updateJobs.values.forEach { it.cancel() }
            updateJobs.clear()
            updatingUrls.clear()
            updateAllJob?.cancel()
            updateAllJob = null
            super.onDestroyView()
        }

        fun reload() {
            val screen = preferenceManager.createPreferenceScreen(requireContext())
            preferenceScreen = screen
            filterPrefs.clear()
            filterBaseSummary.clear()
            filterTitles.clear()
            filterCachedVersion.clear()
            val filters = AdblockRepository.customFilters()
            if (filters.isEmpty()) {
                screen.addPreference(Preference(requireContext()).apply {
                    title = getString(R.string.adblock_custom_filters_empty)
                    isSelectable = false
                    icon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_baseline_filter_list_24)
                })
                return
            }
            filters.forEachIndexed { index, filter ->
                val url = filter.url.trim()
                val title = AdblockRepository.filterDisplayTitle(filter)
                val baseSummary = AdblockRepository.filterDisplaySummary(filter)
                val preference = AdblockFilterPreference(requireContext()) { holder ->
                    holder.itemView.setOnLongClickListener {
                        showFilterActions(index, filter)
                        true
                    }
                    val updateButton = holder.findViewById(R.id.btn_filter_update) as? ImageButton
                    updateButton?.setFilterUpdateState(
                        enabled = AdblockRepository.customFilterEnabled(filter),
                        running = url in updatingUrls,
                    )
                    updateButton?.setOnClickListener {
                        updateOne(filter)
                    }
                }.apply {
                    widgetLayoutResource = R.layout.widget_adblock_filter_update
                    key = "adblockCustomFilter.$index.${filter.url.hashCode()}"
                    this.title = title
                    summary = baseSummary
                    isPersistent = false
                    setDefaultValue(AdblockRepository.customFilterEnabled(filter))
                    setOnPreferenceChangeListener { _, newValue ->
                        val updatedFilters = AdblockRepository.customFilters()
                        val current = updatedFilters.getOrNull(index) ?: return@setOnPreferenceChangeListener false
                        val enabled = newValue as Boolean
                        updatedFilters[index] = current.copy(enabled = enabled)
                        AdblockRepository.saveCustomFilters(updatedFilters)
                        setUrlUpdating(current.url.trim(), false)
                        needReload = true
                        showReloadPrompt()
                        if (enabled && filterCachedVersion[current.url.trim()].isNullOrBlank()) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                updateOne(current.copy(enabled = true))
                            }
                        }
                        true
                    }
                }
                screen.addPreference(preference)
                preference.isChecked = AdblockRepository.customFilterEnabled(filter)
                if (url.isNotBlank()) {
                    filterPrefs[url] = preference
                    filterBaseSummary[url] = baseSummary
                    filterTitles[url] = title
                }
            }
            refreshMetadata(filters)
            loadStoredVersions()
        }

        fun reloadStoredVersions() {
            if (!isAdded) return
            loadStoredVersions()
        }

        fun consumePendingReload(): Boolean {
            val pending = needReload
            needReload = false
            return pending
        }

        private fun service(): ISagerNetService? {
            return (activity as? AdblockCustomFiltersActivity)?.adblockService
        }

        // Batched version lookup: one gobind/binder call for all custom filters
        // instead of one per filter. See libcore/adblock.go
        // AdblockStoredFilterVersionsForInstance for why fanning out concurrent
        // cgo callbacks while the proxy is running corrupts the Go runtime.
        private fun loadStoredVersions() {
            val urls = filterPrefs.keys.toList()
            if (urls.isEmpty()) return
            val service = service()
            lifecycleScope.launch(Dispatchers.IO) {
                val versions = AdblockRepository.fetchStoredFilterVersions(urls, service)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    applyStoredVersions(versions)
                }
            }
        }

        private fun applyStoredVersions(versions: Map<String, String>) {
            versions.forEach { (url, version) ->
                filterCachedVersion[url] = version
                if (version.isNotBlank()) {
                    setVersionSummary(url, version)
                } else {
                    filterPrefs[url]?.summary = filterBaseSummary[url].orEmpty()
                }
            }
        }

        private fun setVersionSummary(url: String, version: String) {
            val pref = filterPrefs[url] ?: return
            filterCachedVersion[url] = version
            val versionLine = getString(R.string.adblock_filter_version, version)
            pref.summary = composeFilterSummary(filterBaseSummary[url].orEmpty(), versionLine)
        }

        private fun updateOne(filter: AdblockRepository.CustomFilter) {
            val url = filter.url.trim()
            if (url.isBlank() || updateJobs.containsKey(url) || updateAllJob?.isActive == true) return
            if (!AdblockRepository.customFilterEnabled(filter)) return
            val title = filterTitles[url] ?: url
            setUrlUpdating(url, true)
            val service = service()
            updateJobs[url] = lifecycleScope.launch(Dispatchers.IO) {
                val result = AdblockRepository.preCacheFilters(listOf(url), service).firstOrNull()
                needReload = true
                withContext(Dispatchers.Main) {
                    updateJobs.remove(url)
                    setUrlUpdating(url, false)
                    if (!isAdded) return@withContext
                    if (result == null || !result.error.isNullOrEmpty()) {
                        Toast.makeText(requireContext(), getString(R.string.adblock_filter_update_failed, title), Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    when {
                        result.lastModified.isNotBlank() -> setVersionSummary(url, result.lastModified)
                        result.lastUpdated.isNotBlank() -> setVersionSummary(url, result.lastUpdated)
                    }
                }
            }
        }

        fun updateAll() {
            if (updateAllJob?.isActive == true) return
            val urls = AdblockRepository.customFilters()
                .filter { AdblockRepository.customFilterEnabled(it) }
                .map { it.url.trim() }
                .filter { it.isNotBlank() && filterPrefs.containsKey(it) && it !in updatingUrls }
            if (urls.isEmpty()) return
            urls.forEach { setUrlUpdating(it, true) }
            val service = service()
            updateAllJob = lifecycleScope.launch(Dispatchers.IO) {
                val results = AdblockRepository.preCacheFilters(urls, service)
                needReload = true
                withContext(Dispatchers.Main) {
                    urls.forEach { setUrlUpdating(it, false) }
                    updateAllJob = null
                    if (!isAdded) return@withContext
                    for (result in results) {
                        val url = result.url
                        if (!result.error.isNullOrEmpty()) {
                            val title = filterTitles[url] ?: url
                            Toast.makeText(requireContext(), getString(R.string.adblock_filter_update_failed, title), Toast.LENGTH_SHORT).show()
                        } else {
                            if (result.lastModified.isNotBlank()) {
                                setVersionSummary(url, result.lastModified)
                            } else if (result.lastUpdated.isNotBlank()) {
                                setVersionSummary(url, result.lastUpdated)
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
            filterPrefs[url]?.refreshWidget()
        }

        private fun showFilterActions(index: Int, filter: AdblockRepository.CustomFilter) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(AdblockRepository.filterDisplayTitle(filter))
                .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                    when (which) {
                        0 -> startActivity(Intent(requireContext(), AdblockCustomFilterActivity::class.java).apply {
                            putExtra(AdblockCustomFilterActivity.EXTRA_INDEX, index)
                        })
                        1 -> confirmDelete(index)
                    }
                }
                .show()
        }

        private fun refreshMetadata(filters: List<AdblockRepository.CustomFilter>) {
            val urls = filters
                .filter { it.metadataFetched != true }
                .map { it.url.trim() }
                .filter { it.isNotBlank() }
            if (urls.isEmpty() || metadataJob?.isActive == true) return
            val service = service()
            metadataJob = lifecycleScope.launch(Dispatchers.IO) {
                val metadata = AdblockRepository.fetchFilterMetadataMap(urls, service)
                val changed = AdblockRepository.saveCustomFilterMetadataMap(metadata)
                withContext(Dispatchers.Main) {
                    metadataJob = null
                    if (changed && isAdded) {
                        reload()
                    }
                }
            }
        }

        private fun confirmDelete(index: Int) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete)
                .setMessage(R.string.adblock_delete_filter_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    val filters = AdblockRepository.customFilters()
                    if (index in filters.indices) {
                        val url = filters[index].url.trim()
                        val service = service()
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { AdblockRepository.deleteCachedFilters(listOf(url), service) }
                        }
                        filters.removeAt(index)
                        AdblockRepository.saveCustomFilters(filters)
                        showReloadPrompt()
                        reload()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun showReloadPrompt() {
            if (DataStore.serviceState.started && view != null) {
                Snackbar.make(requireView(), R.string.need_reload, Snackbar.LENGTH_LONG)
                    .setAction(R.string.apply) { SagerNet.reloadService() }
                    .show()
            }
        }
    }
}

class AdblockCustomFilterActivity : ThemedActivity() {
    companion object {
        const val EXTRA_INDEX = "index"
    }

    private var index = -1
    private lateinit var fragment: CustomFilterEditFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        setContentView(R.layout.layout_settings_activity)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(if (index >= 0) R.string.adblock_edit_filter else R.string.adblock_add_filter)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        fragment = if (savedInstanceState == null) {
            CustomFilterEditFragment().also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.settings, it)
                    .commit()
            }
        } else {
            supportFragmentManager.findFragmentById(R.id.settings) as CustomFilterEditFragment
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, android.R.id.button1, 0, R.string.save)
            .setIcon(R.drawable.ic_baseline_save_24)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.button1) {
            fragment.saveAndFinish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class CustomFilterEditFragment : Fragment() {
        private lateinit var urlEdit: EditText
        private lateinit var trustCheck: CheckBox
        private lateinit var enabledCheck: CheckBox
        private val index: Int
            get() = requireActivity().intent.getIntExtra(EXTRA_INDEX, -1)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val current = AdblockRepository.customFilters().getOrNull(index)
            val padding = (16 * resources.displayMetrics.density).toInt()
            return LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding)
                urlEdit = EditText(requireContext()).apply {
                    hint = getString(R.string.adblock_filter_url)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setSingleLine()
                    setText(current?.url.orEmpty())
                    setSelection(text.length)
                }
                addView(
                    urlEdit,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                trustCheck = CheckBox(requireContext()).apply {
                    text = getString(R.string.adblock_enable_insecure_rules)
                    isChecked = current?.trust == true
                }
                addView(
                    trustCheck,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                enabledCheck = CheckBox(requireContext()).apply {
                    text = getString(R.string.adblock_enable_this_filter_on_add)
                    isChecked = current?.let { AdblockRepository.customFilterEnabled(it) } != false
                }
                addView(
                    enabledCheck,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }

        fun saveAndFinish() {
            val url = urlEdit.text?.toString()?.trim().orEmpty()
            if (url.isBlank()) {
                Toast.makeText(requireContext(), R.string.adblock_filter_url_required, Toast.LENGTH_SHORT).show()
                return
            }
            val filters = AdblockRepository.customFilters()
            val existing = filters.getOrNull(index)
            val keepMetadata = existing?.url == url
            val filter = AdblockRepository.CustomFilter(
                url = url,
                trust = trustCheck.isChecked,
                enabled = enabledCheck.isChecked,
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
            requireActivity().finish()
        }
    }
}

class AdblockCustomRulesActivity : ThemedActivity() {
    private lateinit var fragment: CustomRulesEditFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_settings_activity)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.adblock_custom_rules)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        fragment = if (savedInstanceState == null) {
            CustomRulesEditFragment().also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.settings, it)
                    .commit()
            }
        } else {
            supportFragmentManager.findFragmentById(R.id.settings) as CustomRulesEditFragment
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, android.R.id.button1, 0, R.string.save)
            .setIcon(R.drawable.ic_baseline_save_24)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.button1) {
            fragment.saveAndFinish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class CustomRulesEditFragment : Fragment() {
        private lateinit var editText: EditText

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val padding = (16 * resources.displayMetrics.density).toInt()
            return EditText(requireContext()).apply {
                editText = this
                setPadding(padding)
                setText(DataStore.adblockCustomRules)
                setSelection(text.length)
                typeface = ResourcesCompat.getFont(requireContext(), R.font.jetbrains_mono)
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 12
            }
        }

        fun saveAndFinish() {
            DataStore.adblockCustomRules = editText.text?.toString().orEmpty()
            requireActivity().finish()
        }
    }
}
