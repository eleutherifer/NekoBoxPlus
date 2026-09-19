package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.*
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SpoofApp
import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.defaultSpoofUserAgent
import io.nekohasekai.sagernet.group.normalizeSpoofUserAgent
import io.nekohasekai.sagernet.group.shouldWarnAboutMissingSpoofHwid
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.routing.ProviderRoutingSource
import io.nekohasekai.sagernet.routing.RoutingPreviewPayloadStore
import io.nekohasekai.sagernet.routing.SubscriptionRoutingIntervals
import io.nekohasekai.sagernet.routing.SubscriptionRoutingRepository
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.OutboundPreference
import io.nekohasekai.sagernet.widget.UserAgentPreference
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.MaterialSwitchPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import moe.matsuri.nb4a.ui.showMaterialEditTextPreferenceDialog
import java.text.Collator
import java.util.Locale

@Suppress("UNCHECKED_CAST")
class GroupSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {
    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference

    fun ProxyGroup.init() {
        DataStore.groupName = name ?: ""
        DataStore.groupType = type
        DataStore.groupOrder = order
        DataStore.groupIsSelector = isSelector
        DataStore.groupForceUTLS = forceUTLS
        DataStore.groupEnableMux = enableMux
        DataStore.groupMuxType = muxType
        DataStore.groupMuxMode = muxMode
        DataStore.groupMuxConcurrency = muxConcurrency
        DataStore.groupMuxMaxConnections = muxMaxConnections
        DataStore.groupMuxMinStreams = muxMinStreams
        DataStore.groupMuxPadding = muxPadding
        DataStore.groupMuxBrutal = muxBrutal
        DataStore.groupMuxBrutalUpMbps = muxBrutalUpMbps
        DataStore.groupMuxBrutalDownMbps = muxBrutalDownMbps

        DataStore.frontProxy = frontProxy
        DataStore.landingProxy = landingProxy
        DataStore.frontProxyTmp =
            if (frontProxy >= 0) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.landingProxyTmp =
            if (landingProxy >= 0) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0

        val subscription = subscription ?: SubscriptionBean().applyDefaultValues()
        DataStore.subscriptionLink = subscription.link
        DataStore.subscriptionForceResolve = subscription.forceResolve
        DataStore.subscriptionDeduplication = subscription.deduplication
        DataStore.subscriptionUpdateWhenConnectedOnly = subscription.updateWhenConnectedOnly
        DataStore.subscriptionUserAgent =
            normalizeSpoofUserAgent(subscription.spoofApp ?: SpoofApp.NONE, subscription.customUserAgent)
        DataStore.subscriptionAutoUpdate = subscription.autoUpdate
        DataStore.subscriptionAutoUpdateDelay = subscription.autoUpdateDelay
        DataStore.subscriptionFilterMode = subscription.filterMode
        DataStore.subscriptionFilterRegex = subscription.filterRegex
        DataStore.subscriptionHwidEnabled = subscription.hwidEnabled
        DataStore.subscriptionSpoofApp = subscription.spoofApp
        DataStore.subscriptionServerDns = subscription.serverDnsResolver ?: ""
        DataStore.subscriptionBannerLayout =
            SubscriptionBannerLayout.toValues(subscription.bannerLayout ?: SubscriptionBannerLayout.ALL)
        DataStore.subscriptionRoutingEnabled = subscription.routingEnabled
        DataStore.subscriptionRoutingInterval =
            SubscriptionRoutingIntervals.normalize(subscription.routingUpdateInterval)
    }

