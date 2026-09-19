package io.nekohasekai.sagernet.ui.profile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.internal.decodeEmbeddedProfiles
import io.nekohasekai.sagernet.fmt.internal.filterInsecureProfiles
import io.nekohasekai.sagernet.fmt.internal.hasEmbeddedProfiles
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.compose.ProfileShareAction
import io.nekohasekai.sagernet.ui.compose.ProxySetProfileSettingsScreen
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog
import io.nekohasekai.sagernet.ui.compose.showComposeSingleChoiceDialog
import io.nekohasekai.sagernet.widget.QRCodeDialog

class ProxySetSettingsActivity : ProfileSettingsActivity<ProxySetBean>() {
    companion object {
        private const val KEY_MODE = "proxySetMode"
        private const val KEY_DEFAULT_OUTBOUND = "proxySetDefaultOutbound"
        private const val KEY_INTERRUPT = "proxySetInterruptExistConnections"
        private const val KEY_TEST_URL = "proxySetTestURL"
        private const val KEY_TEST_INTERVAL = "proxySetTestInterval"
        private const val KEY_TEST_IDLE_TIMEOUT = "proxySetTestIdleTimeout"
        private const val KEY_TEST_TOLERANCE = "proxySetTestTolerance"
        private const val KEY_TYPE = "proxySetType"
        private const val KEY_GROUP = "proxySetGroup"
        private const val KEY_GROUP_FILTER = "proxySetGroupFilterNotRegex"
        private const val KEY_SKIP_INSECURE = "proxySetSkipInsecureProfiles"
    }

    override val usesComposePreferences = true
    override fun createEntity() = ProxySetBean()

    private val proxyList = mutableStateListOf<ProxyEntity>()
    private val testingEmbeddedIds = mutableStateListOf<Long>()
    private var embeddedProfiles = emptyList<ProxyEntity>()
    private var hasEmbeddedMembers = false
    private var profilesLoaded = false
    private var replacing = -1
    private var uiRevision by mutableIntStateOf(0)
    private var pendingSharedConfiguration: String? = null

