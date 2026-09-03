package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.QuickToggleShortcut
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ui.ProfileShareCapabilities
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.compose.GroupPickerItem
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.ProfileSettingsActions
import io.nekohasekai.sagernet.ui.compose.ProfileSettingsScaffold
import io.nekohasekai.sagernet.ui.compose.showComposeGroupMoveDialog
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlin.properties.Delegates

@Suppress("UNCHECKED_CAST")
abstract class ProfileSettingsActivity<T : AbstractBean> :
    ThemedActivity(),
    OnPreferenceDataStoreChangeListener {

    companion object {
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_IS_SUBSCRIPTION = "sub"
    }

    abstract fun createEntity(): T
    abstract fun T.init()
    abstract fun T.serialize()
    open val usesComposePreferences: Boolean = true

    @Composable
    open fun ComposePreferences() = Unit

    private val proxyEntity by lazy { SagerDatabase.proxyDao.getById(DataStore.editingId) }
    private var editingBean: T? = null
    private var composeReady by mutableStateOf(false)
    private var composeActions by mutableStateOf(ProfileSettingsActions())
    private var pendingSharedConfiguration: String? = null
    protected var isSubscription by Delegates.notNull<Boolean>()

    private val exportSharedConfigurationLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val configuration = pendingSharedConfiguration
            pendingSharedConfiguration = null
            if (uri == null || configuration == null) return@registerForActivityResult
            runOnDefaultDispatcher {
                try {
                    contentResolver.openOutputStream(uri)!!.bufferedWriter().use {
                        it.write(configuration)
                    }
                    onMainDispatcher {
                        Toast.makeText(
                            this@ProfileSettingsActivity,
                            R.string.action_export_msg,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } catch (error: Exception) {
                    Logs.w(error)
                    onMainDispatcher {
                        Toast.makeText(
                            this@ProfileSettingsActivity,
                            error.readableMessage,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }

    private val resultCallbackCustom = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { editingBean?.customConfigJson = DataStore.serverCustom }

    private val resultCallbackCustomOutbound = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { editingBean?.customOutboundJson = DataStore.serverCustomOutbound }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NekoComposeTheme {
                    ProfileSettingsScaffold(
                        ready = composeReady,
                        actions = composeActions,
                        onClose = ::handleBackNavigation,
                        onAction = { handleProfileAction(it) },
                        content = { ComposePreferences() },
                    )
                }
            }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBackNavigation()
        })

        val editingId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        isSubscription = intent.getBooleanExtra(EXTRA_IS_SUBSCRIPTION, false)
        if (savedInstanceState == null) DataStore.editingId = editingId
        runOnDefaultDispatcher {
            val bean = when {
                editingId == 0L -> createEntity().applyDefaultValues()
                proxyEntity == null -> {
                    onMainDispatcher { finish() }
                    return@runOnDefaultDispatcher
                }
                else -> proxyEntity!!.requireBean() as T
            }
            editingBean = bean
            if (savedInstanceState == null) {
                DataStore.editingGroup = if (editingId == 0L) {
                    DataStore.selectedGroupForImport()
                } else proxyEntity!!.groupId
                writeSharedOptionsToCache(bean)
                DataStore.serverCustom = bean.customConfigJson
                DataStore.serverCustomOutbound = bean.customOutboundJson
                bean.init()
            }
            onMainDispatcher {
                attachComposePreferences()
            }
        }
    }

    private fun attachComposePreferences() {
        val capabilities = editingBean?.let {
            ProfileShareCapabilities.from(ProxyEntity().putBean(it))
        }
        composeActions = ProfileSettingsActions(
            links = capabilities?.links == true,
            standardLinks = capabilities?.standardLinks == true,
            configuration = capabilities?.configuration == true,
            move = DataStore.editingId != 0L &&
                SagerDatabase.groupDao.getById(DataStore.editingGroup)?.type == GroupType.BASIC &&
                SagerDatabase.groupDao.allGroups().count { it.type == GroupType.BASIC } > 1,
            shortcut = Build.VERSION.SDK_INT >= 26 && DataStore.editingId != 0L,
        )
        composeReady = true
        DataStore.dirty = false
        DataStore.profileCacheStore.registerChangeListener(this)
    }

    open suspend fun saveAndExit() {
        val editingId = DataStore.editingId
        if (editingId == 0L) {
            ProfileManager.createProfile(DataStore.editingGroup, createEntity().apply {
                serializeFromCache()
            })
        } else {
            val entity = proxyEntity ?: run { finish(); return }
            if (entity.id == DataStore.selectedProxy) SagerNet.stopService()
            ProfileManager.updateEditedProfile(entity.apply {
                (requireBean() as T).serializeFromCache()
            })
        }
        finish()
    }

    private fun T.serializeFromCache() {
        serialize()
        readSharedOptionsFromCache(this)
        customConfigJson = DataStore.serverCustom
        customOutboundJson = DataStore.serverCustomOutbound
    }

    @SuppressLint("CheckResult")
    private fun handleProfileAction(actionId: Int): Boolean = when (actionId) {
        R.id.action_standard_qr, R.id.action_universal_qr,
        R.id.action_standard_clipboard, R.id.action_universal_clipboard,
        R.id.action_config_export_clipboard, R.id.action_config_export_file,
        -> {
            shareProfile(actionId)
            true
        }
        R.id.action_delete -> {
            requestDelete()
            true
        }
        R.id.action_apply -> {
            runOnDefaultDispatcher { saveAndExit() }
            true
        }
        R.id.action_custom_outbound_json -> {
            DataStore.serverCustomOutbound = editingBean?.customOutboundJson.orEmpty()
            resultCallbackCustomOutbound.launch(Intent(this, ConfigEditActivity::class.java).apply {
                putExtra("key", Key.SERVER_CUSTOM_OUTBOUND)
            })
            true
        }
        R.id.action_custom_config_json -> {
            DataStore.serverCustom = editingBean?.customConfigJson.orEmpty()
            resultCallbackCustom.launch(Intent(this, ConfigEditActivity::class.java).apply {
                putExtra("key", Key.SERVER_CUSTOM)
            })
            true
        }
        R.id.action_create_shortcut -> {
            createShortcut()
            true
        }
        R.id.action_move -> {
            moveProfile()
            true
        }
        else -> false
    }

    private fun shareProfile(action: Int) {
        try {
            val entity = currentShareEntity()
            val content = when (action) {
                R.id.action_standard_qr, R.id.action_standard_clipboard -> entity.toStdLink()
                R.id.action_universal_qr, R.id.action_universal_clipboard ->
                    entity.requireBean().toUniversalLink()
                R.id.action_config_export_clipboard -> entity.exportConfig().first
                else -> null
            }
            when (action) {
                R.id.action_standard_qr, R.id.action_universal_qr ->
                    QRCodeDialog(content!!, entity.displayName())
                        .showAllowingStateLoss(supportFragmentManager)
                R.id.action_standard_clipboard, R.id.action_universal_clipboard,
                R.id.action_config_export_clipboard,
                -> Toast.makeText(
                    this,
                    if (SagerNet.trySetPrimaryClip(content!!)) {
                        R.string.action_export_msg
                    } else R.string.action_export_err,
                    Toast.LENGTH_SHORT,
                ).show()
                R.id.action_config_export_file -> exportSharedConfiguration(entity)
            }
        } catch (error: Exception) {
            Logs.w(error)
            Toast.makeText(this, error.readableMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestDelete() {
        if (DataStore.editingId == 0L) {
            finish()
            return
        }
        val delete = {
            runOnDefaultDispatcher {
                ProfileManager.deleteProfile(DataStore.editingGroup, DataStore.editingId)
            }
            finish()
        }
        if (DataStore.confirmProfileDelete) showComposeMessageDialog(
            title = getText(R.string.delete_confirm_prompt),
            positiveButton = getText(R.string.yes),
            negativeButton = getText(R.string.no),
            onPositive = delete,
        ) else delete()
    }

    private fun createShortcut() {
        val entity = proxyEntity ?: return
        val shortcut = ShortcutInfoCompat.Builder(this, "shortcut-profile-${entity.id}")
            .setShortLabel(entity.displayName())
            .setLongLabel(entity.displayName())
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_qu_shadowsocks_launcher))
            .setIntent(Intent(this, QuickToggleShortcut::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra("profile", entity.id)
            }).build()
        ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
    }

    private fun moveProfile() {
        val entity = proxyEntity ?: return
        val groups = SagerDatabase.groupDao.allGroups()
            .filter { it.type == GroupType.BASIC && it.id != entity.groupId }
            .map { GroupPickerItem(it.id, it.displayName()) }
        showComposeGroupMoveDialog(
            title = getText(R.string.move),
            groups = groups,
            negativeButton = getText(android.R.string.cancel),
        ) { newGroupId ->
            runOnDefaultDispatcher {
                val oldGroupId = entity.groupId
                entity.groupId = newGroupId
                ProfileManager.updateProfile(entity)
                GroupManager.postUpdate(oldGroupId)
                GroupManager.postUpdate(newGroupId)
                DataStore.editingGroup = newGroupId
                runOnMainDispatcher { finish() }
            }
        }
    }

    private fun handleBackNavigation() {
        if (!DataStore.dirty) {
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

    override fun onSupportNavigateUp(): Boolean {
        handleBackNavigation()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) DataStore.dirty = true
    }

    private fun writeSharedOptionsToCache(bean: AbstractBean) {
        val store = DataStore.profileCacheStore
        store.putBoolean("tcpFastOpen", bean.tcpFastOpen)
        store.putBoolean("tcpMultiPath", bean.tcpMultiPath)
        store.putString("udpFragment", bean.udpFragment?.toString().orEmpty())
        store.putBoolean("disableTcpKeepAlive", bean.disableTcpKeepAlive)
        store.putString("tcpKeepAlive", bean.tcpKeepAlive)
        store.putString("tcpKeepAliveInterval", bean.tcpKeepAliveInterval)
        store.putString("tlsCurvePreferences", bean.tlsCurvePreferences)
        store.putString("tlsCertificatePublicKeySha256", bean.tlsCertificatePublicKeySha256)
        store.putString("tlsXrayCertificateSha256", bean.tlsXrayCertificateSha256)
        store.putString("tlsClientCertificate", bean.tlsClientCertificate)
        store.putString("tlsClientKey", bean.tlsClientKey)
        store.putString("echQueryServerName", bean.echQueryServerName)
        store.putString("tlsHandshakeTimeout", bean.tlsHandshakeTimeout)
        store.putString("quicIdleTimeout", bean.quicIdleTimeout)
        store.putString("quicKeepAlivePeriod", bean.quicKeepAlivePeriod)
        store.putString("quicStreamReceiveWindow", bean.quicStreamReceiveWindow.takeIf { it > 0 }?.toString().orEmpty())
        store.putString("quicConnectionReceiveWindow", bean.quicConnectionReceiveWindow.takeIf { it > 0 }?.toString().orEmpty())
        store.putString("quicMaxConcurrentStreams", bean.quicMaxConcurrentStreams.takeIf { it > 0 }?.toString().orEmpty())
        store.putString("quicInitialPacketSize", bean.quicInitialPacketSize.takeIf { it > 0 }?.toString().orEmpty())
        store.putBoolean("quicDisablePathMtuDiscovery", bean.quicDisablePathMtuDiscovery)
    }

    private fun readSharedOptionsFromCache(bean: AbstractBean) {
        val store = DataStore.profileCacheStore
        bean.tcpFastOpen = store.getBoolean("tcpFastOpen", false)
        bean.tcpMultiPath = store.getBoolean("tcpMultiPath", false)
        bean.udpFragment = when (store.getString("udpFragment")) {
            "true" -> true
            "false" -> false
            else -> null
        }
        bean.disableTcpKeepAlive = store.getBoolean("disableTcpKeepAlive", false)
        bean.tcpKeepAlive = store.getString("tcpKeepAlive").orEmpty()
        bean.tcpKeepAliveInterval = store.getString("tcpKeepAliveInterval").orEmpty()
        bean.tlsCurvePreferences = store.getString("tlsCurvePreferences").orEmpty()
        bean.tlsCertificatePublicKeySha256 = store.getString("tlsCertificatePublicKeySha256").orEmpty()
        bean.tlsXrayCertificateSha256 = store.getString("tlsXrayCertificateSha256").orEmpty()
        bean.tlsClientCertificate = store.getString("tlsClientCertificate").orEmpty()
        bean.tlsClientKey = store.getString("tlsClientKey").orEmpty()
        bean.echQueryServerName = store.getString("echQueryServerName").orEmpty()
        bean.tlsHandshakeTimeout = store.getString("tlsHandshakeTimeout").orEmpty()
        bean.quicIdleTimeout = store.getString("quicIdleTimeout").orEmpty()
        bean.quicKeepAlivePeriod = store.getString("quicKeepAlivePeriod").orEmpty()
        bean.quicStreamReceiveWindow = store.getString("quicStreamReceiveWindow")?.toLongOrNull() ?: 0L
        bean.quicConnectionReceiveWindow = store.getString("quicConnectionReceiveWindow")?.toLongOrNull() ?: 0L
        bean.quicMaxConcurrentStreams = store.getString("quicMaxConcurrentStreams")?.toIntOrNull() ?: 0
        bean.quicInitialPacketSize = store.getString("quicInitialPacketSize")?.toIntOrNull() ?: 0
        bean.quicDisablePathMtuDiscovery = store.getBoolean("quicDisablePathMtuDiscovery", false)
    }

    private fun currentShareEntity(): ProxyEntity {
        val bean = (editingBean ?: error("Profile is not ready")).clone() as T
        bean.serializeFromCache()
        return if (DataStore.editingId == 0L) {
            ProxyEntity(groupId = DataStore.editingGroup).putBean(bean)
        } else {
            (proxyEntity?.copy() ?: error("Profile no longer exists")).putBean(bean)
        }
    }

    private fun exportSharedConfiguration(entity: ProxyEntity) {
        val (configuration, fileName) = entity.exportConfig()
        pendingSharedConfiguration = configuration
        try {
            exportSharedConfigurationLauncher.launch(fileName)
        } catch (_: ActivityNotFoundException) {
            pendingSharedConfiguration = null
            Toast.makeText(this, R.string.file_manager_missing, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            pendingSharedConfiguration = null
            Toast.makeText(this, R.string.file_manager_missing, Toast.LENGTH_LONG).show()
        }
    }
}