    fun ProxyGroup.serialize() {
        name = DataStore.groupName.takeIf { it.isNotBlank() } ?: "My group"
        type = DataStore.groupType
        order = DataStore.groupOrder
        isSelector = DataStore.groupIsSelector
        forceUTLS = DataStore.groupForceUTLS
        enableMux = DataStore.groupEnableMux
        muxType = DataStore.groupMuxType
        muxMode = DataStore.groupMuxMode
        muxConcurrency = DataStore.groupMuxConcurrency
        muxMaxConnections = DataStore.groupMuxMaxConnections
        muxMinStreams = DataStore.groupMuxMinStreams
        muxPadding = DataStore.groupMuxPadding
        muxBrutal = DataStore.groupMuxBrutal
        muxBrutalUpMbps = DataStore.groupMuxBrutalUpMbps
        muxBrutalDownMbps = DataStore.groupMuxBrutalDownMbps

        frontProxy =
            if (DataStore.frontProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
                DataStore.frontProxy
            } else {
                -1
            }
        landingProxy =
            if (DataStore.landingProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
                DataStore.landingProxy
            } else {
                -1
            }

        val isSubscription = type == GroupType.SUBSCRIPTION
        if (isSubscription) {
            subscription =
                (subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                    link = DataStore.subscriptionLink
                    forceResolve = DataStore.subscriptionForceResolve
                    deduplication = DataStore.subscriptionDeduplication
                    updateWhenConnectedOnly = DataStore.subscriptionUpdateWhenConnectedOnly
                    customUserAgent = DataStore.subscriptionUserAgent
                    autoUpdate = DataStore.subscriptionAutoUpdate
                    autoUpdateDelay = DataStore.subscriptionAutoUpdateDelay
                    filterMode = DataStore.subscriptionFilterMode
                    filterRegex = DataStore.subscriptionFilterRegex
                    hwidEnabled = DataStore.subscriptionHwidEnabled
                    spoofApp = DataStore.subscriptionSpoofApp
                    serverDnsResolver = DataStore.subscriptionServerDns
                    bannerLayout =
                        SubscriptionBannerLayout.fromValues(DataStore.subscriptionBannerLayout)
                    routingEnabled = DataStore.subscriptionRoutingEnabled
                    routingUpdateInterval =
                        SubscriptionRoutingIntervals.normalize(DataStore.subscriptionRoutingInterval)
                }
        }
    }

    private var isFromClipboard = false

    fun needSave(): Boolean = DataStore.dirty

    private fun sortProfileNameKey(profile: ProxyEntity): String {
        val name = profile.displayName().trim()
        val firstSortableIndex = name.indexOfFirst { it.isLetterOrDigit() }
        return if (firstSortableIndex >= 0) {
            name.substring(firstSortableIndex).trim()
        } else {
            name
        }
    }

    private fun applySubscriptionOriginOrder(
        group: ProxyGroup,
        profiles: List<ProxyEntity>,
    ): List<ProxyEntity> {
        if (group.type != GroupType.SUBSCRIPTION) return profiles
        val originOrderIds = group.originOrderIds()
        if (originOrderIds.isEmpty()) return profiles

        val originIndex = originOrderIds.withIndex().associate { it.value to it.index }
        val originalPosition = profiles.withIndex().associate { it.value.id to it.index }
        return profiles.sortedWith { left, right ->
            val leftOriginIndex = originIndex[left.id]
            val rightOriginIndex = originIndex[right.id]
            when {
                leftOriginIndex != null && rightOriginIndex != null -> {
                    leftOriginIndex.compareTo(rightOriginIndex)
                }

                leftOriginIndex != null -> {
                    -1
                }

                rightOriginIndex != null -> {
                    1
                }

                else -> {
                    originalPosition.getValue(left.id).compareTo(originalPosition.getValue(right.id))
                }
            }
        }
    }