    private val exportSharedConfiguration =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val content = pendingSharedConfiguration
            pendingSharedConfiguration = null
            if (uri == null || content == null) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                    ?: error("Unable to open output file")
            }.onFailure {
                Logs.w(it)
                Toast.makeText(this, it.readableMessage, Toast.LENGTH_LONG).show()
            }
        }

    override fun ProxySetBean.init() {
        hasEmbeddedMembers = hasEmbeddedProfiles()
        embeddedProfiles = if (hasEmbeddedMembers) decodeEmbeddedProfiles() else emptyList()
        DataStore.profileName = name
        DataStore.serverProtocol = if (hasEmbeddedMembers) "" else proxies.joinToString(",")
        DataStore.profileCacheStore.putString(KEY_MODE, mode.toString())
        DataStore.profileCacheStore.putLong(KEY_DEFAULT_OUTBOUND, defaultOutbound)
        DataStore.profileCacheStore.putBoolean(KEY_INTERRUPT, interruptExistConnections)
        DataStore.profileCacheStore.putString(KEY_TEST_URL, testURL)
        DataStore.profileCacheStore.putString(KEY_TEST_INTERVAL, testInterval)
        DataStore.profileCacheStore.putString(KEY_TEST_IDLE_TIMEOUT, testIdleTimeout)
        DataStore.profileCacheStore.putString(KEY_TEST_TOLERANCE, testTolerance.toString())
        DataStore.profileCacheStore.putString(KEY_TYPE, type.toString())
        DataStore.profileCacheStore.putString(KEY_GROUP, groupId.toString())
        DataStore.profileCacheStore.putString(KEY_GROUP_FILTER, groupFilterNotRegex)
        DataStore.profileCacheStore.putBoolean(KEY_SKIP_INSECURE, skipInsecureProfiles)
    }

    override fun ProxySetBean.serialize() {
        name = DataStore.profileName
        mode = if (hasEmbeddedMembers) ProxySetBean.MODE_URL_TEST else
            DataStore.profileCacheStore.getString(KEY_MODE)?.toIntOrNull() ?: ProxySetBean.MODE_SELECTOR
        defaultOutbound = currentDefaultOutbound()
        interruptExistConnections = DataStore.profileCacheStore.getBoolean(KEY_INTERRUPT) ?: false
        testURL = DataStore.profileCacheStore.getString(KEY_TEST_URL) ?: CONNECTION_TEST_URL
        testInterval = DataStore.profileCacheStore.getString(KEY_TEST_INTERVAL) ?: "3m"
        testIdleTimeout = DataStore.profileCacheStore.getString(KEY_TEST_IDLE_TIMEOUT) ?: "3m"
        testTolerance = DataStore.profileCacheStore.getString(KEY_TEST_TOLERANCE)?.toIntOrNull() ?: 50
        type = if (hasEmbeddedMembers) ProxySetBean.TYPE_LIST else currentCollectType()
        groupId = DataStore.profileCacheStore.getString(KEY_GROUP)?.toLongOrNull() ?: 0L
        groupFilterNotRegex = DataStore.profileCacheStore.getString(KEY_GROUP_FILTER).orEmpty()
        skipInsecureProfiles = DataStore.profileCacheStore.getBoolean(KEY_SKIP_INSECURE) ?: false
        if (!hasEmbeddedMembers) proxies = proxyList.map { it.id }
        initializeDefaultValues()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.proxy_set_settings)
    }

    @Composable
    override fun ComposePreferences() {
        uiRevision
        ProxySetProfileSettingsScreen(
            profiles = proxyList,
            testingIds = testingEmbeddedIds,
            hasEmbeddedMembers = hasEmbeddedMembers,
            selectedGroupName = currentGroupName(),
            defaultOutboundName = currentDefaultOutboundName(),
            onLoad = ::loadProfiles,
            onSettingChanged = { uiRevision++ },
            onSelectGroup = ::showGroupDialog,
            onSelectDefaultOutbound = ::showDefaultOutboundDialog,
            onAdd = { selectProfile(-1, null) },
            onReplace = { selectProfile(it, proxyList[it]) },
            onMove = ::moveProfile,
            onDelete = ::requestDelete,
            onUrlTest = ::testEmbeddedProfile,
            onShare = ::shareEmbeddedProfile,
        )
    }

    private fun loadProfiles() {
        if (profilesLoaded) return
        profilesLoaded = true
        if (hasEmbeddedMembers) {
            proxyList.clear()
            proxyList.addAll(embeddedProfiles)
            uiRevision++
            return
        }
        runOnDefaultDispatcher {
            val ids = DataStore.serverProtocol.split(",").mapNotNull { it.toLongOrNull() }
            val profiles = ProfileManager.getProfiles(ids).associateBy { it.id }
            onMainDispatcher {
                proxyList.clear()
                ids.mapNotNullTo(proxyList) { profiles[it] }
                uiRevision++
            }
        }
    }

    private fun currentCollectType() =
        DataStore.profileCacheStore.getString(KEY_TYPE)?.toIntOrNull() ?: ProxySetBean.TYPE_LIST

    private fun currentDefaultOutbound() =
        DataStore.profileCacheStore.getLong(KEY_DEFAULT_OUTBOUND) ?: 0L

    private fun currentGroupName(): String {
        val groupId = DataStore.profileCacheStore.getString(KEY_GROUP)?.toLongOrNull() ?: 0L
        return SagerDatabase.groupDao.getById(groupId)?.displayName() ?: getString(R.string.not_set)
    }

    private fun selectableDefaultOutbounds(): List<ProxyEntity> {
        val filterBean = ProxySetBean().apply {
            skipInsecureProfiles = DataStore.profileCacheStore.getBoolean(KEY_SKIP_INSECURE) ?: false
        }
        if (currentCollectType() != ProxySetBean.TYPE_GROUP) {
            return filterBean.filterInsecureProfiles(proxyList, DataStore.globalAllowInsecure)
        }
        val groupId = DataStore.profileCacheStore.getString(KEY_GROUP)?.toLongOrNull() ?: 0L
        val regex = DataStore.profileCacheStore.getString(KEY_GROUP_FILTER)
            ?.takeIf(String::isNotBlank)?.let { runCatching { it.toRegex() }.getOrNull() }
        val profiles = SagerDatabase.proxyDao.getByGroup(groupId).filter { profile ->
            profile.id != DataStore.editingId &&
                profile.type != ProxyEntity.TYPE_PROXY_SET &&
                profile.type != ProxyEntity.TYPE_CHAIN &&
                !profile.containsMasterDnsVPN() &&
                !profile.containsByeDPI() &&
                (regex == null || regex.containsMatchIn(profile.displayName()))
        }
        return filterBean.filterInsecureProfiles(profiles, DataStore.globalAllowInsecure)
    }

    private fun currentDefaultOutboundName() = selectableDefaultOutbounds()
        .firstOrNull { it.id == currentDefaultOutbound() }?.displayName() ?: getString(R.string.none)

    private fun showDefaultOutboundDialog() {
        val profiles = selectableDefaultOutbounds()
        val values = listOf(0L) + profiles.map { it.id }
        val labels = listOf(getString(R.string.none)) + profiles.map { it.displayName() }
        val checked = values.indexOf(currentDefaultOutbound()).coerceAtLeast(0)
        showComposeSingleChoiceDialog(
            title = getText(R.string.proxy_set_default_outbound),
            items = labels,
            selectedIndex = checked,
            negativeButton = getText(android.R.string.cancel),
            onItemSelected = {
                DataStore.profileCacheStore.putLong(KEY_DEFAULT_OUTBOUND, values[it])
                uiRevision++
            },
        )
    }

    private fun showGroupDialog() {
        val groups = SagerDatabase.groupDao.allGroups()
        val selectedGroupId = DataStore.profileCacheStore.getString(KEY_GROUP)?.toLongOrNull()
        showComposeSingleChoiceDialog(
            title = getText(R.string.proxy_set_type_group),
            items = groups.map { it.displayName() },
            selectedIndex = groups.indexOfFirst { it.id == selectedGroupId },
            negativeButton = getText(android.R.string.cancel),
            onItemSelected = {
                DataStore.profileCacheStore.putString(KEY_GROUP, groups[it].id.toString())
                uiRevision++
            },
        )
    }

    private fun moveProfile(from: Int, to: Int) {
        if (hasEmbeddedMembers || from !in proxyList.indices || to !in proxyList.indices) return
        proxyList.add(to, proxyList.removeAt(from))
        DataStore.dirty = true
        uiRevision++
    }

    private fun requestDelete(index: Int) {
        if (hasEmbeddedMembers || index !in proxyList.indices) return
        val remove = {
            if (index in proxyList.indices) proxyList.removeAt(index)
            DataStore.dirty = true
            uiRevision++
            Unit
        }
        if (DataStore.confirmProfileDelete) showComposeMessageDialog(
            title = getText(R.string.delete_confirm_prompt),
            positiveButton = getText(R.string.yes),
            negativeButton = getText(R.string.no),
            onPositive = remove,
        ) else remove()
    }

    private fun selectProfile(index: Int, selected: ProxyEntity?) {
        replacing = index
        selectProfileForAdd.launch(Intent(this, ProfileSelectActivity::class.java).apply {
            selected?.let { putExtra(ProfileSelectActivity.EXTRA_SELECTED, it) }
        })
    }

    private fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId || profile.type == ProxyEntity.TYPE_PROXY_SET ||
            profile.containsMasterDnsVPN() || profile.containsByeDPI() ||
            proxyList.any { it.id == profile.id }) return false
        return proxyList.none { testProfileContains(it, profile) }
    }

    private fun testProfileContains(profile: ProxyEntity, another: ProxyEntity): Boolean {
        if (profile.type != ProxyEntity.TYPE_CHAIN || another.type != ProxyEntity.TYPE_CHAIN) return false
        if (profile.id == another.id) return true
        val ids = profile.chainBean!!.proxies
        return another.id in ids || ids.isNotEmpty() && ProfileManager.getProfiles(ids).any {
            testProfileContains(it, another)
        }
    }

    private val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode != Activity.RESULT_OK || data == null) return@registerForActivityResult
            runOnDefaultDispatcher {
                val profile = ProfileManager.getProfile(
                    data.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0),
                ) ?: return@runOnDefaultDispatcher
                if (!testProfileAllowed(profile)) {
                    onMainDispatcher {
                        showComposeMessageDialog(
                            title = getText(R.string.invalid_profile),
                            message = getText(when {
                                profile.type == ProxyEntity.TYPE_MASTERDNSVPN -> R.string.masterdnsvpn_proxy_set_error
                                profile.containsMasterDnsVPN() -> R.string.masterdnsvpn_chain_error
                                profile.containsByeDPI() -> R.string.byedpi_proxy_set_error
                                else -> R.string.circular_reference_sum
                            }),
                        )
                    }
                    return@runOnDefaultDispatcher
                }
                onMainDispatcher {
                    if (replacing in proxyList.indices) proxyList[replacing] = profile else proxyList.add(profile)
                    DataStore.dirty = true
                    uiRevision++
                }
            }
        }

    private fun testEmbeddedProfile(profile: ProxyEntity) {
        if (profile.id in testingEmbeddedIds) return
        testingEmbeddedIds.add(profile.id)
        runOnDefaultDispatcher {
            try {
                profile.ping = UrlTest().doTest(profile)
                profile.status = 1
                profile.error = null
            } catch (error: Exception) {
                Logs.w(error)
                profile.status = 3
                profile.error = error.readableMessage
            } finally {
                onMainDispatcher { testingEmbeddedIds.remove(profile.id) }
            }
        }
    }

    private fun shareEmbeddedProfile(action: ProfileShareAction, profile: ProxyEntity) {
        try {
            val content = when (action) {
                ProfileShareAction.STANDARD_QR, ProfileShareAction.STANDARD_CLIPBOARD -> profile.toStdLink()
                ProfileShareAction.UNIVERSAL_QR, ProfileShareAction.UNIVERSAL_CLIPBOARD -> profile.requireBean().toUniversalLink()
                ProfileShareAction.CONFIGURATION_CLIPBOARD -> profile.exportConfig().first
                ProfileShareAction.CONFIGURATION_FILE -> null
            }
            when (action) {
                ProfileShareAction.STANDARD_QR, ProfileShareAction.UNIVERSAL_QR ->
                    QRCodeDialog(content!!, profile.displayName()).showAllowingStateLoss(supportFragmentManager)
                ProfileShareAction.STANDARD_CLIPBOARD,
                ProfileShareAction.UNIVERSAL_CLIPBOARD,
                ProfileShareAction.CONFIGURATION_CLIPBOARD -> Toast.makeText(
                    this,
                    if (SagerNet.trySetPrimaryClip(content!!)) R.string.action_export_msg else R.string.action_export_err,
                    Toast.LENGTH_SHORT,
                ).show()
                ProfileShareAction.CONFIGURATION_FILE -> {
                    val (configuration, fileName) = profile.exportConfig()
                    pendingSharedConfiguration = configuration
                    exportSharedConfiguration.launch(fileName)
                }
            }
        } catch (error: Exception) {
            Logs.w(error)
            Toast.makeText(this, error.readableMessage, Toast.LENGTH_LONG).show()
        }
    }
}
