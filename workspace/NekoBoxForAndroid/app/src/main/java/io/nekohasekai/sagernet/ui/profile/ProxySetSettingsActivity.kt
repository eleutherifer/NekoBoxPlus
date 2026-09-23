package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.profileCardType
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
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
    }

    override fun createEntity() = ProxySetBean()

    private val proxyList = ArrayList<ProxyEntity>()

    override fun ProxySetBean.init() {
        DataStore.profileName = name
        DataStore.serverProtocol = proxies.joinToString(",")
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
    }

    override fun ProxySetBean.serialize() {
        name = DataStore.profileName
        mode = DataStore.profileCacheStore.getString(KEY_MODE)?.toIntOrNull() ?: ProxySetBean.MODE_SELECTOR
        defaultOutbound = DataStore.profileCacheStore.getLong(KEY_DEFAULT_OUTBOUND) ?: 0L
        interruptExistConnections = DataStore.profileCacheStore.getBoolean(KEY_INTERRUPT) ?: false
        testURL = DataStore.profileCacheStore.getString(KEY_TEST_URL) ?: CONNECTION_TEST_URL
        testInterval = DataStore.profileCacheStore.getString(KEY_TEST_INTERVAL) ?: "3m"
        testIdleTimeout = DataStore.profileCacheStore.getString(KEY_TEST_IDLE_TIMEOUT) ?: "3m"
        testTolerance = DataStore.profileCacheStore.getString(KEY_TEST_TOLERANCE)?.toIntOrNull() ?: 50
        type = DataStore.profileCacheStore.getString(KEY_TYPE)?.toIntOrNull() ?: ProxySetBean.TYPE_LIST
        groupId = DataStore.profileCacheStore.getString(KEY_GROUP)?.toLongOrNull() ?: 0L
        groupFilterNotRegex = DataStore.profileCacheStore.getString(KEY_GROUP_FILTER) ?: ""
        proxies = proxyList.map { it.id }
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

        configurationList.isVisible = !isGroup
        divider.isVisible = !isGroup
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
                if (viewHolder is ProfileHolder) super.getSwipeDirs(recyclerView, viewHolder) else 0

            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
                if (viewHolder is ProfileHolder) super.getDragDirs(recyclerView, viewHolder) else 0

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
        if (currentCollectType() != ProxySetBean.TYPE_GROUP) {
            return proxyList
        }
        val groupId = DataStore.profileCacheStore.getString(KEY_GROUP)?.toLongOrNull() ?: 0L
        val filter = DataStore.profileCacheStore.getString(KEY_GROUP_FILTER)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { it.toRegex() }.getOrNull() }
        return SagerDatabase.proxyDao.getByGroup(groupId).filter { profile ->
            if (profile.id == DataStore.editingId) return@filter false
            if (profile.type == ProxyEntity.TYPE_PROXY_SET) return@filter false
            if (profile.type == ProxyEntity.TYPE_CHAIN) return@filter false
            if (profile.containsMasterDnsVPN()) return@filter false
            if (profile.containsByeDPI()) return@filter false
            filter == null || filter.containsMatchIn(profile.displayName())
        }
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

        override fun getItemId(position: Int) = if (position == 0) 0 else proxyList[position - 1].id
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun getItemCount() = proxyList.size + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
            } else {
                ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) holder.bind() else if (holder is ProfileHolder) holder.bind(proxyList[position - 1])
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

    inner class ProfileHolder(binding: LayoutProfileBinding) : RecyclerView.ViewHolder(binding.root) {
        private val profileCard = binding.root
        private val defaultCardStrokeColor = profileCard.strokeColorStateList
        private val defaultCardStrokeWidth = profileCard.strokeWidth
        private val profileName = binding.profileName
        private val profileType = binding.profileType
        private val trafficText: TextView = binding.trafficText
        private val editButton = binding.edit
        private val urlTestButton = binding.urlTest
        private val shareLayout = binding.share

        fun bind(proxyEntity: ProxyEntity) {
            profileName.text = proxyEntity.displayName()
            profileType.text = proxyEntity.profileCardType()
            profileType.setTextColor(getProtocolColor(proxyEntity.type))
            profileCard.bindProfileSecurity(
                proxyEntity,
                defaultCardStrokeColor,
                defaultCardStrokeWidth,
            )
            trafficText.isVisible = false
            editButton.setOnClickListener {
                replacing = bindingAdapterPosition
                selectProfileForAdd.launch(Intent(this@ProxySetSettingsActivity, ProfileSelectActivity::class.java).apply {
                    putExtra(ProfileSelectActivity.EXTRA_SELECTED, proxyEntity)
                })
            }
            urlTestButton.isVisible = false
            shareLayout.isVisible = false
        }
    }
}
