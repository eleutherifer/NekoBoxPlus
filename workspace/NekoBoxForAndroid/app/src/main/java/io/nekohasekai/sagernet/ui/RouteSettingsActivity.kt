package io.nekohasekai.sagernet.ui

import android.Manifest
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.core.view.ViewCompat
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.CustomDnsServerStore
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleType
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.AppListPreference
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.OutboundPreference
import io.nekohasekai.sagernet.widget.RouteEditTextPreferenceDialogFragment
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.EditConfigPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import moe.matsuri.nb4a.ui.showMaterialEditTextPreferenceDialog

@Suppress("UNCHECKED_CAST")
class RouteSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_settings_activity,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    companion object {
        const val EXTRA_ROUTE_ID = "id"
        const val EXTRA_PACKAGE_NAME = "pkg"
        const val EXTRA_ROUTE_TYPE = "type"
        private const val WIFI_LOCATION_PERMISSION_REQUEST_CODE = 1001

        private const val ROUTE_PROTOCOL_SELECTOR = "routeProtocolSelector"
        private const val ROUTE_PROTOCOL_CUSTOM = "__custom__"
        private const val DNS_SERVER_CUSTOM = "__custom__"
        private const val DNS_SERVER_BLOCK = "__block__"
    }

    private val routeProtocolValues by lazy {
        resources.getStringArray(R.array.route_sniff_protocol_value)
    }
    private val routeProtocolOfficialValues by lazy {
        routeProtocolValues.filterNot { it == ROUTE_PROTOCOL_CUSTOM }.toSet()
    }

    fun init(packageName: String?) {
        RuleEntity().apply {
            type = RuleType.fromValue(intent.getStringExtra(EXTRA_ROUTE_TYPE)).value
            if (!packageName.isNullOrBlank()) {
                packages = setOf(packageName)
                name = app.getString(R.string.route_for, PackageCache.loadLabel(packageName))
            }
        }.init()
    }

    fun RuleEntity.init() {
        DataStore.routeDnsAction = dnsAction
        DataStore.routeDnsServer = dnsServer
        DataStore.routeDnsStrategy = dnsStrategy
        DataStore.routeDnsDisableCache = dnsDisableCache
        DataStore.routeDnsRewriteTtl = dnsRewriteTtl
        DataStore.routeDnsClientSubnet = dnsClientSubnet
        DataStore.routeDnsRcode = dnsRcode
        DataStore.routeDnsRejectMethod = dnsRejectMethod
        DataStore.routeDnsPredefinedAnswer = dnsPredefinedAnswer
        DataStore.routeDnsPredefinedNs = dnsPredefinedNs
        DataStore.routeDnsPredefinedExtra = dnsPredefinedExtra
        DataStore.routeName = name
        DataStore.serverConfig = config
        DataStore.routeDomain = domains
        DataStore.routeIP = ip
        DataStore.routePort = port
        DataStore.routeSourcePort = sourcePort
        DataStore.routeNetworkType = networkType
        DataStore.routeWifiSsid = wifiSsid
        DataStore.routeWifiBssid = wifiBssid
        DataStore.routeNetwork = network
        DataStore.routeSource = source
        DataStore.routeProtocol = protocol
        DataStore.routeRuleset = ruleset
        DataStore.routeClashMode = clashMode
        DataStore.routeCreateDnsRule = if (createDnsRule) 1 else 0
        DataStore.routeOutboundRule = outbound
        DataStore.routeOutbound = when (outbound) {
            0L -> 0
            -1L -> 1
            -2L -> 2
            else -> 3
        }
        DataStore.routePackages = packages.joinToString("\n")
    }

    fun RuleEntity.serialize() {
        type = if (DataStore.editingId == 0L) {
            RuleType.fromValue(intent.getStringExtra(EXTRA_ROUTE_TYPE)).value
        } else {
            type
        }
        dnsAction = DataStore.routeDnsAction.ifBlank { "route" }
        dnsServer = when (DataStore.routeDnsServer) {
            DNS_SERVER_CUSTOM, DNS_SERVER_BLOCK -> "dns-remote"
            else -> DataStore.routeDnsServer
        }
        dnsStrategy = DataStore.routeDnsStrategy
        dnsDisableCache = DataStore.routeDnsDisableCache
        dnsRewriteTtl = DataStore.routeDnsRewriteTtl
        dnsClientSubnet = DataStore.routeDnsClientSubnet
        dnsRcode = DataStore.routeDnsRcode.ifBlank { "NOERROR" }
        dnsRejectMethod = DataStore.routeDnsRejectMethod
        dnsPredefinedAnswer = DataStore.routeDnsPredefinedAnswer
        dnsPredefinedNs = DataStore.routeDnsPredefinedNs
        dnsPredefinedExtra = DataStore.routeDnsPredefinedExtra
        name = DataStore.routeName
        config = DataStore.serverConfig
        domains = DataStore.routeDomain
        ip = DataStore.routeIP
        port = DataStore.routePort
        sourcePort = DataStore.routeSourcePort
        networkType = DataStore.routeNetworkType
        wifiSsid = RuleEntity.normalizeWifiSsid(DataStore.routeWifiSsid)
        wifiBssid = RuleEntity.normalizeWifiBssid(DataStore.routeWifiBssid)
        network = DataStore.routeNetwork
        source = DataStore.routeSource
        protocol = DataStore.routeProtocol
        ruleset = DataStore.routeRuleset
        clashMode = DataStore.routeClashMode
        createDnsRule = DataStore.routeCreateDnsRule != 0
        outbound = when (DataStore.routeOutbound) {
            0 -> 0L
            1 -> -1L
            2 -> -2L
            else -> DataStore.routeOutboundRule
        }
        packages = DataStore.routePackages.split("\n").filter { it.isNotBlank() }.toSet()

        if (DataStore.editingId == 0L) {
            enabled = true
        }
    }

    private lateinit var editConfigPreference: EditConfigPreference
    private lateinit var routeProtocolPreference: SimpleMenuPreference
    private lateinit var routeCreateDnsRulePreference: SimpleMenuPreference
    private lateinit var routeWifiSsidPreference: EditTextPreference
    private lateinit var routeWifiBssidPreference: EditTextPreference
    private lateinit var routeNetworkTypePreference: MultiSelectListPreference
    private lateinit var routeDnsActionPreference: SimpleMenuPreference
    private lateinit var routeDnsServerPreference: SimpleMenuPreference
    private lateinit var routeDnsStrategyPreference: SimpleMenuPreference
    private lateinit var routeDnsDisableCachePreference: Preference
    private lateinit var routeDnsRewriteTtlPreference: Preference
    private lateinit var routeDnsClientSubnetPreference: Preference
    private lateinit var routeDnsRcodePreference: SimpleMenuPreference
    private lateinit var routeDnsRejectMethodPreference: Preference
    private lateinit var routeDnsPredefinedAnswerPreference: Preference
    private lateinit var routeDnsPredefinedNsPreference: Preference
    private lateinit var routeDnsPredefinedExtraPreference: Preference
    private var pendingWifiPermissionSave = false
    private var pendingWifiBackgroundPermissionSave = false
    private var resetDirtyWhenEditorReady = false
    private var preferenceListenerRegistered = false

    private val requestBackgroundLocationSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!pendingWifiBackgroundPermissionSave) {
            return@registerForActivityResult
        }
        pendingWifiBackgroundPermissionSave = false
        runOnDefaultDispatcher {
            persistAndExit()
        }
    }

    fun needSave(): Boolean {
        return DataStore.dirty
    }

    private fun routeProtocolSelectorValue(protocol: String): String {
        return when {
            protocol in routeProtocolOfficialValues -> protocol
            protocol.isNotBlank() -> ROUTE_PROTOCOL_CUSTOM
            else -> ""
        }
    }

    private fun showRouteProtocolCustomDialog() {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(DataStore.routeProtocol)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding)
            addView(
                editText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.protocol)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataStore.routeProtocol = editText.text?.toString().orEmpty()
                routeProtocolPreference.value = routeProtocolSelectorValue(DataStore.routeProtocol)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                routeProtocolPreference.value = routeProtocolSelectorValue(DataStore.routeProtocol)
            }
            .show()
    }

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.route_preferences)

        editConfigPreference = findPreference(Key.SERVER_CONFIG)!!
        routeNetworkTypePreference = findPreference(Key.ROUTE_NETWORK_TYPE)!!
        routeNetworkTypePreference.summaryProvider = MultiSelectSummaryProvider
        routeWifiSsidPreference = findPreference(Key.ROUTE_WIFI_SSID)!!
        routeWifiBssidPreference = findPreference(Key.ROUTE_WIFI_BSSID)!!
        routeProtocolPreference = findPreference(ROUTE_PROTOCOL_SELECTOR)!!
        routeProtocolPreference.value = routeProtocolSelectorValue(DataStore.routeProtocol)
        routeProtocolPreference.summaryProvider = RouteProtocolSummaryProvider
        routeCreateDnsRulePreference = findPreference(Key.ROUTE_CREATE_DNS_RULE)!!
        routeCreateDnsRulePreference.value = DataStore.routeCreateDnsRule.toString()
        routeDnsActionPreference = findPreference(Key.ROUTE_DNS_ACTION)!!
        routeDnsServerPreference = findPreference(Key.ROUTE_DNS_SERVER)!!
        routeDnsStrategyPreference = findPreference(Key.ROUTE_DNS_STRATEGY)!!
        routeDnsDisableCachePreference = findPreference(Key.ROUTE_DNS_DISABLE_CACHE)!!
        routeDnsRewriteTtlPreference = findPreference(Key.ROUTE_DNS_REWRITE_TTL)!!
        routeDnsClientSubnetPreference = findPreference(Key.ROUTE_DNS_CLIENT_SUBNET)!!
        routeDnsRcodePreference = findPreference(Key.ROUTE_DNS_RCODE)!!
        routeDnsRejectMethodPreference = findPreference(Key.ROUTE_DNS_REJECT_METHOD)!!
        routeDnsPredefinedAnswerPreference = findPreference(Key.ROUTE_DNS_PREDEFINED_ANSWER)!!
        routeDnsPredefinedNsPreference = findPreference(Key.ROUTE_DNS_PREDEFINED_NS)!!
        routeDnsPredefinedExtraPreference = findPreference(Key.ROUTE_DNS_PREDEFINED_EXTRA)!!
        syncDnsRulePreferences()
        updateWifiSsidVisibility()
    }

    override fun onResume() {
        super.onResume()

        if (::editConfigPreference.isInitialized) {
            editConfigPreference.notifyChanged()
        }
    }

    val selectProfileForAdd = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (resultCode, data) ->
        if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                data!!.getLongExtra(
                    ProfileSelectActivity.EXTRA_PROFILE_ID, 0
                )
            ) ?: return@runOnDefaultDispatcher
            DataStore.routeOutboundRule = profile.id
            onMainDispatcher {
                outbound.value = "3"
                outbound.postUpdate()
            }
        }
    }

    val selectAppList = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (_, _) ->
        apps.postUpdate()
    }

    lateinit var outbound: OutboundPreference
    lateinit var apps: AppListPreference

    fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        outbound = findPreference(Key.ROUTE_OUTBOUND)!!
        apps = findPreference(Key.ROUTE_PACKAGES)!!
        routeProtocolPreference = findPreference(ROUTE_PROTOCOL_SELECTOR)!!
        routeProtocolPreference.value = routeProtocolSelectorValue(DataStore.routeProtocol)
        routeCreateDnsRulePreference = findPreference(Key.ROUTE_CREATE_DNS_RULE)!!
        routeCreateDnsRulePreference.value = DataStore.routeCreateDnsRule.toString()
        routeDnsActionPreference = findPreference(Key.ROUTE_DNS_ACTION)!!
        routeDnsServerPreference = findPreference(Key.ROUTE_DNS_SERVER)!!
        routeDnsStrategyPreference = findPreference(Key.ROUTE_DNS_STRATEGY)!!
        routeDnsDisableCachePreference = findPreference(Key.ROUTE_DNS_DISABLE_CACHE)!!
        routeDnsRewriteTtlPreference = findPreference(Key.ROUTE_DNS_REWRITE_TTL)!!
        routeDnsClientSubnetPreference = findPreference(Key.ROUTE_DNS_CLIENT_SUBNET)!!
        routeDnsRcodePreference = findPreference(Key.ROUTE_DNS_RCODE)!!
        routeDnsRejectMethodPreference = findPreference(Key.ROUTE_DNS_REJECT_METHOD)!!
        routeDnsPredefinedAnswerPreference = findPreference(Key.ROUTE_DNS_PREDEFINED_ANSWER)!!
        routeDnsPredefinedNsPreference = findPreference(Key.ROUTE_DNS_PREDEFINED_NS)!!
        routeDnsPredefinedExtraPreference = findPreference(Key.ROUTE_DNS_PREDEFINED_EXTRA)!!
        syncDnsRulePreferences()
        updateWifiSsidVisibility()
        routeProtocolPreference.setOnPreferenceChangeListener { _, newValue ->
            when (newValue.toString()) {
                ROUTE_PROTOCOL_CUSTOM -> {
                    showRouteProtocolCustomDialog()
                    false
                }

                else -> {
                    DataStore.routeProtocol = newValue.toString()
                    true
                }
            }
        }

        outbound.setOnPreferenceChangeListener { _, newValue ->
            if (newValue.toString() == "3") {
                selectProfileForAdd.launch(
                    Intent(
                        this@RouteSettingsActivity, ProfileSelectActivity::class.java
                    )
                )
                false
            } else {
                true
            }
        }

        apps.setOnPreferenceClickListener {
            selectAppList.launch(
                Intent(
                    this@RouteSettingsActivity, AppListActivity::class.java
                )
            )
            true
        }
        routeDnsActionPreference.setOnPreferenceChangeListener { _, newValue ->
            DataStore.routeDnsAction = newValue.toString()
            syncDnsRulePreferences()
            true
        }
        routeDnsServerPreference.setOnPreferenceChangeListener { _, newValue ->
            when (newValue.toString()) {
                DNS_SERVER_CUSTOM -> {
                    showCustomDnsServerPicker()
                    false
                }
                DNS_SERVER_BLOCK -> {
                    DataStore.routeDnsAction = "predefined"
                    DataStore.routeDnsRcode = "NOERROR"
                    syncDnsRulePreferences()
                    true
                }
                else -> {
                    DataStore.routeDnsAction = "route"
                    DataStore.routeDnsServer = newValue.toString()
                    syncDnsRulePreferences()
                    true
                }
            }
        }
    }

    fun displayPreferenceDialog(preference: Preference): Boolean {
        val mode = when (preference.key) {
            Key.ROUTE_WIFI_SSID -> RouteEditTextPreferenceDialogFragment.EditorMode.PLAIN_MULTILINE
            Key.ROUTE_WIFI_BSSID -> RouteEditTextPreferenceDialogFragment.EditorMode.PLAIN_MULTILINE
            Key.ROUTE_RULESET -> RouteEditTextPreferenceDialogFragment.EditorMode.RULESET
            Key.ROUTE_DOMAIN -> RouteEditTextPreferenceDialogFragment.EditorMode.ROUTE_DOMAIN
            Key.ROUTE_IP -> if (currentRuleType() == RuleType.DNS) {
                RouteEditTextPreferenceDialogFragment.EditorMode.PLAIN_MULTILINE
            } else {
                RouteEditTextPreferenceDialogFragment.EditorMode.ROUTE_IP
            }
            else -> null
        }
        if (mode != null && preference is EditTextPreference) {
            RouteEditTextPreferenceDialogFragment.newInstance(
                key = preference.key,
                title = preference.title?.toString().orEmpty(),
                value = preference.text.orEmpty(),
                mode = mode,
            ).show(supportFragmentManager, preference.key)
            return true
        }
        return false
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as RouteSettingsActivity).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    private fun currentRuleType(): RuleType {
        if (DataStore.editingId == 0L) return RuleType.fromValue(intent.getStringExtra(EXTRA_ROUTE_TYPE))
        return RuleType.fromValue(SagerDatabase.rulesDao.getById(DataStore.editingId)?.type)
    }

    private fun syncDnsRulePreferences() {
        if (!::routeDnsActionPreference.isInitialized) return
        if (!::outbound.isInitialized) return
        val isDnsRule = currentRuleType() == RuleType.DNS
        editConfigPreference.isVisible = true
        outbound.isVisible = !isDnsRule
        routeCreateDnsRulePreference.isVisible = !isDnsRule

        routeDnsActionPreference.isVisible = isDnsRule
        routeDnsServerPreference.isVisible = isDnsRule && DataStore.routeDnsAction.ifBlank { "route" } == "route"
        routeDnsStrategyPreference.isVisible = isDnsRule && DataStore.routeDnsAction in setOf("route", "route-options")
        routeDnsDisableCachePreference.isVisible = isDnsRule && DataStore.routeDnsAction in setOf("route", "route-options")
        routeDnsRewriteTtlPreference.isVisible = isDnsRule && DataStore.routeDnsAction in setOf("route", "route-options")
        routeDnsClientSubnetPreference.isVisible = isDnsRule && DataStore.routeDnsAction in setOf("route", "route-options")
        routeDnsRcodePreference.isVisible = isDnsRule && DataStore.routeDnsAction == "predefined"
        routeDnsRejectMethodPreference.isVisible = isDnsRule && DataStore.routeDnsAction == "reject"
        routeDnsPredefinedAnswerPreference.isVisible = isDnsRule && DataStore.routeDnsAction == "predefined"
        routeDnsPredefinedNsPreference.isVisible = isDnsRule && DataStore.routeDnsAction == "predefined"
        routeDnsPredefinedExtraPreference.isVisible = isDnsRule && DataStore.routeDnsAction == "predefined"

        supportActionBar?.setTitle(if (isDnsRule) R.string.dns_rule else R.string.cag_route)
        routeDnsActionPreference.value = DataStore.routeDnsAction.ifBlank { "route" }
        routeDnsStrategyPreference.value = DataStore.routeDnsStrategy.ifBlank { "" }
        routeDnsRcodePreference.value = DataStore.routeDnsRcode.ifBlank { "NOERROR" }
        val storedDnsServer = DataStore.routeDnsServer
        routeDnsServerPreference.value = when {
            DataStore.routeDnsAction == "predefined" && DataStore.routeDnsRcode == "NOERROR" -> DNS_SERVER_BLOCK
            storedDnsServer == DNS_SERVER_CUSTOM -> "dns-remote"
            storedDnsServer in setOf("dns-direct", "dns-remote") -> storedDnsServer
            storedDnsServer.isNotBlank() -> DNS_SERVER_CUSTOM
            else -> "dns-remote"
        }
        if (storedDnsServer.isNotBlank() && storedDnsServer !in setOf(DNS_SERVER_CUSTOM, DNS_SERVER_BLOCK, "dns-direct", "dns-remote")) {
            DataStore.routeDnsServer = storedDnsServer
        }
    }

    private fun showCustomDnsServerPicker() {
        val servers = CustomDnsServerStore.allServers()
        if (servers.isEmpty()) {
            Toast.makeText(this, R.string.custom_dns_servers_empty, Toast.LENGTH_SHORT).show()
            syncDnsRulePreferences()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_dns_servers)
            .setItems(servers.map { it.tag }.toTypedArray()) { _, which ->
                DataStore.routeDnsAction = "route"
                DataStore.routeDnsServer = servers[which].tag
                syncDnsRulePreferences()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> syncDnsRulePreferences() }
            .show()
    }

    @Parcelize
    data class ProfileIdArg(val ruleId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_route_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    ProfileManager.deleteRule(arg.ruleId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        resetDirtyWhenEditorReady = savedInstanceState == null
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.cag_route)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    init(intent.getStringExtra(EXTRA_PACKAGE_NAME))
                } else {
                    val ruleEntity = SagerDatabase.rulesDao.getById(editingId)
                    if (ruleEntity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    ruleEntity.init()
                }

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()
                }
            }


        }

    }

    suspend fun saveAndExit() {
        DataStore.routeWifiSsid = RuleEntity.normalizeWifiSsid(DataStore.routeWifiSsid)
        DataStore.routeWifiBssid = RuleEntity.normalizeWifiBssid(DataStore.routeWifiBssid)

        if (shouldRequestForegroundWifiPermission()) {
            pendingWifiPermissionSave = true
            onMainDispatcher {
                ActivityCompat.requestPermissions(
                    this@RouteSettingsActivity,
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                    WIFI_LOCATION_PERMISSION_REQUEST_CODE,
                )
            }
            return
        }
        if (shouldRequestBackgroundWifiPermission()) {
            onMainDispatcher {
                requestBackgroundWifiPermissionThenSave()
            }
            return
        }
        pendingWifiPermissionSave = false
        persistAndExit()
    }

    private suspend fun persistAndExit() {

        if (!needSave()) {
            onMainDispatcher {
                MaterialAlertDialogBuilder(this@RouteSettingsActivity).setTitle(R.string.empty_route)
                    .setMessage(R.string.empty_route_notice)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            return
        }

        val editingId = DataStore.editingId
        if (editingId == 0L) {
            if (intent.hasExtra(EXTRA_PACKAGE_NAME)) {
                setResult(RESULT_OK, Intent())
            }

            ProfileManager.createRule(RuleEntity().apply { serialize() })
        } else {
            val entity = SagerDatabase.rulesDao.getById(DataStore.editingId)
            if (entity == null) {
                finish()
                return
            }
            ProfileManager.updateRule(entity.apply { serialize() })
        }
        finish()

    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = child.onOptionsItemSelected(item)

    private fun showUnsavedChangesIfNeeded(): Boolean {
        if (!needSave()) return false
        UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
        return true
    }

    override fun onBackPressed() {
        if (!showUnsavedChangesIfNeeded()) super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun onEditorReady() {
        if (resetDirtyWhenEditorReady) {
            DataStore.dirty = false
            resetDirtyWhenEditorReady = false
        }
        if (!preferenceListenerRegistered) {
            DataStore.profileCacheStore.registerChangeListener(this)
            preferenceListenerRegistered = true
        }
    }

    override fun onDestroy() {
        if (preferenceListenerRegistered) {
            DataStore.profileCacheStore.unregisterChangeListener(this)
            preferenceListenerRegistered = false
        }
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY && key != ROUTE_PROTOCOL_SELECTOR) {
            DataStore.dirty = true
        }
        if (::routeProtocolPreference.isInitialized && key == Key.ROUTE_PROTOCOL) {
            routeProtocolPreference.value = routeProtocolSelectorValue(DataStore.routeProtocol)
        }
        if (::routeWifiSsidPreference.isInitialized && key == Key.ROUTE_NETWORK_TYPE) {
            updateWifiSsidVisibility()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != WIFI_LOCATION_PERMISSION_REQUEST_CODE || !pendingWifiPermissionSave) {
            return
        }
        pendingWifiPermissionSave = false
        runOnDefaultDispatcher {
            if (
                permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION) &&
                shouldRequestForegroundWifiPermission()
            ) {
                persistAndExit()
            } else if (permissions.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                persistAndExit()
            } else if (shouldRequestBackgroundWifiPermission()) {
                onMainDispatcher {
                    requestBackgroundWifiPermissionThenSave()
                }
            } else {
                persistAndExit()
            }
        }
    }

    private fun updateWifiSsidVisibility() {
        val isVisible = RuleEntity.isWifiIdentityVisible(DataStore.routeNetworkType)
        routeWifiSsidPreference.isVisible = isVisible
        routeWifiBssidPreference.isVisible = isVisible
    }

    private fun hasActiveWifiIdentity(): Boolean {
        if (!RuleEntity.isWifiIdentityVisible(DataStore.routeNetworkType)) {
            return false
        }
        return RuleEntity.normalizeWifiSsidList(DataStore.routeWifiSsid).isNotEmpty() ||
            RuleEntity.normalizeWifiBssidList(DataStore.routeWifiBssid).isNotEmpty()
    }

    private fun shouldRequestForegroundWifiPermission(): Boolean {
        if (!hasActiveWifiIdentity()) {
            return false
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun shouldRequestBackgroundWifiPermission(): Boolean {
        if (!hasActiveWifiIdentity() ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            shouldRequestForegroundWifiPermission()
        ) {
            return false
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestBackgroundWifiPermissionThenSave() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            pendingWifiPermissionSave = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                WIFI_LOCATION_PERMISSION_REQUEST_CODE,
            )
            return
        }

        pendingWifiBackgroundPermissionSave = true
        val allowAllTheTimeLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.backgroundPermissionOptionLabel
        } else {
            getString(R.string.wifi_background_location_permission_allow_all_the_time)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wifi_background_location_permission_title)
            .setMessage(
                getString(
                    R.string.wifi_background_location_permission_message,
                    allowAllTheTimeLabel,
                )
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                requestBackgroundLocationSettings.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
            }
            .setNegativeButton(R.string.wifi_background_location_permission_continue_without) { _, _ ->
                pendingWifiBackgroundPermissionSave = false
                runOnDefaultDispatcher {
                    persistAndExit()
                }
            }
            .show()
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: RouteSettingsActivity? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as RouteSettingsActivity).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)

            activity?.apply {
                viewCreated(view, savedInstanceState)
                onEditorReady()
            }
        }

        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else if (!DataStore.confirmProfileDelete) {
                    runOnDefaultDispatcher {
                        ProfileManager.deleteRule(DataStore.editingId)
                    }
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(ProfileIdArg(DataStore.editingId))
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            activity?.apply {
                if (displayPreferenceDialog(preference)) return
            }
            if (showMaterialEditTextPreferenceDialog(preference)) return
            super.onDisplayPreferenceDialog(preference)
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

    object MultiSelectSummaryProvider : Preference.SummaryProvider<MultiSelectListPreference> {

        override fun provideSummary(preference: MultiSelectListPreference): CharSequence {
            val values = preference.values
            if (values.isEmpty()) {
                return preference.context.getString(androidx.preference.R.string.not_set)
            }

            val labels = preference.entryValues
                ?.mapIndexedNotNull { index, value ->
                    value?.toString()?.takeIf(values::contains)?.let {
                        preference.entries?.getOrNull(index)?.toString() ?: it
                    }
                }
                .orEmpty()

            return labels.joinToString()
        }

    }

    object RouteProtocolSummaryProvider : Preference.SummaryProvider<SimpleMenuPreference> {

        override fun provideSummary(preference: SimpleMenuPreference): CharSequence {
            val protocol = DataStore.routeProtocol
            if (protocol.isBlank()) {
                return preference.context.getString(androidx.preference.R.string.not_set)
            }

            val index = preference.entryValues.indexOf(protocol)
            if (index >= 0) {
                return preference.entries.getOrNull(index) ?: protocol
            }

            return protocol
        }

    }

}
