package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.fmt.supportsSharedTLSFieldInjection
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.ProfileShareCapabilities
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.showMaterialEditTextPreferenceDialog
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import moe.matsuri.nb4a.ui.MaterialSwitchPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import kotlin.properties.Delegates

@Suppress("UNCHECKED_CAST")
abstract class ProfileSettingsActivity<T : AbstractBean>(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId), OnPreferenceDataStoreChangeListener {

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as ProfileSettingsActivity<*>).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class ProfileIdArg(val profileId: Long, val groupId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_confirm_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    ProfileManager.deleteProfile(arg.groupId, arg.profileId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_IS_SUBSCRIPTION = "sub"
    }

    abstract fun createEntity(): T
    abstract fun T.init()
    abstract fun T.serialize()

    val proxyEntity by lazy { SagerDatabase.proxyDao.getById(DataStore.editingId) }
    private var editingBean: T? = null
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
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        Toast.makeText(
                            this@ProfileSettingsActivity,
                            e.readableMessage,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.profile_config)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
            isSubscription = intent.getBooleanExtra(EXTRA_IS_SUBSCRIPTION, false)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    DataStore.editingGroup = DataStore.selectedGroupForImport()
                    createEntity().applyDefaultValues().also {
                        editingBean = it
                        writeSharedOptionsToCache(it)
                        it.init()
                    }
                } else {
                    if (proxyEntity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    DataStore.editingGroup = proxyEntity!!.groupId
                    (proxyEntity!!.requireBean() as T).also {
                        editingBean = it
                        writeSharedOptionsToCache(it)
                        it.init()
                    }
                }

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()
                    invalidateOptionsMenu()
                }
            }


        }

    }

    open suspend fun saveAndExit() {

        val editingId = DataStore.editingId
        if (editingId == 0L) {
            val editingGroup = DataStore.editingGroup
            ProfileManager.createProfile(editingGroup, createEntity().apply {
                serialize()
                readSharedOptionsFromCache(this)
            })
        } else {
            if (proxyEntity == null) {
                finish()
                return
            }
            if (proxyEntity!!.id == DataStore.selectedProxy) {
                SagerNet.stopService()
            }
            ProfileManager.updateEditedProfile(proxyEntity!!.apply {
                (requireBean() as T).apply {
                    serialize()
                    readSharedOptionsFromCache(this)
                }
            })
        }
        finish()

    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        menu.findItem(R.id.action_move)?.apply {
            if (DataStore.editingId != 0L // not new profile
                && SagerDatabase.groupDao.getById(DataStore.editingGroup)?.type == GroupType.BASIC // not in subscription group
                && SagerDatabase.groupDao.allGroups()
                    .filter { it.type == GroupType.BASIC }.size > 1 // have other basic group
            ) isVisible = true
        }
        menu.findItem(R.id.action_create_shortcut)?.apply {
            if (Build.VERSION.SDK_INT >= 26 && DataStore.editingId != 0L) {
                isVisible = true // not new profile
            }
        }
        // shared menu item
        menu.findItem(R.id.action_custom_outbound_json)?.isVisible = true
        menu.findItem(R.id.action_custom_config_json)?.isVisible = true
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val capabilities = editingBean?.let {
            ProfileShareCapabilities.from(ProxyEntity().putBean(it))
        }
        menu.findItem(R.id.action_share_server)?.isVisible = capabilities != null
        menu.findItem(R.id.action_group_qr)?.isVisible = capabilities?.links == true
        menu.findItem(R.id.action_group_clipboard)?.isVisible = capabilities?.links == true
        menu.findItem(R.id.action_standard_qr)?.isVisible = capabilities?.standardLinks == true
        menu.findItem(R.id.action_standard_clipboard)?.isVisible =
            capabilities?.standardLinks == true
        menu.findItem(R.id.action_group_configuration)?.isVisible =
            capabilities?.configuration == true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = child.onOptionsItemSelected(item)

    override fun onBackPressed() {
        if (DataStore.dirty) UnsavedChangesDialogFragment().apply { key() }
            .show(supportFragmentManager, null) else super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    abstract fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    )

    private fun supportsSharedDialOptions(bean: AbstractBean): Boolean =
        bean !is ConfigBean && bean !is NekoBean && bean !is ChainBean && bean !is ProxySetBean

    private fun supportsSharedTLSOptions(bean: AbstractBean): Boolean =
        bean.supportsSharedTLSFieldInjection() && when (bean) {
            is StandardV2RayBean,
            is HysteriaBean,
            is TuicBean,
            is TrustTunnelBean,
            is TrojanGoBean,
            is AnyTLSBean,
            is JuicityBean,
            is NaiveBean,
            -> true
            else -> false
        }

    private fun writeSharedOptionsToCache(bean: AbstractBean) {
        DataStore.profileCacheStore.putBoolean("tcpFastOpen", bean.tcpFastOpen)
        DataStore.profileCacheStore.putBoolean("tcpMultiPath", bean.tcpMultiPath)
        DataStore.profileCacheStore.putString(
            "udpFragment",
            bean.udpFragment?.toString().orEmpty(),
        )
        DataStore.profileCacheStore.putBoolean("disableTcpKeepAlive", bean.disableTcpKeepAlive)
        DataStore.profileCacheStore.putString("tcpKeepAlive", bean.tcpKeepAlive)
        DataStore.profileCacheStore.putString("tcpKeepAliveInterval", bean.tcpKeepAliveInterval)
        DataStore.profileCacheStore.putString("tlsCurvePreferences", bean.tlsCurvePreferences)
        DataStore.profileCacheStore.putString(
            "tlsCertificatePublicKeySha256",
            bean.tlsCertificatePublicKeySha256,
        )
        DataStore.profileCacheStore.putString("tlsXrayCertificateSha256", bean.tlsXrayCertificateSha256)
        DataStore.profileCacheStore.putString("tlsClientCertificate", bean.tlsClientCertificate)
        DataStore.profileCacheStore.putString("tlsClientKey", bean.tlsClientKey)
        DataStore.profileCacheStore.putString("echQueryServerName", bean.echQueryServerName)
    }

    private fun readSharedOptionsFromCache(bean: AbstractBean) {
        bean.tcpFastOpen = DataStore.profileCacheStore.getBoolean("tcpFastOpen", false)
        bean.tcpMultiPath = DataStore.profileCacheStore.getBoolean("tcpMultiPath", false)
        bean.udpFragment = when (DataStore.profileCacheStore.getString("udpFragment")) {
            "true" -> true
            "false" -> false
            else -> null
        }
        bean.disableTcpKeepAlive = DataStore.profileCacheStore.getBoolean("disableTcpKeepAlive", false)
        bean.tcpKeepAlive = DataStore.profileCacheStore.getString("tcpKeepAlive").orEmpty()
        bean.tcpKeepAliveInterval = DataStore.profileCacheStore.getString("tcpKeepAliveInterval").orEmpty()
        bean.tlsCurvePreferences = DataStore.profileCacheStore.getString("tlsCurvePreferences").orEmpty()
        bean.tlsCertificatePublicKeySha256 =
            DataStore.profileCacheStore.getString("tlsCertificatePublicKeySha256").orEmpty()
        bean.tlsXrayCertificateSha256 =
            DataStore.profileCacheStore.getString("tlsXrayCertificateSha256").orEmpty()
        bean.tlsClientCertificate = DataStore.profileCacheStore.getString("tlsClientCertificate").orEmpty()
        bean.tlsClientKey = DataStore.profileCacheStore.getString("tlsClientKey").orEmpty()
        bean.echQueryServerName = DataStore.profileCacheStore.getString("echQueryServerName").orEmpty()
    }

    private fun currentShareEntity(): ProxyEntity {
        val bean = (editingBean ?: error("Profile is not ready")).clone() as T
        bean.serialize()
        readSharedOptionsFromCache(bean)
        val entity = if (DataStore.editingId == 0L) {
            ProxyEntity(groupId = DataStore.editingGroup)
        } else {
            proxyEntity?.copy() ?: error("Profile no longer exists")
        }
        return entity.putBean(bean)
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

    private fun PreferenceFragmentCompat.addSharedOptions() {
        val bean = editingBean ?: return
        if (supportsSharedDialOptions(bean)) {
            val category = PreferenceCategory(requireContext()).apply {
                title = getString(R.string.sing_box_dial_options)
            }
            preferenceScreen.addPreference(category)
            category.addPreference(MaterialSwitchPreference(requireContext()).apply {
                key = "tcpFastOpen"
                title = getString(R.string.tcp_fast_open)
                icon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_baseline_speed_24)
            })
            category.addPreference(MaterialSwitchPreference(requireContext()).apply {
                key = "tcpMultiPath"
                title = getString(R.string.multipath_tcp)
                icon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_baseline_multiple_stop_24)
            })
            category.addPreference(SimpleMenuPreference(requireContext()).apply {
                key = "udpFragment"
                title = getString(R.string.udp_fragmentation)
                icon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_baseline_call_split_24)
                entries = arrayOf(
                    getString(R.string.connection_option_default),
                    getString(R.string.connection_option_enabled),
                    getString(R.string.connection_option_disabled),
                )
                entryValues = arrayOf("", "true", "false")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
            })
            category.addPreference(MaterialSwitchPreference(requireContext()).apply {
                key = "disableTcpKeepAlive"
                title = getString(R.string.disable_tcp_keep_alive)
                icon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_baseline_timer_24)
            })
            category.addPreference(EditTextPreference(requireContext()).apply {
                key = "tcpKeepAlive"
                title = getString(R.string.tcp_keep_alive)
                icon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_baseline_timer_24)
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            })
            category.addPreference(EditTextPreference(requireContext()).apply {
                key = "tcpKeepAliveInterval"
                title = getString(R.string.tcp_keep_alive_interval)
                icon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_baseline_timelapse_24)
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            })
        }
        if (supportsSharedTLSOptions(bean)) {
            val category = PreferenceCategory(requireContext()).apply {
                title = getString(R.string.sing_box_tls_13_options)
            }
            preferenceScreen.addPreference(category)
            fun addText(keyValue: String, titleValue: Int, iconValue: Int, secret: Boolean = false) {
                category.addPreference(EditTextPreference(requireContext()).apply {
                    key = keyValue
                    title = getString(titleValue)
                    icon = AppCompatResources.getDrawable(requireContext(), iconValue)
                    summaryProvider = if (secret) PasswordSummaryProvider
                    else EditTextPreference.SimpleSummaryProvider.getInstance()
                })
            }
            addText("tlsCurvePreferences", R.string.tls_curve_preferences, R.drawable.ic_baseline_multiple_stop_24)
            if (bean !is TrustTunnelBean) {
                addText(
                    "tlsCertificatePublicKeySha256",
                    R.string.tls_certificate_public_key_sha256,
                    R.drawable.ic_baseline_fingerprint_24,
                )
                addText(
                    "tlsXrayCertificateSha256",
                    R.string.tls_xray_certificate_sha256,
                    R.drawable.ic_baseline_fingerprint_24,
                )
                addText("tlsClientCertificate", R.string.tls_client_certificate, R.drawable.ic_action_copyright)
                addText("tlsClientKey", R.string.tls_client_key, R.drawable.ic_baseline_vpn_key_24, true)
                addText("echQueryServerName", R.string.ech_query_server_name, R.drawable.ic_baseline_dns_24)
            }
        }
    }

    open fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
    }

    open fun PreferenceFragmentCompat.displayPreferenceDialog(preference: Preference): Boolean {
        return false
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: ProfileSettingsActivity<*>? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as ProfileSettingsActivity<*>).apply {
                    createPreferences(savedInstanceState, rootKey)
                    addSharedOptions()
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
                DataStore.dirty = false
                DataStore.profileCacheStore.registerChangeListener(this)
            }
        }

        var callbackCustom: ((String) -> Unit)? = null
        var callbackCustomOutbound: ((String) -> Unit)? = null

        val resultCallbackCustom = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            callbackCustom?.let { it(DataStore.serverCustom) }
        }

        val resultCallbackCustomOutbound = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            callbackCustomOutbound?.let { it(DataStore.serverCustomOutbound) }
        }

        @SuppressLint("CheckResult")
        override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
            R.id.action_standard_qr,
            R.id.action_universal_qr,
            R.id.action_standard_clipboard,
            R.id.action_universal_clipboard,
            R.id.action_config_export_clipboard,
            R.id.action_config_export_file,
            -> {
                try {
                    val host = activity ?: return true
                    val entity = host.currentShareEntity()
                    val content = when (item.itemId) {
                        R.id.action_standard_qr,
                        R.id.action_standard_clipboard,
                        -> entity.toStdLink()
                        R.id.action_universal_qr,
                        R.id.action_universal_clipboard,
                        -> entity.requireBean().toUniversalLink()
                        R.id.action_config_export_clipboard -> entity.exportConfig().first
                        else -> null
                    }
                    when (item.itemId) {
                        R.id.action_standard_qr,
                        R.id.action_universal_qr,
                        -> QRCodeDialog(content!!, entity.displayName())
                            .showAllowingStateLoss(parentFragmentManager)
                        R.id.action_standard_clipboard,
                        R.id.action_universal_clipboard,
                        R.id.action_config_export_clipboard,
                        -> Toast.makeText(
                            requireContext(),
                            if (SagerNet.trySetPrimaryClip(content!!)) {
                                R.string.action_export_msg
                            } else {
                                R.string.action_export_err
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                        R.id.action_config_export_file -> host.exportSharedConfiguration(entity)
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    Toast.makeText(requireContext(), e.readableMessage, Toast.LENGTH_LONG).show()
                }
                true
            }

            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else if (!DataStore.confirmProfileDelete) {
                    runOnDefaultDispatcher {
                        ProfileManager.deleteProfile(DataStore.editingGroup, DataStore.editingId)
                    }
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(
                            ProfileIdArg(
                                DataStore.editingId, DataStore.editingGroup
                            )
                        )
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

            R.id.action_custom_outbound_json -> {
                activity?.proxyEntity?.apply {
                    val bean = requireBean()
                    DataStore.serverCustomOutbound = bean.customOutboundJson
                    callbackCustomOutbound = { bean.customOutboundJson = it }
                    resultCallbackCustomOutbound.launch(
                        Intent(
                            requireContext(),
                            ConfigEditActivity::class.java
                        ).apply {
                            putExtra("key", Key.SERVER_CUSTOM_OUTBOUND)
                        })
                }
                true
            }

            R.id.action_custom_config_json -> {
                activity?.proxyEntity?.apply {
                    val bean = requireBean()
                    DataStore.serverCustom = bean.customConfigJson
                    callbackCustom = { bean.customConfigJson = it }
                    resultCallbackCustom.launch(
                        Intent(
                            requireContext(),
                            ConfigEditActivity::class.java
                        ).apply {
                            putExtra("key", Key.SERVER_CUSTOM)
                        })
                }
                true
            }

            R.id.action_create_shortcut -> {
                val activity = requireActivity() as ProfileSettingsActivity<*>
                val ent = activity.proxyEntity!!
                val shortcut = ShortcutInfoCompat.Builder(activity, "shortcut-profile-${ent.id}")
                    .setShortLabel(ent.displayName())
                    .setLongLabel(ent.displayName())
                    .setIcon(
                        IconCompat.createWithResource(
                            activity, R.drawable.ic_qu_shadowsocks_launcher
                        )
                    ).setIntent(Intent(
                        context, QuickToggleShortcut::class.java
                    ).apply {
                        action = Intent.ACTION_MAIN
                        putExtra("profile", ent.id)
                    }).build()
                ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)
            }

            R.id.action_move -> {
                val activity = requireActivity() as ProfileSettingsActivity<*>
                val view = LinearLayout(context).apply {
                    val ent = activity.proxyEntity!!
                    orientation = LinearLayout.VERTICAL

                    SagerDatabase.groupDao.allGroups()
                        .filter { it.type == GroupType.BASIC && it.id != ent.groupId }
                        .forEach { group ->
                            LayoutGroupItemBinding.inflate(layoutInflater, this, true).apply {
                                edit.isVisible = false
                                options.isVisible = false
                                groupName.text = group.displayName()
                                groupUpdate.text = getString(R.string.move)
                                groupUpdate.setOnClickListener {
                                    runOnDefaultDispatcher {
                                        val oldGroupId = ent.groupId
                                        val newGroupId = group.id
                                        ent.groupId = newGroupId
                                        ProfileManager.updateProfile(ent)
                                        GroupManager.postUpdate(oldGroupId) // reload
                                        GroupManager.postUpdate(newGroupId)
                                        DataStore.editingGroup = newGroupId // post switch animation
                                        runOnMainDispatcher {
                                            activity.finish()
                                        }
                                    }
                                }
                            }
                        }
                }
                val scrollView = ScrollView(context).apply {
                    addView(view)
                }
                MaterialAlertDialogBuilder(activity).setView(scrollView).show()
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

}
