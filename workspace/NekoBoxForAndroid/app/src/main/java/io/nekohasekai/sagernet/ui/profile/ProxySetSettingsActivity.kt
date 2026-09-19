package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.profileCardType
import io.nekohasekai.sagernet.utils.ProfileCountryResolver
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.internal.decodeEmbeddedProfiles
import io.nekohasekai.sagernet.fmt.internal.filterInsecureProfiles
import io.nekohasekai.sagernet.fmt.internal.hasEmbeddedProfiles
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.ProfileShareCapabilities
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.ui.bindProfileSecurity
import io.nekohasekai.sagernet.widget.ListListener
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class ProxySetSettingsActivity : ProfileSettingsActivity<ProxySetBean>(R.layout.layout_proxy_set_settings) {

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

    override fun createEntity() = ProxySetBean()

    private val proxyList = ArrayList<ProxyEntity>()
    private var hasEmbeddedMembers = false
    private val testingEmbeddedIds = mutableSetOf<Long>()
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
        if (hasEmbeddedMembers) {
            proxyList.clear()
            proxyList += decodeEmbeddedProfiles()
        }
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
        mode = if (hasEmbeddedMembers) {
            ProxySetBean.MODE_URL_TEST
        } else {
            DataStore.profileCacheStore.getString(KEY_MODE)?.toIntOrNull() ?: ProxySetBean.MODE_SELECTOR
        }
        defaultOutbound = DataStore.profileCacheStore.getLong(KEY_DEFAULT_OUTBOUND) ?: 0L
        interruptExistConnections = DataStore.profileCacheStore.getBoolean(KEY_INTERRUPT) ?: false
        testURL = DataStore.profileCacheStore.getString(KEY_TEST_URL) ?: CONNECTION_TEST_URL
        testInterval = DataStore.profileCacheStore.getString(KEY_TEST_INTERVAL) ?: "3m"
        testIdleTimeout = DataStore.profileCacheStore.getString(KEY_TEST_IDLE_TIMEOUT) ?: "3m"
        testTolerance = DataStore.profileCacheStore.getString(KEY_TEST_TOLERANCE)?.toIntOrNull() ?: 50
        type = if (hasEmbeddedMembers) {
            ProxySetBean.TYPE_LIST
        } else {
            DataStore.profileCacheStore.getString(KEY_TYPE)?.toIntOrNull() ?: ProxySetBean.TYPE_LIST
        }
        groupId = DataStore.profileCacheStore.getString(KEY_GROUP)?.toLongOrNull() ?: 0L
        groupFilterNotRegex = DataStore.profileCacheStore.getString(KEY_GROUP_FILTER) ?: ""
        skipInsecureProfiles =
            DataStore.profileCacheStore.getBoolean(KEY_SKIP_INSECURE) ?: false
        if (!hasEmbeddedMembers) proxies = proxyList.map { it.id }
        initializeDefaultValues()
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.proxy_set_preferences)
        findPreference<EditTextPreference>(KEY_TEST_TOLERANCE)!!.setOnBindEditTextListener {
            it.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        defaultOutboundPreference = findPreference(KEY_DEFAULT_OUTBOUND)
        defaultOutboundPreference?.setOnPreferenceClickListener {
            showDefaultOutboundDialog(it)
            true
        }

        val mode = findPreference<SimpleMenuPreference>(KEY_MODE)!!
        val type = findPreference<SimpleMenuPreference>(KEY_TYPE)!!
        mode.isEnabled = !hasEmbeddedMembers
        type.isEnabled = !hasEmbeddedMembers
        fun updateVisibility(
            modeValue: Any? = mode.value,
            typeValue: Any? = type.value,
        ) {
            val isUrlTest = modeValue?.toString()?.toIntOrNull() == ProxySetBean.MODE_URL_TEST
            val isGroup = typeValue?.toString()?.toIntOrNull() == ProxySetBean.TYPE_GROUP
            findPreference<Preference>(KEY_DEFAULT_OUTBOUND)!!.isVisible = !isUrlTest
            findPreference<Preference>(KEY_TEST_URL)!!.isVisible = isUrlTest
            findPreference<Preference>(KEY_TEST_INTERVAL)!!.isVisible = isUrlTest
            findPreference<Preference>(KEY_TEST_IDLE_TIMEOUT)!!.isVisible = isUrlTest
            findPreference<Preference>(KEY_TEST_TOLERANCE)!!.isVisible = isUrlTest
            findPreference<Preference>(KEY_GROUP)!!.isVisible = isGroup
            findPreference<Preference>(KEY_GROUP_FILTER)!!.isVisible = isGroup
            updateDefaultOutboundSummary()
            updatePanelVisibility(isGroup)
        }

        findPreference<Preference>(KEY_GROUP)!!.setOnPreferenceChangeListener { _, _ ->
            this@ProxySetSettingsActivity.window.decorView.post { updateDefaultOutboundSummary() }
            true
        }
        findPreference<Preference>(KEY_GROUP_FILTER)!!.setOnPreferenceChangeListener { _, _ ->
            this@ProxySetSettingsActivity.window.decorView.post { updateDefaultOutboundSummary() }
            true
        }
        findPreference<Preference>(KEY_SKIP_INSECURE)!!.setOnPreferenceChangeListener { _, _ ->
            this@ProxySetSettingsActivity.window.decorView.post { updateDefaultOutboundSummary() }
            true
        }
        mode.setOnPreferenceChangeListener { _, newValue ->
            updateVisibility(modeValue = newValue)
            true
        }
        type.setOnPreferenceChangeListener { _, newValue ->
            updateVisibility(typeValue = newValue)
            true
        }
        updateVisibility()
    }

    private lateinit var configurationList: RecyclerView
    private lateinit var configurationAdapter: ProxiesAdapter
    private var defaultOutboundPreference: Preference? = null

    private fun updatePanelVisibility(isGroup: Boolean) {
        if (!::configurationList.isInitialized) return
        val divider = findViewById<View>(R.id.list_cell)

        configurationList.isVisible = hasEmbeddedMembers || !isGroup
        divider.isVisible = hasEmbeddedMembers || !isGroup
    }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar!!.setTitle(R.string.proxy_set_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_scroll), ListListener)
        configurationList = findViewById(R.id.configuration_list)
        configurationList.isNestedScrollingEnabled = false
        configurationList.layoutManager = FixedLinearLayoutManager(configurationList)
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
                if (!hasEmbeddedMembers && viewHolder is ProfileHolder) super.getSwipeDirs(recyclerView, viewHolder) else 0

            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
                if (!hasEmbeddedMembers && viewHolder is ProfileHolder) super.getDragDirs(recyclerView, viewHolder) else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target !is ProfileHolder) false else {
                    configurationAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION) return
                if (DataStore.confirmProfileDelete) {
                    var confirmed = false
                    MaterialAlertDialogBuilder(this@ProxySetSettingsActivity)
                        .setTitle(R.string.delete_confirm_prompt)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            confirmed = true
                            configurationAdapter.remove(index)
                        }
                        .setNegativeButton(R.string.no, null)
                        .setOnDismissListener {
                            if (!confirmed) configurationAdapter.notifyItemChanged(index)
                        }
                        .show()
                } else {
                    configurationAdapter.remove(index)
                }
            }
        }).attachToRecyclerView(configurationList)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        view.rootView.findViewById<RecyclerView>(R.id.recycler_view).apply {
            (layoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                layoutParams = this
            }
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        updatePanelVisibility(
            DataStore.profileCacheStore.getString(KEY_TYPE)?.toIntOrNull() == ProxySetBean.TYPE_GROUP
        )
        runOnDefaultDispatcher {
            configurationAdapter.reload()
        }
    }

    private fun currentCollectType(): Int {
        return DataStore.profileCacheStore.getString(KEY_TYPE)?.toIntOrNull() ?: ProxySetBean.TYPE_LIST
    }

    private fun currentDefaultOutbound(): Long {
        return DataStore.profileCacheStore.getLong(KEY_DEFAULT_OUTBOUND) ?: 0L
    }

    private fun selectableDefaultOutbounds(): List<ProxyEntity> {
        val skipInsecureProfiles =
            DataStore.profileCacheStore.getBoolean(KEY_SKIP_INSECURE) ?: false
        val filterBean = ProxySetBean().apply {
            this.skipInsecureProfiles = skipInsecureProfiles
        }
        if (currentCollectType() != ProxySetBean.TYPE_GROUP) {
            return filterBean.filterInsecureProfiles(proxyList, DataStore.globalAllowInsecure)
        }
        val groupId = DataStore.profileCacheStore.getString(KEY_GROUP)?.toLongOrNull() ?: 0L
        val filter = DataStore.profileCacheStore.getString(KEY_GROUP_FILTER)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { it.toRegex() }.getOrNull() }
        val profiles = SagerDatabase.proxyDao.getByGroup(groupId).filter { profile ->
            if (profile.id == DataStore.editingId) return@filter false
            if (profile.type == ProxyEntity.TYPE_PROXY_SET) return@filter false
            if (profile.type == ProxyEntity.TYPE_CHAIN) return@filter false
            if (profile.containsMasterDnsVPN()) return@filter false
            if (profile.containsByeDPI()) return@filter false
            filter == null || filter.containsMatchIn(profile.displayName())
        }
        return filterBean.filterInsecureProfiles(profiles, DataStore.globalAllowInsecure)
    }

    private fun updateDefaultOutboundSummary() {
        val preference = defaultOutboundPreference ?: return
        val defaultOutbound = currentDefaultOutbound()
        preference.summary = selectableDefaultOutbounds()
            .firstOrNull { it.id == defaultOutbound }
            ?.displayName()
            ?: getString(R.string.none)
    }

    private fun showDefaultOutboundDialog(preference: Preference) {
        val profiles = selectableDefaultOutbounds()
        val values = listOf(0L) + profiles.map { it.id }
        val labels = listOf(getString(R.string.none)) + profiles.map { it.displayName() }
        val checked = values.indexOf(currentDefaultOutbound()).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.proxy_set_default_outbound)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                DataStore.profileCacheStore.putLong(KEY_DEFAULT_OUTBOUND, values[which])
                DataStore.dirty = true
                updateDefaultOutboundSummary()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        suspend fun reload() {
            if (hasEmbeddedMembers) {
                onMainDispatcher {
                    notifyDataSetChanged()
                    updateDefaultOutboundSummary()
                }
                return
            }
            val idList = DataStore.serverProtocol.split(",")
                .mapNotNull { it.takeIf { value -> value.isNotBlank() }?.toLong() }
            if (idList.isNotEmpty()) {
                val profiles = ProfileManager.getProfiles(idList).associateBy { it.id }
                proxyList.clear()
                for (id in idList) {
                    proxyList.add(profiles[id] ?: continue)
                }
            }
            onMainDispatcher {
                notifyDataSetChanged()
                updateDefaultOutboundSummary()
            }
        }

        fun move(from: Int, to: Int) {
            val toMove = proxyList[to - 1]
            proxyList[to - 1] = proxyList[from - 1]
            proxyList[from - 1] = toMove
            notifyItemMoved(from, to)
            updateDefaultOutboundSummary()
            DataStore.dirty = true
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            updateDefaultOutboundSummary()
            DataStore.dirty = true
        }

        private fun profileIndex(position: Int) = position - if (hasEmbeddedMembers) 0 else 1

        override fun getItemId(position: Int) =
            if (!hasEmbeddedMembers && position == 0) 0 else proxyList[profileIndex(position)].id
        override fun getItemViewType(position: Int) = if (!hasEmbeddedMembers && position == 0) 0 else 1
        override fun getItemCount() = proxyList.size + if (hasEmbeddedMembers) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
            } else {
                ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) holder.bind() else if (holder is ProfileHolder) holder.bind(proxyList[profileIndex(position)])
        }
    }

    private fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId) return false
        if (profile.type == ProxyEntity.TYPE_PROXY_SET) return false
        if (profile.containsMasterDnsVPN()) return false
        if (profile.containsByeDPI()) return false
        if (proxyList.any { it.id == profile.id }) return false
        for (entity in proxyList) {
            if (testProfileContains(entity, profile)) return false
        }
        return true
    }

    private fun testProfileContains(profile: ProxyEntity, anotherProfile: ProxyEntity): Boolean {
        if (profile.type != ProxyEntity.TYPE_CHAIN || anotherProfile.type != ProxyEntity.TYPE_CHAIN) return false
        if (profile.id == anotherProfile.id) return true
        val proxies = profile.chainBean!!.proxies
        if (proxies.contains(anotherProfile.id)) return true
        if (proxies.isNotEmpty()) {
            for (entity in ProfileManager.getProfiles(proxies)) {
                if (testProfileContains(entity, anotherProfile)) return true
            }
        }
        return false
    }

    private var replacing = 0

    private val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                DataStore.dirty = true
                val profile = ProfileManager.getProfile(data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0))!!
                if (!testProfileAllowed(profile)) {
                    onMainDispatcher {
                        MaterialAlertDialogBuilder(this@ProxySetSettingsActivity)
                            .setTitle(R.string.invalid_profile)
                            .setMessage(
                                when {
                                    profile.type == ProxyEntity.TYPE_MASTERDNSVPN ->
                                        R.string.masterdnsvpn_proxy_set_error
                                    profile.containsMasterDnsVPN() -> R.string.masterdnsvpn_chain_error
                                    profile.containsByeDPI() -> R.string.byedpi_proxy_set_error
                                    else -> R.string.circular_reference_sum
                                }
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                } else {
                    configurationList.post {
                        if (replacing != 0) {
                            proxyList[replacing - 1] = profile
                            configurationAdapter.notifyItemChanged(replacing)
                        } else {
                            proxyList.add(profile)
                            configurationAdapter.notifyItemInserted(proxyList.size)
                        }
                        updateDefaultOutboundSummary()
                    }
                }
            }
        }

    inner class AddHolder(private val binding: LayoutAddEntityBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener {
                replacing = 0
                selectProfileForAdd.launch(Intent(this@ProxySetSettingsActivity, ProfileSelectActivity::class.java))
            }
        }
    }

    inner class ProfileHolder(private val binding: LayoutProfileBinding) : RecyclerView.ViewHolder(binding.root) {
        private val profileCard = binding.root
        private val defaultCardStrokeColor = profileCard.strokeColorStateList
        private val defaultCardStrokeWidth = profileCard.strokeWidth
        private val profileName = binding.profileName
        private val profileType = binding.profileType
        private val trafficText: TextView = binding.trafficText
        private val profileStatus: TextView = binding.profileStatus
        private val editButton = binding.edit
        private val urlTestButton = binding.urlTest
        private val shareLayout = binding.share

        fun bind(proxyEntity: ProxyEntity) {
            val countryBadgeVisible = binding.countryBadge.bind(proxyEntity)
            profileName.text =
                ProfileCountryResolver.presentationName(proxyEntity, countryBadgeVisible)
            profileType.text = proxyEntity.profileCardType(DataStore.shortProfileProtocolInfo)
            profileType.setTextColor(getProtocolColor(proxyEntity.type))
            profileCard.bindProfileSecurity(
                proxyEntity,
                defaultCardStrokeColor,
                defaultCardStrokeWidth,
            )
            trafficText.isVisible = false
            editButton.isVisible = !hasEmbeddedMembers
            if (!hasEmbeddedMembers) {
                editButton.setOnClickListener {
                    replacing = bindingAdapterPosition
                    selectProfileForAdd.launch(Intent(this@ProxySetSettingsActivity, ProfileSelectActivity::class.java).apply {
                        putExtra(ProfileSelectActivity.EXTRA_SELECTED, proxyEntity)
                    })
                }
            }
            urlTestButton.isVisible = hasEmbeddedMembers
            urlTestButton.isEnabled = !UrlTest.isUnsupportedProfile(proxyEntity) && proxyEntity.id !in testingEmbeddedIds
            urlTestButton.setOnClickListener { testEmbeddedProfile(proxyEntity) }
            shareLayout.isVisible = hasEmbeddedMembers
            shareLayout.setOnClickListener { showEmbeddedShareMenu(it, proxyEntity) }
            profileStatus.text = when {
                proxyEntity.id in testingEmbeddedIds -> getString(R.string.connection_test_testing)
                proxyEntity.status == 1 -> getString(R.string.available, proxyEntity.ping)
                proxyEntity.status >= 2 -> proxyEntity.error.orEmpty()
                else -> ""
            }
        }
    }

    private fun testEmbeddedProfile(profile: ProxyEntity) {
        if (!testingEmbeddedIds.add(profile.id)) return
        configurationAdapter.notifyItemChanged(proxyList.indexOf(profile))
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
                onMainDispatcher {
                    testingEmbeddedIds.remove(profile.id)
                    val index = proxyList.indexOf(profile)
                    if (index >= 0) configurationAdapter.notifyItemChanged(index)
                }
            }
        }
    }

    private fun showEmbeddedShareMenu(anchor: View, profile: ProxyEntity) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)
        val capabilities = ProfileShareCapabilities.from(profile)
        if (!capabilities.links) {
            popup.menu.removeItem(R.id.action_group_qr)
            popup.menu.removeItem(R.id.action_group_clipboard)
        } else if (!capabilities.standardLinks) {
            popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
            popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(R.id.action_standard_clipboard)
        }
        if (!capabilities.configuration) popup.menu.removeItem(R.id.action_group_configuration)
        popup.setOnMenuItemClickListener { shareEmbeddedProfile(it, profile) }
        popup.show()
    }

    private fun shareEmbeddedProfile(item: MenuItem, profile: ProxyEntity): Boolean = try {
        val content = when (item.itemId) {
            R.id.action_standard_qr, R.id.action_standard_clipboard -> profile.toStdLink()
            R.id.action_universal_qr, R.id.action_universal_clipboard -> profile.requireBean().toUniversalLink()
            R.id.action_config_export_clipboard -> profile.exportConfig().first
            R.id.action_config_export_file -> null
            else -> return false
        }
        when (item.itemId) {
            R.id.action_standard_qr, R.id.action_universal_qr ->
                QRCodeDialog(content!!, profile.displayName()).showAllowingStateLoss(supportFragmentManager)
            R.id.action_standard_clipboard, R.id.action_universal_clipboard,
            R.id.action_config_export_clipboard -> Toast.makeText(
                this,
                if (SagerNet.trySetPrimaryClip(content!!)) R.string.action_export_msg else R.string.action_export_err,
                Toast.LENGTH_SHORT,
            ).show()
            R.id.action_config_export_file -> {
                val (configuration, fileName) = profile.exportConfig()
                pendingSharedConfiguration = configuration
                exportSharedConfiguration.launch(fileName)
            }
        }
        true
    } catch (error: Exception) {
        Logs.w(error)
        Toast.makeText(this, error.readableMessage, Toast.LENGTH_LONG).show()
        true
    }
}
