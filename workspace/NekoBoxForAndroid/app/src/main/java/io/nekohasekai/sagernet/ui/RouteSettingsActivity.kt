package io.nekohasekai.sagernet.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.CustomDnsServerStore
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleType
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.onDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog
import io.nekohasekai.sagernet.ui.compose.showComposeItemDialog
import io.nekohasekai.sagernet.ui.compose.showComposeTextInputDialog
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.RouteEditorField
import io.nekohasekai.sagernet.ui.compose.RouteSettingsScreen
import io.nekohasekai.sagernet.ui.compose.showComposeSingleChoiceDialog
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.RouteEditTextPreferenceDialogFragment
import io.nekohasekai.sagernet.ui.profile.ConfigEditActivity
import kotlinx.coroutines.launch

@Suppress("UNCHECKED_CAST")
class RouteSettingsActivity : ThemedActivity(),
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

    private var screenRevision by mutableIntStateOf(0)
    private var dnsRule = false
    private var pendingWifiPermissionSave = false
    private var pendingWifiBackgroundPermissionSave = false
    private var resetDirtyWhenEditorReady = false
    private var preferenceListenerRegistered = false
    private val editConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        screenRevision++
    }

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
        showComposeTextInputDialog(
            title = getText(R.string.protocol),
            initialValue = DataStore.routeProtocol,
            onPositive = { value ->
                DataStore.routeProtocol = value
                screenRevision++
            },
        )
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
                DataStore.routeOutbound = 3
                screenRevision++
            }
        }
    }

    val selectAppList = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (_, _) ->
        screenRevision++
    }

    private fun currentRuleType(): RuleType {
        if (DataStore.editingId == 0L) return RuleType.fromValue(intent.getStringExtra(EXTRA_ROUTE_TYPE))
        return RuleType.fromValue(SagerDatabase.rulesDao.getById(DataStore.editingId)?.type)
    }

    private fun showCustomDnsServerPicker() {
        val servers = CustomDnsServerStore.allServers()
        if (servers.isEmpty()) {
            Toast.makeText(this, R.string.custom_dns_servers_empty, Toast.LENGTH_SHORT).show()
            return
        }
        showComposeItemDialog(
            title = getText(R.string.custom_dns_servers),
            items = servers.map { it.tag },
            negativeButton = getText(android.R.string.cancel),
            onItemSelected = { which ->
                DataStore.routeDnsAction = "route"
                DataStore.routeDnsServer = servers[which].tag
                screenRevision++
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        resetDirtyWhenEditorReady = savedInstanceState == null
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeEditor()
        })
        val editingId = intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
        DataStore.editingId = editingId
        lifecycleScope.launch {
            val loaded = onDefaultDispatcher {
                if (editingId == 0L) {
                    init(intent.getStringExtra(EXTRA_PACKAGE_NAME))
                    true
                } else {
                    SagerDatabase.rulesDao.getById(editingId)?.also { it.init() } != null
                }
            }
            if (!loaded) {
                finish()
                return@launch
            }
            dnsRule = onDefaultDispatcher { currentRuleType() == RuleType.DNS }
            onEditorReady()
            setContent {
                NekoComposeTheme {
                    screenRevision
                    RouteSettingsScreen(
                        isDnsRule = dnsRule,
                        outboundName = currentOutboundName(),
                        onClose = ::closeEditor,
                        onSave = { runOnDefaultDispatcher { saveAndExit() } },
                        onDelete = ::requestDelete,
                        onEditConfig = { editConfig.launch(Intent(this@RouteSettingsActivity, ConfigEditActivity::class.java)) },
                        onSelectApps = { selectAppList.launch(Intent(this@RouteSettingsActivity, AppListActivity::class.java)) },
                        onSelectOutbound = ::selectOutbound,
                        onSelectDnsServer = ::selectDnsServer,
                        onEditProtocol = ::selectProtocol,
                        onSpecialEditor = ::showSpecialEditor,
                    )
                }
            }
        }
    }

    suspend fun saveAndExit() {
        RuleEntity.normalizeWifiSsid(DataStore.routeWifiSsid).let { normalized ->
            if (normalized != DataStore.routeWifiSsid) DataStore.routeWifiSsid = normalized
        }
        RuleEntity.normalizeWifiBssid(DataStore.routeWifiBssid).let { normalized ->
            if (normalized != DataStore.routeWifiBssid) DataStore.routeWifiBssid = normalized
        }

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
                showComposeMessageDialog(
                    title = getText(R.string.empty_route),
                    message = getText(R.string.empty_route_notice),
                )
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

    private fun closeEditor() {
        if (!needSave()) {
            finish()
            return
        }
        showComposeMessageDialog(
            title = getText(R.string.unsaved_changes_prompt),
            positiveButton = getText(R.string.yes),
            negativeButton = getText(R.string.no),
            neutralButton = getText(android.R.string.cancel),
            onPositive = { runOnDefaultDispatcher { saveAndExit() } },
            onNegative = ::finish,
        )
    }

    private fun requestDelete() {
        val id = DataStore.editingId
        if (id == 0L) {
            finish()
            return
        }
        fun delete() {
            runOnDefaultDispatcher { ProfileManager.deleteRule(id) }
            finish()
        }
        if (DataStore.confirmProfileDelete) showComposeMessageDialog(
            title = getText(R.string.delete_route_prompt),
            positiveButton = getText(R.string.yes),
            negativeButton = getText(R.string.no),
            onPositive = ::delete,
        ) else delete()
    }

    private fun currentOutboundName(): String {
        val entries = resources.getStringArray(R.array.outbound_entry)
        val values = resources.getStringArray(R.array.outbound_value)
        return if (DataStore.routeOutbound == 3) {
            ProfileManager.getProfile(DataStore.routeOutboundRule)?.displayName() ?: getString(R.string.none)
        } else entries.getOrElse(values.indexOf(DataStore.routeOutbound.toString())) { getString(R.string.none) }
    }

    private fun selectOutbound() {
        val entries = resources.getStringArray(R.array.outbound_entry).toList()
        val values = resources.getStringArray(R.array.outbound_value)
        showComposeSingleChoiceDialog(
            title = getText(R.string.outbound),
            items = entries,
            selectedIndex = values.indexOf(DataStore.routeOutbound.toString()).coerceAtLeast(0),
            onItemSelected = { index ->
                val value = values[index].toInt()
                if (value == 3) {
                    selectProfileForAdd.launch(Intent(this, ProfileSelectActivity::class.java))
                } else {
                    DataStore.routeOutbound = value
                    screenRevision++
                }
            },
        )
    }

    private fun selectProtocol() {
        val entries = resources.getStringArray(R.array.route_sniff_protocol_entry).toList()
        val values = routeProtocolValues
        showComposeSingleChoiceDialog(
            title = getText(R.string.protocol),
            items = entries,
            selectedIndex = values.indexOf(routeProtocolSelectorValue(DataStore.routeProtocol)).coerceAtLeast(0),
            onItemSelected = { index ->
                if (values[index] == ROUTE_PROTOCOL_CUSTOM) showRouteProtocolCustomDialog()
                else {
                    DataStore.routeProtocol = values[index]
                    screenRevision++
                }
            },
        )
    }

    private fun selectDnsServer() {
        val entries = resources.getStringArray(R.array.dns_rule_server_entry).toList()
        val values = resources.getStringArray(R.array.dns_rule_server_value)
        val selected = when {
            DataStore.routeDnsAction == "predefined" && DataStore.routeDnsRcode == "NOERROR" -> DNS_SERVER_BLOCK
            DataStore.routeDnsServer in values -> DataStore.routeDnsServer
            else -> DNS_SERVER_CUSTOM
        }
        showComposeSingleChoiceDialog(
            title = getText(R.string.dns_rule_server),
            items = entries,
            selectedIndex = values.indexOf(selected).coerceAtLeast(0),
            onItemSelected = { index -> when (val value = values[index]) {
                DNS_SERVER_CUSTOM -> showCustomDnsServerPicker()
                DNS_SERVER_BLOCK -> {
                    DataStore.routeDnsAction = "predefined"
                    DataStore.routeDnsRcode = "NOERROR"
                    screenRevision++
                }
                else -> {
                    DataStore.routeDnsAction = "route"
                    DataStore.routeDnsServer = value
                    screenRevision++
                }
            } },
        )
    }

    private fun showSpecialEditor(field: RouteEditorField, title: String, value: String) {
        val (key, mode) = when (field) {
            RouteEditorField.DOMAIN -> Key.ROUTE_DOMAIN to RouteEditTextPreferenceDialogFragment.EditorMode.ROUTE_DOMAIN
            RouteEditorField.IP -> Key.ROUTE_IP to if (dnsRule)
                RouteEditTextPreferenceDialogFragment.EditorMode.PLAIN_MULTILINE
            else RouteEditTextPreferenceDialogFragment.EditorMode.ROUTE_IP
            RouteEditorField.RULESET -> Key.ROUTE_RULESET to RouteEditTextPreferenceDialogFragment.EditorMode.RULESET
            RouteEditorField.WIFI_SSID -> Key.ROUTE_WIFI_SSID to RouteEditTextPreferenceDialogFragment.EditorMode.PLAIN_MULTILINE
            RouteEditorField.WIFI_BSSID -> Key.ROUTE_WIFI_BSSID to RouteEditTextPreferenceDialogFragment.EditorMode.PLAIN_MULTILINE
        }
        RouteEditTextPreferenceDialogFragment.newInstance(key, title, value, mode)
            .show(supportFragmentManager, key)
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
        screenRevision++
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
        showComposeMessageDialog(
            title = getText(R.string.wifi_background_location_permission_title),
            message = getString(
                R.string.wifi_background_location_permission_message,
                allowAllTheTimeLabel,
            ),
            positiveButton = getText(android.R.string.ok),
            negativeButton = getText(R.string.wifi_background_location_permission_continue_without),
            onPositive = {
                requestBackgroundLocationSettings.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
            },
            onNegative = {
                pendingWifiBackgroundPermissionSave = false
                runOnDefaultDispatcher {
                    persistAndExit()
                }
            },
        )
    }

}
