package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleType
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.routing.RoutingExportWarning
import io.nekohasekai.sagernet.routing.RoutingProfileExporter
import io.nekohasekai.sagernet.routing.RoutingProfileFormat
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.RouteScreen
import io.nekohasekai.sagernet.ui.compose.RouteScreenAction
import io.nekohasekai.sagernet.ui.compose.RouteScreenExportDestination
import io.nekohasekai.sagernet.ui.compose.RouteScreenExportFormat
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import java.util.Collections

private object PendingRouteDeletions {
    private val ids = Collections.synchronizedSet(mutableSetOf<Long>())

    fun add(ruleId: Long) = ids.add(ruleId)
    fun remove(ruleId: Long) = ids.remove(ruleId)
    fun contains(ruleId: Long) = ids.contains(ruleId)
}

class RouteFragment : ToolbarFragment(), ProfileManager.RuleListener,
    UndoSnackbarManager.Interface<RuleEntity> {

    private lateinit var activity: MainActivity
    private lateinit var undoManager: UndoSnackbarManager<RuleEntity>
    private val rules = mutableStateListOf<RuleEntity>()
    private val movedRules = LinkedHashSet<RuleEntity>()
    private var exportWarningMessage by mutableStateOf<String?>(null)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NekoComposeTheme {
                    RouteScreen(
                        rules = rules,
                        onOpenDrawer = { (requireActivity() as MainActivity).openDrawer() },
                        onAddNormalRoute = { addRoute(RuleType.NORMAL) },
                        onAddDnsRoute = { addRoute(RuleType.DNS) },
                        onAction = ::handleScreenAction,
                        onExport = ::handleExport,
                        shouldConfirmDelete = { DataStore.confirmProfileDelete },
                        exportWarningMessage = exportWarningMessage,
                        onDismissExportWarning = { exportWarningMessage = null },
                        onOpenDocumentation = {
                            requireContext().launchCustomTab(
                                "https://matsuridayo.github.io/nb4a-route/",
                            )
                        },
                        onEnabledChange = ::setRuleEnabled,
                        onEdit = ::editRule,
                        onDuplicate = ::duplicateRule,
                        onDelete = ::deleteRule,
                        onMove = ::move,
                        onMoveFinished = ::commitMove,
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity = requireActivity() as MainActivity

        undoManager = UndoSnackbarManager(activity, this)
        ProfileManager.addListener(this)
        runOnDefaultDispatcher { reload() }
    }

    override fun onDestroyView() {
        if (::undoManager.isInitialized) undoManager.flush()
        ProfileManager.removeListener(this)
        movedRules.clear()
        rules.clear()
        exportWarningMessage = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private suspend fun reload() {
        val loaded = ProfileManager.getRules().filterNot { PendingRouteDeletions.contains(it.id) }
        onMainDispatcher {
            rules.clear()
            rules.addAll(loaded)
        }
    }

    private fun addRoute(type: RuleType) {
        startActivity(Intent(requireContext(), RouteSettingsActivity::class.java).apply {
            if (type == RuleType.DNS) {
                putExtra(RouteSettingsActivity.EXTRA_ROUTE_TYPE, RuleType.DNS.value)
            }
        })
    }

    private fun handleScreenAction(action: RouteScreenAction) {
        when (action) {
            RouteScreenAction.RESET -> {
                runOnDefaultDispatcher {
                    SagerDatabase.rulesDao.reset()
                    DataStore.rulesFirstCreate = false
                    reload()
                }
            }
            RouteScreenAction.MANAGE_ASSETS -> {
                startActivity(Intent(requireContext(), AssetsActivity::class.java))
            }
            RouteScreenAction.IMPORT_CLIPBOARD -> {
                activity.requestRoutingImport(SagerNet.getClipboardText())
            }
        }
    }

    private fun handleExport(
        format: RouteScreenExportFormat,
        destination: RouteScreenExportDestination,
        name: String,
    ) = requestRoutingExport(
        when (format) {
            RouteScreenExportFormat.NEKOBOX_PLUS -> RoutingProfileFormat.NEKOBOX_PLUS
            RouteScreenExportFormat.HAPP -> RoutingProfileFormat.HAPP
            RouteScreenExportFormat.INCY -> RoutingProfileFormat.INCY
        },
        when (destination) {
            RouteScreenExportDestination.CLIPBOARD -> RoutingExportDestination.CLIPBOARD
            RouteScreenExportDestination.SHARE -> RoutingExportDestination.SHARE
            RouteScreenExportDestination.QR_CODE -> RoutingExportDestination.QR_CODE
        },
        name,
    )

    private enum class RoutingExportDestination { CLIPBOARD, SHARE, QR_CODE }

    private fun requestRoutingExport(
        format: RoutingProfileFormat,
        destination: RoutingExportDestination,
        name: String,
    ) {
        runOnDefaultDispatcher {
            val result = RoutingProfileExporter.export(
                format,
                name,
                SagerDatabase.rulesDao.allRules(),
            )
            onMainDispatcher {
                if (!isAdded || view == null) return@onMainDispatcher
                val completed = when (destination) {
                    RoutingExportDestination.CLIPBOARD -> {
                        val copied = SagerNet.trySetPrimaryClip(result.link)
                        when {
                            !copied -> activity.snackbar(R.string.action_export_err).show()
                            result.warnings.isEmpty() -> {
                                activity.snackbar(R.string.action_export_msg).show()
                            }
                        }
                        copied
                    }
                    RoutingExportDestination.SHARE -> {
                        startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, result.link),
                            getString(R.string.share),
                        ))
                        true
                    }
                    RoutingExportDestination.QR_CODE -> {
                        QRCodeDialog(result.link, name)
                            .showAllowingStateLoss(parentFragmentManager)
                        true
                    }
                }
                if (completed && result.warnings.isNotEmpty()) {
                    exportWarningMessage = result.warnings.joinToString("\n") { warningText(it) }
                }
            }
        }
    }

    private fun warningText(warning: RoutingExportWarning): String = getString(when (warning) {
        RoutingExportWarning.UNSUPPORTED_RULES -> R.string.routing_export_warning_unsupported_rules
        RoutingExportWarning.SIMPLIFIED_ORDER -> R.string.routing_export_warning_order
        RoutingExportWarning.DNS_VALUES_OMITTED -> R.string.routing_export_warning_dns
        RoutingExportWarning.DNS_HOST_VALUES_OMITTED -> R.string.routing_export_warning_hosts
        RoutingExportWarning.CUSTOM_OUTBOUND_FALLBACK -> R.string.routing_export_warning_outbound_fallback
    })

    private fun setRuleEnabled(ruleId: Long, enabled: Boolean) {
        val index = rules.indexOfFirst { it.id == ruleId }
        if (index < 0) return
        val rule = rules[index].copy(enabled = enabled)
        rules[index] = rule
        runOnDefaultDispatcher {
            SagerDatabase.rulesDao.updateRule(rule)
            onMainDispatcher { needReload() }
        }
    }

    private fun editRule(ruleId: Long) {
        startActivity(Intent(requireContext(), RouteSettingsActivity::class.java).apply {
            putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, ruleId)
        })
    }

    private fun duplicateRule(ruleId: Long) {
        val rule = rules.firstOrNull { it.id == ruleId } ?: return
        runOnDefaultDispatcher {
            ProfileManager.duplicateRuleAfter(rule)
            reload()
            onMainDispatcher {
                needReload()
                Toast.makeText(requireContext(), R.string.route_duplicated, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteRule(ruleId: Long) {
        val index = rules.indexOfFirst { it.id == ruleId }
        if (index < 0) return
        val rule = rules[index]
        val current = rules.indexOfFirst { it.id == ruleId }
        if (current >= 0) {
            PendingRouteDeletions.add(ruleId)
            rules.removeAt(current)
            runOnDefaultDispatcher { ProfileManager.deleteRules(listOf(rule)) }
            undoManager.remove(current to rule)
        }
    }

    private fun move(from: Int, requestedTo: Int) {
        if (from !in rules.indices) return
        val to = requestedTo.coerceIn(rules.indices)
        if (from == to) return
        val rule = rules.removeAt(from)
        rules.add(to, rule)
        rules.forEachIndexed { index, item ->
            val order = (index + 1).toLong()
            if (item.userOrder != order) {
                val updated = item.copy(userOrder = order)
                rules[index] = updated
                movedRules += updated
            }
        }
    }

    private fun commitMove() {
        if (movedRules.isEmpty()) return
        val updates = movedRules.toList()
        movedRules.clear()
        runOnDefaultDispatcher {
            SagerDatabase.rulesDao.updateRules(updates)
            onMainDispatcher { needReload() }
        }
    }

    override fun undo(actions: List<Pair<Int, RuleEntity>>) {
        actions.forEach { (requestedIndex, rule) ->
            PendingRouteDeletions.remove(rule.id)
            rules.add(requestedIndex.coerceIn(0, rules.size), rule)
        }
        runOnDefaultDispatcher {
            actions.forEach { (_, rule) ->
                if (SagerDatabase.rulesDao.getById(rule.id) == null) {
                    SagerDatabase.rulesDao.createRule(rule)
                }
            }
            onMainDispatcher { needReload() }
        }
    }

    override fun commit(actions: List<Pair<Int, RuleEntity>>) {
        actions.forEach { PendingRouteDeletions.remove(it.second.id) }
    }

    override suspend fun onAdd(rule: RuleEntity) {
        onMainDispatcher {
            if (::undoManager.isInitialized) undoManager.flush()
            if (!PendingRouteDeletions.contains(rule.id) && rules.none { it.id == rule.id }) {
                rules += rule
            }
            needReload()
        }
    }

    override suspend fun onUpdated(rule: RuleEntity) {
        onMainDispatcher {
            if (PendingRouteDeletions.contains(rule.id)) return@onMainDispatcher
            val index = rules.indexOfFirst { it.id == rule.id }
            if (index >= 0) rules[index] = rule
            needReload()
        }
    }

    override suspend fun onRemoved(ruleId: Long) {
        onMainDispatcher {
            rules.removeAll { it.id == ruleId }
            needReload()
        }
    }

    override suspend fun onCleared() {
        onMainDispatcher {
            rules.clear()
            needReload()
        }
    }

}
