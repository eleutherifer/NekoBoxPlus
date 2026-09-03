package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.AppData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.happCryptUnsupportedDialog
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ui.compose.AddGroupAction
import io.nekohasekai.sagernet.ui.compose.GroupAction
import io.nekohasekai.sagernet.ui.compose.GroupScreen
import io.nekohasekai.sagernet.ui.compose.GroupUiItem
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.utils.SubscriptionTrafficFormatter
import io.nekohasekai.sagernet.utils.SubscriptionUserInfoParser
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util

class GroupFragment : ToolbarFragment(), GroupManager.Listener,
    UndoSnackbarManager.Interface<ProxyGroup> {

    private lateinit var mainActivity: MainActivity
    private lateinit var undoManager: UndoSnackbarManager<ProxyGroup>
    private val groups = ArrayList<ProxyGroup>()
    private val uiItems = mutableStateListOf<GroupUiItem>()
    private val movedGroups = HashSet<ProxyGroup>()
    private var loading by mutableStateOf(true)
    private lateinit var selectedGroup: ProxyGroup

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        mainActivity = requireActivity() as MainActivity
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NekoComposeTheme {
                    GroupScreen(
                        groups = uiItems,
                        loading = loading,
                        onOpenDrawer = { mainActivity.openDrawer() },
                        onUpdateAll = ::updateAll,
                        onAdd = ::handleAddAction,
                        onAction = ::handleGroupAction,
                        shouldConfirmDelete = { DataStore.confirmProfileDelete },
                        onMove = ::move,
                        onMoveFinished = ::commitMove,
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        undoManager = UndoSnackbarManager(mainActivity, this)
        GroupManager.addListener(this)
        reload()
    }

    fun refreshSubscriptionTrafficUnits() = refreshAll()

    private fun reload() = runOnDefaultDispatcher {
        val loaded = AppData.groups.allGroups().toMutableList()
        if (loaded.isEmpty()) {
            loaded += ProxyGroup(ungrouped = true).apply {
                id = AppData.groups.createGroup(this)
            }
        }
        loaded.find { it.ungrouped }?.let { ungrouped ->
            if (loaded.size > 1 && AppData.profiles.countByGroup(ungrouped.id) == 0L) {
                loaded.removeAll { it.ungrouped }
            }
        }
        val items = loaded.map(::createUiItem)
        onMainDispatcher {
            groups.clear()
            groups.addAll(loaded)
            uiItems.clear()
            uiItems.addAll(items)
            loading = false
        }
    }

    private fun refreshAll() = runOnDefaultDispatcher {
        val snapshot = groups.toList()
        val items = snapshot.map(::createUiItem)
        onMainDispatcher {
            uiItems.clear()
            uiItems.addAll(items)
        }
    }

    private fun refresh(groupId: Long) = runOnDefaultDispatcher {
        val group = AppData.groups.getById(groupId) ?: return@runOnDefaultDispatcher
        val item = createUiItem(group)
        onMainDispatcher {
            val index = groups.indexOfFirst { it.id == groupId }
            if (index < 0) {
                reload()
            } else {
                groups[index] = group
                uiItems[index] = item
            }
        }
    }

    private fun createUiItem(group: ProxyGroup): GroupUiItem {
        val subscription = group.subscription
        val count = AppData.profiles.countByGroup(group.id)
        val updating = group.id in GroupUpdater.updating
        val updateProgress = GroupUpdater.progress[group.id]
        return GroupUiItem(
            id = group.id,
            name = group.displayName(),
            username = subscription?.username.orEmpty(),
            status = when (group.type) {
                GroupType.SUBSCRIPTION -> if (count == 0L) {
                    getString(R.string.group_status_empty_subscription)
                } else {
                    getString(
                        R.string.group_status_proxies_subscription,
                        Util.timeStamp2Text(subscription!!.lastUpdated * 1000L),
                        count,
                    )
                }
                else -> if (count == 0L) {
                    getString(R.string.group_status_empty)
                } else {
                    getString(R.string.group_status_proxies, count)
                }
            },
            traffic = subscriptionTraffic(subscription),
            canEdit = !group.ungrouped || GroupManager.canDelete(group.id),
            canDelete = GroupDeletionPolicy.canSwipeDelete(
                isUngrouped = group.ungrouped,
                canDeleteGroup = GroupManager.canDelete(group.id),
                isUpdating = updating,
            ),
            canDrag = !group.ungrouped && !updating,
            canUpdate = group.type == GroupType.SUBSCRIPTION,
            isUpdating = updating,
            progress = updateProgress?.let {
                if (it.max <= 0) null else (it.progress.toFloat() / it.max).coerceIn(0f, 1f)
            },
            canShareSubscription = group.type == GroupType.SUBSCRIPTION,
            canShareSubscriptionUrl = group.type == GroupType.SUBSCRIPTION &&
                !subscription?.link.isNullOrBlank(),
        )
    }

    private fun subscriptionTraffic(subscription: SubscriptionBean?): String? {
        if (subscription == null) return null
        if (subscription.bytesUsed > 0L) {
            return if (subscription.bytesRemaining > 0L) {
                getString(
                    R.string.subscription_traffic,
                    SubscriptionTrafficFormatter.format(
                        subscription.bytesUsed,
                        DataStore.subscriptionTrafficUnit,
                    ),
                    SubscriptionTrafficFormatter.format(
                        subscription.bytesRemaining,
                        DataStore.subscriptionTrafficUnit,
                    ),
                )
            } else {
                getString(
                    R.string.subscription_used,
                    SubscriptionTrafficFormatter.format(
                        subscription.bytesUsed,
                        DataStore.subscriptionTrafficUnit,
                    ),
                )
            }
        }
        val userInfo = subscription.subscriptionUserinfo.orEmpty()
        if (userInfo.isBlank()) return null
        val info = SubscriptionUserInfoParser.parse(userInfo)
        val used = info.usedBytes
        val total = info.totalBytes
        val lines = ArrayList<String>(2)
        if (used > 0L || total > 0L) {
            val remaining = total - used
            lines += if (remaining > 0L) {
                getString(
                    R.string.subscription_traffic,
                    SubscriptionTrafficFormatter.format(used, DataStore.subscriptionTrafficUnit),
                    SubscriptionTrafficFormatter.format(remaining, DataStore.subscriptionTrafficUnit),
                )
            } else {
                getString(
                    R.string.subscription_used,
                    SubscriptionTrafficFormatter.format(used, DataStore.subscriptionTrafficUnit),
                )
            }
        }
        info.expiresAtEpochSeconds?.let {
            lines += getString(R.string.subscription_expire, Util.timeStamp2Text(it * 1000L))
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun handleAddAction(action: AddGroupAction) {
        when (action) {
            AddGroupAction.New -> startActivity(Intent(context, GroupSettingsActivity::class.java))
            AddGroupAction.Clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) snackbar(getString(R.string.clipboard_empty)).show()
                else importSubscriptions(text, R.string.no_subscriptions_found_in_clipboard)
            }
            AddGroupAction.ScanQr -> scanSubscription.launch(
                Intent(context, ScannerActivity::class.java).apply {
                    putExtra(ScannerActivity.EXTRA_RETURN_SCAN_TEXT, true)
                },
            )
        }
    }

    private fun updateAll() = runOnDefaultDispatcher {
        AppData.groups.allGroups()
            .filter { it.type == GroupType.SUBSCRIPTION }
            .forEach { GroupUpdater.startUpdate(it, true) }
    }

    private fun handleGroupAction(groupId: Long, action: GroupAction) {
        val group = groups.firstOrNull { it.id == groupId } ?: return
        selectedGroup = group
        when (action) {
            GroupAction.Edit -> startActivity(Intent(context, GroupSettingsActivity::class.java).apply {
                putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
            })
            GroupAction.Update -> GroupUpdater.startUpdate(group, true)
            GroupAction.ShareUrlClipboard -> exportToClipboard(subscriptionUrl(group))
            GroupAction.ShareUrlQr -> showQr(subscriptionUrl(group), group.displayName())
            GroupAction.ShareUniversalClipboard -> exportToClipboard(group.toUniversalLink())
            GroupAction.ShareUniversalQr -> showQr(group.toUniversalLink(), group.displayName())
            GroupAction.ExportClipboard -> exportProfilesToClipboard(group)
            GroupAction.ExportFile -> startFilesForResult(
                exportProfiles,
                "profiles_${group.displayName()}.txt",
            )
            GroupAction.Clear -> runOnDefaultDispatcher { GroupManager.clearGroup(group.id) }
            GroupAction.Delete -> deleteGroup(group)
        }
    }

    private fun subscriptionUrl(group: ProxyGroup): String {
        val link = group.subscription?.link.orEmpty()
        val hasFragment = link.contains('#') ||
            runCatching { Uri.parse(link).fragment != null }.getOrDefault(false)
        return if (hasFragment) link else "$link#${Uri.encode(group.displayName())}"
    }

    private fun exportToClipboard(link: String) {
        val success = SagerNet.trySetPrimaryClip(link)
        mainActivity.snackbar(if (success) R.string.action_export_msg else R.string.action_export_err).show()
    }

    private fun showQr(link: String, name: String) {
        QRCodeDialog(link, name).showAllowingStateLoss(parentFragmentManager)
    }

    private fun exportProfilesToClipboard(group: ProxyGroup) = runOnDefaultDispatcher {
        val links = AppData.profiles.getByGroup(group.id)
            .mapNotNull { it.toGroupExportLink() }
            .joinToString("\n")
        onMainDispatcher {
            SagerNet.trySetPrimaryClip(links)
            snackbar(getString(R.string.copy_toast_msg)).show()
        }
    }

    private fun deleteGroup(group: ProxyGroup) {
        val index = groups.indexOfFirst { it.id == group.id }
        if (index < 0) return
        groups.removeAt(index)
        uiItems.removeAt(index)
        undoManager.remove(index to group)
    }

    private fun move(from: Int, requestedTo: Int) {
        val to = requestedTo.coerceIn(groups.indices)
        if (from !in groups.indices || from == to) return
        val first = groups[from]
        var previousOrder = first.userOrder
        val (step, range) = if (from < to) 1 to (from until to) else -1 to (to + 1 downTo from)
        for (index in range) {
            val next = groups[index + step]
            val order = next.userOrder
            next.userOrder = previousOrder
            previousOrder = order
            groups[index] = next
            movedGroups += next
        }
        first.userOrder = previousOrder
        groups[to] = first
        movedGroups += first
        val item = uiItems.removeAt(from)
        uiItems.add(to, item)
    }

    private fun commitMove() {
        val snapshot = movedGroups.toList()
        movedGroups.clear()
        if (snapshot.isEmpty()) return
        runOnDefaultDispatcher {
            snapshot.forEach(AppData.groups::updateGroup)
        }
    }

    override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
        runOnDefaultDispatcher {
            val restored = actions.map { (index, group) -> Triple(index, group, createUiItem(group)) }
            onMainDispatcher {
                restored.forEach { (requestedIndex, group, item) ->
                    val index = requestedIndex.coerceIn(0, groups.size)
                    groups.add(index, group)
                    uiItems.add(index, item)
                }
            }
        }
    }

    override fun commit(actions: List<Pair<Int, ProxyGroup>>) {
        runOnDefaultDispatcher {
            GroupManager.deleteGroup(actions.map { it.second })
            reload()
        }
    }

    override suspend fun groupAdd(group: ProxyGroup) {
        delay(300L)
        val item = createUiItem(group)
        onMainDispatcher {
            undoManager.flush()
            if (group.ungrouped) reload() else {
                groups += group
                uiItems += item
                if (group.type == GroupType.SUBSCRIPTION) GroupUpdater.startUpdate(group, true)
            }
        }
    }

    override suspend fun groupRemoved(groupId: Long) {
        onMainDispatcher {
            undoManager.flush()
            reload()
        }
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        refresh(group.id)
    }

    override suspend fun groupUpdated(groupId: Long) {
        refresh(groupId)
    }

    override suspend fun groupProfileCountChanged(groupId: Long) {
        refresh(groupId)
    }

    private val exportProfiles = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null || !::selectedGroup.isInitialized) return@registerForActivityResult
        runOnDefaultDispatcher {
            val links = AppData.profiles.getByGroup(selectedGroup.id)
                .mapNotNull { it.toGroupExportLink() }
                .joinToString("\n")
            try {
                requireActivity().contentResolver.openOutputStream(uri)!!.bufferedWriter().use {
                    it.write(links)
                }
                onMainDispatcher { snackbar(getString(R.string.action_export_msg)).show() }
            } catch (error: Exception) {
                Logs.w(error)
                onMainDispatcher { snackbar(error.readableMessage).show() }
            }
        }
    }

    private val scanSubscription = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            importSubscriptions(
                result.data?.getStringExtra(ScannerActivity.EXTRA_SCAN_TEXT).orEmpty(),
                R.string.no_subscriptions_found_in_qr,
            )
        }
    }

    private fun importSubscriptions(text: String, notFoundMessage: Int) {
        if (text.isBlank()) {
            snackbar(notFoundMessage).show()
            return
        }
        if (SubscriptionLinkImportPolicy.isHappCryptLink(text)) {
            requireContext().happCryptUnsupportedDialog().show()
            return
        }
        runOnDefaultDispatcher {
            val existingLinks = AppData.groups.subscriptions()
                .mapNotNull { it.subscription?.link?.takeIf(String::isNotBlank) }
                .map(SubscriptionLinkImportPolicy::linkWithoutFragment)
                .toHashSet()
            val importedLinks = HashSet<String>()
            var imported = 0
            var found = 0

            suspend fun addGroup(group: ProxyGroup, name: String?) {
                val link = group.subscription?.link.orEmpty()
                val normalized = SubscriptionLinkImportPolicy.linkWithoutFragment(link)
                if (normalized.isBlank()) return
                found++
                if (normalized in existingLinks || !importedLinks.add(normalized)) return
                group.id = 0L
                group.userOrder = 0L
                group.ungrouped = false
                group.type = GroupType.SUBSCRIPTION
                group.name = group.name?.takeIf(String::isNotBlank)
                    ?: name?.takeIf(String::isNotBlank)
                    ?: "Subscription #${System.currentTimeMillis()}"
                group.subscription = (group.subscription ?: SubscriptionBean()).apply { this.link = normalized }
                GroupManager.createGroup(group)
                imported++
            }

            suspend fun addSubscription(link: String, name: String?) = addGroup(
                ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
                    this.name = name?.takeIf(String::isNotBlank)
                        ?: "Subscription #${System.currentTimeMillis()}"
                    subscription = SubscriptionBean().apply {
                        this.link = link
                        autoUpdate = false
                    }
                },
                name,
            )

            suspend fun addSnSubscription(link: String) {
                val uri = Uri.parse(link)
                val url = uri.getQueryParameter("url")
                if (!url.isNullOrBlank()) {
                    addSubscription(url, uri.getQueryParameter("name") ?: SubscriptionLinkImportPolicy.linkFragment(url))
                    return
                }
                val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
                val group = runCatching {
                    KryoConverters.deserialize(
                        ProxyGroup().apply { export = true },
                        Util.zlibDecompress(Util.b64Decode(data)),
                    ).apply { export = false }
                }.onFailure(Logs::w).getOrNull() ?: return
                val subscriptionLink = group.subscription?.link?.takeIf(String::isNotBlank) ?: return
                addGroup(group, SubscriptionLinkImportPolicy.linkFragment(subscriptionLink))
            }

            val links = SubscriptionLinkImportPolicy.extractLinks(text)
            links.filter { it.startsWith("sn://subscription?", ignoreCase = true) }.forEach {
                runCatching { addSnSubscription(it) }.onFailure(Logs::w)
            }
            links.filter(SubscriptionLinkImportPolicy::isHttpLink).forEach {
                runCatching { addSubscription(it, SubscriptionLinkImportPolicy.linkFragment(it)) }
                    .onFailure(Logs::w)
            }
            onMainDispatcher {
                when {
                    found == 0 -> snackbar(notFoundMessage).show()
                    imported == 0 -> snackbar(R.string.subscription_already_exists).show()
                    else -> snackbar(
                        resources.getQuantityString(R.plurals.subscriptions_added, imported, imported),
                    ).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        if (::undoManager.isInitialized) undoManager.flush()
        GroupManager.removeListener(this)
        super.onDestroyView()
    }
}