    private fun persistManualOrderFromPreviousMode(group: ProxyGroup) {
        if (group.order == GroupOrder.MANUAL || DataStore.groupOrder != GroupOrder.MANUAL) return

        var profiles = SagerDatabase.proxyDao.getByGroup(group.id)
        profiles =
            when (group.order) {
                GroupOrder.ORIGIN -> {
                    applySubscriptionOriginOrder(group, profiles)
                }

                GroupOrder.BY_NAME -> {
                    val collator =
                        Collator.getInstance(Locale.ROOT).apply {
                            strength = Collator.PRIMARY
                        }
                    profiles.sortedWith { left, right ->
                        val nameCompare = collator.compare(sortProfileNameKey(left), sortProfileNameKey(right))
                        if (nameCompare != 0) nameCompare else left.id.compareTo(right.id)
                    }
                }

                GroupOrder.BY_DELAY -> {
                    profiles.sortedWith(
                        compareBy<ProxyEntity> { if (it.status == 1) it.ping else 114514 }
                            .thenBy { it.id },
                    )
                }

                else -> {
                    profiles
                }
            }

        val changed =
            profiles.mapIndexedNotNull { index, profile ->
                val newOrder = (index + 1).toLong()
                if (profile.userOrder == newOrder) null else profile.apply { userOrder = newOrder }
            }
        if (changed.isNotEmpty()) {
            SagerDatabase.proxyDao.updateProxy(changed)
        }
    }

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.group_preferences)

        frontProxyPreference = findPreference(Key.GROUP_FRONT_PROXY)!!
        frontProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.frontProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddFront.launch(
                        Intent(
                            this@GroupSettingsActivity,
                            ProfileSelectActivity::class.java,
                        ).apply {
                            ProfileManager.getProfile(DataStore.frontProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        },
                    )
                    false
                } else {
                    true
                }
            }
        }
        landingProxyPreference = findPreference(Key.GROUP_LANDING_PROXY)!!
        landingProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.landingProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddLanding.launch(
                        Intent(
                            this@GroupSettingsActivity,
                            ProfileSelectActivity::class.java,
                        ).apply {
                            ProfileManager.getProfile(DataStore.landingProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        },
                    )
                    false
                } else {
                    true
                }
            }
        }

        val groupType = findPreference<SimpleMenuPreference>(Key.GROUP_TYPE)!!
        val groupSubscription = findPreference<PreferenceCategory>(Key.GROUP_SUBSCRIPTION)!!
        val subscriptionUpdate = findPreference<PreferenceCategory>(Key.SUBSCRIPTION_UPDATE)!!

        fun updateGroupType(groupType: Int = DataStore.groupType) {
            val isSubscription = groupType == GroupType.SUBSCRIPTION
            groupSubscription.isVisible = isSubscription
            subscriptionUpdate.isVisible = isSubscription
        }
        updateGroupType()
        groupType.setOnPreferenceChangeListener { _, newValue ->
            updateGroupType((newValue as String).toInt())
            true
        }

        val enableMux = findPreference<MaterialSwitchPreference>(Key.GROUP_ENABLE_MUX)!!
        val muxType = findPreference<Preference>(Key.GROUP_MUX_TYPE)!!
        val muxMode = findPreference<SimpleMenuPreference>(Key.GROUP_MUX_MODE)!!
        val muxConcurrency = findPreference<Preference>(Key.GROUP_MUX_CONCURRENCY)!!
        val muxMaxConnections = findPreference<Preference>(Key.GROUP_MUX_MAX_CONNECTIONS)!!
        val muxMinStreams = findPreference<Preference>(Key.GROUP_MUX_MIN_STREAMS)!!
        val muxPadding = findPreference<Preference>(Key.GROUP_MUX_PADDING)!!
        val muxBrutal = findPreference<MaterialSwitchPreference>(Key.GROUP_MUX_BRUTAL)!!
        val muxBrutalUpMbps = findPreference<Preference>(Key.GROUP_MUX_BRUTAL_UP_MBPS)!!
        val muxBrutalDownMbps = findPreference<Preference>(Key.GROUP_MUX_BRUTAL_DOWN_MBPS)!!
        val muxDetails = listOf(
            muxType,
            muxMode,
            muxConcurrency,
            muxMaxConnections,
            muxMinStreams,
            muxPadding,
            muxBrutal,
            muxBrutalUpMbps,
            muxBrutalDownMbps,
        )
        fun updateMuxVisibility(
            enabled: Boolean = enableMux.isChecked,
            mode: Int = DataStore.groupMuxMode,
            brutal: Boolean = DataStore.groupMuxBrutal,
        ) {
            muxDetails.forEach { it.isVisible = enabled }
            if (enabled) {
                muxConcurrency.isVisible = mode == 0
                muxMaxConnections.isVisible = mode == 1
                muxMinStreams.isVisible = mode == 1
                muxBrutalUpMbps.isVisible = brutal
                muxBrutalDownMbps.isVisible = brutal
            }
        }
        updateMuxVisibility()
        enableMux.setOnPreferenceChangeListener { _, newValue ->
            updateMuxVisibility(newValue as Boolean)
            true
        }
        muxMode.setOnPreferenceChangeListener { _, newValue ->
            updateMuxVisibility(mode = (newValue as String).toInt())
            true
        }
        muxBrutal.setOnPreferenceChangeListener { _, newValue ->
            updateMuxVisibility(brutal = newValue as Boolean)
            true
        }

        val subscriptionAutoUpdate =
            findPreference<MaterialSwitchPreference>(Key.SUBSCRIPTION_AUTO_UPDATE)!!
        val subscriptionAutoUpdateDelay =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY)!!

        subscriptionAutoUpdateDelay.isEnabled = subscriptionAutoUpdate.isChecked
        subscriptionAutoUpdateDelay.setOnPreferenceChangeListener { _, newValue ->
            val delay = (newValue as String).toIntOrNull()
            if (delay == null) {
                false
            } else {
                delay >= 15
            }
        }
        subscriptionAutoUpdate.setOnPreferenceChangeListener { _, newValue ->
            subscriptionAutoUpdateDelay.isEnabled = (newValue as Boolean)
            true
        }

        val subscriptionFilterMode =
            findPreference<SimpleMenuPreference>(Key.SUBSCRIPTION_FILTER_MODE)!!
        val subscriptionFilterRegex =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_FILTER_REGEX)!!

        fun updateFilterMode(filterMode: Int = DataStore.subscriptionFilterMode) {
            subscriptionFilterRegex.isVisible = filterMode != SubscriptionFilterMode.DISABLED
        }
        updateFilterMode()
        subscriptionFilterMode.setOnPreferenceChangeListener { _, newValue ->
            updateFilterMode((newValue as String).toInt())
            true
        }

        val subscriptionHwidEnabled =
            findPreference<MaterialSwitchPreference>(Key.SUBSCRIPTION_HWID_ENABLED)!!
        val subscriptionSpoofApp =
            findPreference<SimpleMenuPreference>(Key.SUBSCRIPTION_SPOOF_APP)!!
        val subscriptionUserAgent =
            findPreference<UserAgentPreference>(Key.SUBSCRIPTION_USER_AGENT)!!

        fun showSpoofWithoutHwidWarning() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.spoof_app_without_hwid_title)
                .setMessage(R.string.spoof_app_without_hwid_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        subscriptionHwidEnabled.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (shouldWarnAboutMissingSpoofHwid(DataStore.subscriptionSpoofApp, enabled)) {
                showSpoofWithoutHwidWarning()
            }
            true
        }

        subscriptionSpoofApp.setOnPreferenceChangeListener { _, newValue ->
            val spoofApp = (newValue as String).toInt()
            val ua = defaultSpoofUserAgent(spoofApp)
            subscriptionUserAgent.text = ua
            DataStore.subscriptionUserAgent = ua
            subscriptionUserAgent.notifyChanged()
            if (shouldWarnAboutMissingSpoofHwid(spoofApp, subscriptionHwidEnabled.isChecked)) {
                showSpoofWithoutHwidWarning()
            }
            true
        }

        val subscriptionServerDns =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_SERVER_DNS)!!
        subscriptionServerDns.setOnPreferenceChangeListener { pref, newValue ->
            val value = (newValue as String).trim()
            if (isValidServerDns(value)) {
                if (value != newValue) {
                    (pref as EditTextPreference).text = value
                    false
                } else {
                    true
                }
            } else {
                Toast
                    .makeText(
                        requireContext(),
                        R.string.server_dns_invalid,
                        Toast.LENGTH_LONG,
                    ).show()
                false
            }
        }

        val subscriptionRoutingEnabled =
            findPreference<MaterialSwitchPreference>(Key.SUBSCRIPTION_ROUTING_ENABLED)!!
        val subscriptionRoutingInterval =
            findPreference<SimpleMenuPreference>(Key.SUBSCRIPTION_ROUTING_INTERVAL)!!
        subscriptionRoutingInterval.isEnabled = subscriptionRoutingEnabled.isChecked
        subscriptionRoutingEnabled.setOnPreferenceChangeListener { _, newValue ->
            subscriptionRoutingInterval.isEnabled = newValue as Boolean
            true
        }
        subscriptionRoutingInterval.setOnPreferenceChangeListener { _, newValue ->
            newValue.toString().toIntOrNull() in SubscriptionRoutingIntervals.allowed
        }
        findPreference<Preference>(Key.SUBSCRIPTION_IMPORT_ROUTING)!!
            .setOnPreferenceClickListener {
                importSubscriptionRouting()
                true
            }
    }

    private fun importSubscriptionRouting() {
        val link = DataStore.subscriptionLink.trim()
        if (link.isBlank()) {
            Toast.makeText(this, R.string.subscription_routing_not_found, Toast.LENGTH_LONG).show()
            return
        }
        val progress = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.routing_import_preparing)
            .setCancelable(false)
            .show()
        runOnDefaultDispatcher {
            val result = runCatching {
                val storedGroup = DataStore.editingId.takeIf { it > 0L }
                    ?.let(SagerDatabase.groupDao::getById)
                val group = storedGroup ?: ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
                    subscription = SubscriptionBean().applyDefaultValues()
                }
                group.subscription!!.apply {
                    this.link = link
                    customUserAgent = DataStore.subscriptionUserAgent
                    hwidEnabled = DataStore.subscriptionHwidEnabled
                    spoofApp = DataStore.subscriptionSpoofApp
                }
                SubscriptionRoutingRepository.fetchFromSubscription(group)
            }
            onMainDispatcher {
                progress.dismiss()
                result.onSuccess { (source, routing) ->
                    when {
                        source is ProviderRoutingSource.Off -> {
                            Toast.makeText(
                                this@GroupSettingsActivity,
                                R.string.subscription_routing_disabled_by_provider,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        routing == null -> {
                            Toast.makeText(
                                this@GroupSettingsActivity,
                                R.string.subscription_routing_not_found,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        else -> {
                            val token = RoutingPreviewPayloadStore.put(
                                this@GroupSettingsActivity,
                                routing.candidate(),
                            )
                            startActivity(
                                Intent(
                                    this@GroupSettingsActivity,
                                    RoutingImportPreviewActivity::class.java,
                                ).putExtra(RoutingImportPreviewActivity.EXTRA_PAYLOAD_TOKEN, token),
                            )
                        }
                    }
                }.onFailure {
                    MaterialAlertDialogBuilder(this@GroupSettingsActivity)
                        .setTitle(R.string.error_title)
                        .setMessage(it.readableMessage)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as GroupSettingsActivity).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class GroupIdArg(
        val groupId: Long,
    ) : Parcelable

    class DeleteConfirmationDialogFragment : AlertDialogFragment<GroupIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_group_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    GroupManager.deleteGroup(arg.groupId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "id"
        const val EXTRA_FROM_CLIPBOARD = "fromClipboard"
        const val EXTRA_GROUP_SUBSCRIPTION_LINK = "subscription_link"
    }

    @SuppressLint("CommitTransaction")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.group_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
            isFromClipboard = intent.getBooleanExtra(EXTRA_FROM_CLIPBOARD, false)
            val subscriptionLink = intent.getStringExtra(EXTRA_GROUP_SUBSCRIPTION_LINK)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    val group = ProxyGroup()
                    group.init()

                    // 如果有订阅链接，设置为订阅类型并填充链接
                    if (!subscriptionLink.isNullOrEmpty()) {
                        DataStore.groupType = GroupType.SUBSCRIPTION
                        DataStore.subscriptionLink = subscriptionLink
                        DataStore.dirty = true
                    }
                } else {
                    val entity = SagerDatabase.groupDao.getById(editingId)
                    if (entity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    entity.init()
                }

                onMainDispatcher {
                    supportFragmentManager
                        .beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()

                    DataStore.dirty = false
                    DataStore.profileCacheStore.registerChangeListener(this@GroupSettingsActivity)
                }
            }
        }
    }

    suspend fun saveAndExit() {
        val editingId = DataStore.editingId
        if (editingId == 0L) {
            val draft = ProxyGroup().apply { serialize() }
            val requestedRouting = draft.subscription?.routingEnabled == true
            if (requestedRouting) draft.subscription?.routingEnabled = false
            val newGroup = GroupManager.createGroup(draft)
            val routingResult =
                if (requestedRouting) downloadRoutingForSave(newGroup, newGroup.id) else Result.success(null)
            if (routingResult.isFailure) {
                DataStore.editingId = newGroup.id
                return
            }
            val routing = routingResult.getOrNull()
            if (requestedRouting && routing == null) {
                newGroup.subscription?.let(SubscriptionRoutingRepository::clearStored)
            }
            if (routing != null) {
                SubscriptionRoutingRepository.store(newGroup.subscription!!, routing)
                GroupManager.updateGroup(newGroup)
            } else if (requestedRouting) {
                GroupManager.updateGroup(newGroup)
                showRoutingNotProvided()
            }
            if (isFromClipboard && newGroup.type == GroupType.SUBSCRIPTION && !newGroup.subscription?.link.isNullOrEmpty()) {
                GroupUpdater.startUpdate(newGroup, true)
            }
        } else if (needSave()) {
            val entity = SagerDatabase.groupDao.getById(editingId)
            if (entity == null) {
                onMainDispatcher { finish() }
                return
            }
            val routingWasEnabled = entity.subscription?.routingEnabled == true
            val keepUserInfo = (
                entity.type == GroupType.SUBSCRIPTION &&
                    DataStore.groupType == GroupType.SUBSCRIPTION &&
                    entity.subscription?.link == DataStore.subscriptionLink
            )
            if (!keepUserInfo) {
                entity.subscription?.apply {
                    subscriptionUserinfo = ""
                    announcement = ""
                    announcementUrl = ""
                    supportUrl = ""
                    supportEmail = ""
                    profileWebPageUrl = ""
                    homepage = ""
                    SubscriptionRoutingRepository.clearStored(this)
                }
                SubscriptionRoutingRepository.deleteFiles(entity.id)
            }
            persistManualOrderFromPreviousMode(entity)
            entity.serialize()
            val subscription = entity.subscription
            if (entity.type == GroupType.SUBSCRIPTION && subscription?.routingEnabled == true) {
                val routingResult = downloadRoutingForSave(entity, entity.id)
                if (routingResult.isFailure) return
                val routing = routingResult.getOrNull()
                if (routing == null) {
                    SubscriptionRoutingRepository.clearStored(subscription)
                    SubscriptionRoutingRepository.deleteFiles(entity.id)
                    GroupManager.updateGroup(entity)
                    showRoutingNotProvided()
                } else {
                    SubscriptionRoutingRepository.store(subscription, routing)
                    GroupManager.updateGroup(entity)
                }
            } else {
                if (routingWasEnabled) {
                    subscription?.let(SubscriptionRoutingRepository::clearStored)
                    SubscriptionRoutingRepository.deleteFiles(entity.id)
                }
                GroupManager.updateGroup(entity)
            }
        }

        onMainDispatcher { finish() }
    }

    private suspend fun downloadRoutingForSave(
        group: ProxyGroup,
        groupId: Long,
    ): Result<io.nekohasekai.sagernet.routing.ResolvedSubscriptionRouting?> {
        val dialog = onMainDispatcher {
            val progress = LayoutProgressBinding.inflate(layoutInflater)
            progress.content.setText(R.string.subscription_routing_downloading)
            MaterialAlertDialogBuilder(this@GroupSettingsActivity)
                .setView(progress.root)
                .setCancelable(false)
                .show()
        }
        val result = runCatching {
            val (source, routing) = SubscriptionRoutingRepository.fetchFromSubscription(group)
            routing.takeUnless {
                source is ProviderRoutingSource.Missing || source is ProviderRoutingSource.Off
            }?.also {
                SubscriptionRoutingRepository.prepareAssets(groupId, it)
            }
        }
        onMainDispatcher { dialog.dismiss() }
        result.exceptionOrNull()?.let { showRoutingError(it) }
        return result
    }

    private suspend fun showRoutingNotProvided() {
        onMainDispatcher {
            MessageStore.showMessage(
                this@GroupSettingsActivity,
                R.string.subscription_routing_not_provided,
            )
        }
    }

    private suspend fun showRoutingError(error: Throwable) {
        onMainDispatcher {
            MaterialAlertDialogBuilder(this@GroupSettingsActivity)
                .setTitle(R.string.error_title)
                .setMessage(error.readableMessage)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        if (DataStore.editingId != 0L && !GroupManager.canDelete(DataStore.editingId)) {
            menu.findItem(R.id.action_delete)?.isVisible = false
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = child.onOptionsItemSelected(item)

    override fun onBackPressed() {
        if (needSave()) {
            UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
        } else {
            super.onBackPressed()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(
        store: PreferenceDataStore,
        key: String,
    ) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {
        var activity: GroupSettingsActivity? = null

        override fun onCreatePreferences(
            savedInstanceState: Bundle?,
            rootKey: String?,
        ) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity =
                    (requireActivity() as GroupSettingsActivity).apply {
                        createPreferences(savedInstanceState, rootKey)
                    }
            } catch (e: Exception) {
                Toast
                    .makeText(
                        SagerNet.application,
                        "Error on createPreferences, please try again.",
                        Toast.LENGTH_SHORT,
                    ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(
            view: View,
            savedInstanceState: Bundle?,
        ) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        override fun onOptionsItemSelected(item: MenuItem) =
            when (item.itemId) {
                R.id.action_delete -> {
                    if (DataStore.editingId == 0L) {
                        requireActivity().finish()
                    } else if (!GroupManager.canDelete(DataStore.editingId)) {
                        Toast
                            .makeText(
                                requireContext(),
                                R.string.group_delete_unavailable,
                                Toast.LENGTH_SHORT,
                            ).show()
                    } else if (!DataStore.confirmProfileDelete) {
                        runOnDefaultDispatcher {
                            GroupManager.deleteGroup(DataStore.editingId)
                        }
                        requireActivity().finish()
                    } else {
                        DeleteConfirmationDialogFragment()
                            .apply {
                                arg(GroupIdArg(DataStore.editingId))
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

                else -> {
                    false
                }
            }

        override fun onDisplayPreferenceDialog(preference: Preference) {
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

    private fun showInvalidProfileDialog(messageResId: Int) {
        AlertDialog
            .Builder(this)
            .setTitle(R.string.invalid_profile)
            .setMessage(messageResId)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    val selectProfileForAddFront =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            if (it.resultCode == Activity.RESULT_OK) {
                runOnDefaultDispatcher {
                    val profile =
                        ProfileManager.getProfile(
                            it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0),
                        ) ?: return@runOnDefaultDispatcher
                    if (profile.containsByeDPI() && !profile.startsWithByeDPI()) {
                        onMainDispatcher {
                            showInvalidProfileDialog(R.string.byedpi_front_proxy_error)
                        }
                        return@runOnDefaultDispatcher
                    }
                    DataStore.frontProxy = profile.id
                    onMainDispatcher {
                        frontProxyPreference.value = "3"
                        frontProxyPreference.postUpdate()
                    }
                }
            }
        }

    val selectProfileForAddLanding =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            if (it.resultCode == Activity.RESULT_OK) {
                runOnDefaultDispatcher {
                    val profile =
                        ProfileManager.getProfile(
                            it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0),
                        ) ?: return@runOnDefaultDispatcher
                    if (profile.containsByeDPI()) {
                        onMainDispatcher {
                            showInvalidProfileDialog(R.string.byedpi_landing_proxy_error)
                        }
                        return@runOnDefaultDispatcher
                    }
                    DataStore.landingProxy = profile.id
                    onMainDispatcher {
                        landingProxyPreference.value = "3"
                        landingProxyPreference.postUpdate()
                    }
                }
            }
        }
}

private fun isValidServerDns(raw: String): Boolean {
    val value = raw.trim()
    if (value.isEmpty()) return true
    if (value.any { it.isISOControl() || it.isWhitespace() }) return false

    if (value.contains("://")) {
        val scheme = value.substringBefore("://").lowercase()
        if (scheme !in setOf("https", "tls", "quic")) return false
        val rest = value.substringAfter("://")
        val host = rest.substringBefore("/").substringBefore("?")
        val bare = host.substringBeforeLast(":").trim('[', ']')
        return bare.isNotEmpty()
    }

    val host = value.substringBeforeLast(":").trim('[', ']')
    return host.isNotEmpty()
}
