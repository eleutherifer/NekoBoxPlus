package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleType
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutEmptyRouteBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.databinding.LayoutRoutingExportNameDialogBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.routing.RoutingExportWarning
import io.nekohasekai.sagernet.routing.RoutingProfileExporter
import io.nekohasekai.sagernet.routing.RoutingProfileFormat
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager

class RouteFragment : ToolbarFragment(R.layout.layout_route), Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var ruleListView: RecyclerView
    lateinit var ruleAdapter: RuleAdapter
    lateinit var undoManager: UndoSnackbarManager<RuleEntity>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_route)
        toolbar.inflateMenu(R.menu.add_route_menu)
        toolbar.setOnMenuItemClickListener(this)

        ruleListView = view.findViewById(R.id.route_list)
        ruleListView.layoutManager = FixedLinearLayoutManager(ruleListView)
        ruleAdapter = RuleAdapter()
        ProfileManager.addListener(ruleAdapter)
        ruleListView.adapter = ruleAdapter
        undoManager = UndoSnackbarManager(activity, ruleAdapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is RuleAdapter.DocumentHolder) {
                0
            } else {
                super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is RuleAdapter.DocumentHolder) {
                0
            } else {
                super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION) return
                val rule = (viewHolder as RuleAdapter.RuleHolder).rule
                if (DataStore.confirmProfileDelete) {
                    var confirmed = false
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.delete_route_prompt)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            confirmed = true
                            ruleAdapter.remove(index)
                            undoManager.remove(index to rule)
                        }
                        .setNegativeButton(R.string.no, null)
                        .setOnDismissListener {
                            if (!confirmed) ruleAdapter.notifyItemChanged(index)
                        }
                        .show()
                } else {
                    ruleAdapter.remove(index)
                    undoManager.remove(index to rule)
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target is RuleAdapter.DocumentHolder) {
                    false
                } else {
                    ruleAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    true
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                ruleAdapter.commitMove()
            }
        }).attachToRecyclerView(ruleListView)
    }

    override fun onDestroy() {
        if (::ruleAdapter.isInitialized) {
            ProfileManager.removeListener(ruleAdapter)
        }
        super.onDestroy()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_route -> {
                val anchor = toolbar.findViewById<View>(R.id.action_new_route) ?: toolbar
                PopupMenu(requireContext(), anchor).apply {
                    menu.add(Menu.NONE, RuleType.NORMAL.ordinal, Menu.NONE, R.string.route_normal)
                    menu.add(Menu.NONE, RuleType.DNS.ordinal, Menu.NONE, R.string.dns_rule)
                    setOnMenuItemClickListener { routeTypeItem ->
                        startActivity(Intent(context, RouteSettingsActivity::class.java).apply {
                            if (routeTypeItem.itemId == RuleType.DNS.ordinal) {
                                putExtra(RouteSettingsActivity.EXTRA_ROUTE_TYPE, RuleType.DNS.value)
                            }
                        })
                        true
                    }
                    show()
                }
            }
            R.id.action_reset_route -> {
                MaterialAlertDialogBuilder(activity).setTitle(R.string.confirm)
                    .setMessage(R.string.clear_profiles_message)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            SagerDatabase.rulesDao.reset()
                            DataStore.rulesFirstCreate = false
                            ruleAdapter.reload()
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
            R.id.action_manage_assets -> {
                startActivity(Intent(requireContext(), AssetsActivity::class.java))
            }
            R.id.action_import_routing_clipboard -> {
                activity.requestRoutingImport(SagerNet.getClipboardText())
            }
            R.id.action_export_routing_happ_clipboard -> requestRoutingExport(
                RoutingProfileFormat.HAPP, RoutingExportDestination.CLIPBOARD,
            )
            R.id.action_export_routing_happ_share -> requestRoutingExport(
                RoutingProfileFormat.HAPP, RoutingExportDestination.SHARE,
            )
            R.id.action_export_routing_happ_qr -> requestRoutingExport(
                RoutingProfileFormat.HAPP, RoutingExportDestination.QR_CODE,
            )
            R.id.action_export_routing_incy_clipboard -> requestRoutingExport(
                RoutingProfileFormat.INCY, RoutingExportDestination.CLIPBOARD,
            )
            R.id.action_export_routing_incy_share -> requestRoutingExport(
                RoutingProfileFormat.INCY, RoutingExportDestination.SHARE,
            )
            R.id.action_export_routing_incy_qr -> requestRoutingExport(
                RoutingProfileFormat.INCY, RoutingExportDestination.QR_CODE,
            )
            R.id.action_export_routing_nekobox_plus_clipboard -> requestRoutingExport(
                RoutingProfileFormat.NEKOBOX_PLUS, RoutingExportDestination.CLIPBOARD,
            )
            R.id.action_export_routing_nekobox_plus_share -> requestRoutingExport(
                RoutingProfileFormat.NEKOBOX_PLUS, RoutingExportDestination.SHARE,
            )
            R.id.action_export_routing_nekobox_plus_qr -> requestRoutingExport(
                RoutingProfileFormat.NEKOBOX_PLUS, RoutingExportDestination.QR_CODE,
            )
        }
        return true
    }

    private enum class RoutingExportDestination { CLIPBOARD, SHARE, QR_CODE }

    private fun requestRoutingExport(
        format: RoutingProfileFormat,
        destination: RoutingExportDestination,
    ) {
        val dialogBinding = LayoutRoutingExportNameDialogBinding.inflate(layoutInflater)
        val input = dialogBinding.routingName.apply {
            setText(R.string.routing_export_default_name)
            selectAll()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.routing_export_name)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_export) { _, _ ->
                val name = input.text?.toString().orEmpty()
                runOnDefaultDispatcher {
                    val result = RoutingProfileExporter.export(
                        format,
                        name,
                        SagerDatabase.rulesDao.allRules(),
                    )
                    onMainDispatcher {
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
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.routing_export_complete_with_warnings)
                                .setMessage(result.warnings.joinToString("\n") { warningText(it) })
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                }
            }
            .show()
    }

    private fun warningText(warning: RoutingExportWarning): String = getString(when (warning) {
        RoutingExportWarning.UNSUPPORTED_RULES -> R.string.routing_export_warning_unsupported_rules
        RoutingExportWarning.SIMPLIFIED_ORDER -> R.string.routing_export_warning_order
        RoutingExportWarning.DNS_VALUES_OMITTED -> R.string.routing_export_warning_dns
        RoutingExportWarning.DNS_HOST_VALUES_OMITTED -> R.string.routing_export_warning_hosts
        RoutingExportWarning.CUSTOM_OUTBOUND_FALLBACK -> R.string.routing_export_warning_outbound_fallback
    })

    inner class RuleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ProfileManager.RuleListener, UndoSnackbarManager.Interface<RuleEntity> {

        val ruleList = ArrayList<RuleEntity>()
        suspend fun reload() {
            val rules = ProfileManager.getRules()
            ruleListView.post {
                ruleList.clear()
                ruleList.addAll(rules)
                ruleAdapter.notifyDataSetChanged()
            }
        }

        init {
            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                DocumentHolder(LayoutEmptyRouteBinding.inflate(layoutInflater, parent, false))
            } else {
                RuleHolder(LayoutRouteItemBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return 0
            return 1
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is DocumentHolder) {
                holder.bind()
            } else if (holder is RuleHolder) {
                holder.bind(ruleList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return ruleList.size + 1
        }

        override fun getItemId(position: Int): Long {
            if (position == 0) return 0L
            return ruleList[position - 1].id
        }

        private val updated = HashSet<RuleEntity>()
        fun move(from: Int, to: Int) {
            val first = ruleList[from - 1]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from - 1 until to - 1) else Pair(-1, to downTo from - 1)
            for (i in range) {
                val next = ruleList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                ruleList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            ruleList[to - 1] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            if (updated.isNotEmpty()) {
                SagerDatabase.rulesDao.updateRules(updated.toList())
                updated.clear()
                needReload()
            }
        }

        fun remove(index: Int) {
            ruleList.removeAt(index - 1)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, RuleEntity>>) {
            for ((index, item) in actions) {
                ruleList.add(index - 1, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, RuleEntity>>) {
            val rules = actions.map { it.second }
            runOnDefaultDispatcher {
                ProfileManager.deleteRules(rules)
            }
        }

        override suspend fun onAdd(rule: RuleEntity) {
            ruleListView.post {
                ruleList.add(rule)
                ruleAdapter.notifyItemInserted(ruleList.size)
                needReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            ruleListView.post {
                val index = ruleList.indexOfFirst { it.id == rule.id }
                if (index == -1) return@post
                ruleList[index] = rule
                ruleAdapter.notifyItemChanged(index + 1)
                needReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) {
            ruleListView.post {
                val index = ruleList.indexOfFirst { it.id == ruleId }
                if (index == -1) {
                    needReload()
                    return@post
                }
                ruleList.removeAt(index)
                ruleAdapter.notifyItemRemoved(index + 1)
                needReload()
            }
        }

        override suspend fun onCleared() {
            ruleListView.post {
                ruleList.clear()
                ruleAdapter.notifyDataSetChanged()
                needReload()
            }
        }

        inner class DocumentHolder(binding: LayoutEmptyRouteBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind() {
                itemView.setOnClickListener {
                    it.context.launchCustomTab("https://matsuridayo.github.io/nb4a-route/")
                }
            }
        }

        inner class RuleHolder(binding: LayoutRouteItemBinding) : RecyclerView.ViewHolder(binding.root) {

            lateinit var rule: RuleEntity
            val profileName = binding.profileName
            val profileType = binding.profileType
            val routeOutbound = binding.routeOutbound
            val editButton = binding.edit
            val duplicateButton = binding.duplicate
            val shareLayout = binding.share
            val enableSwitch = binding.enable
            val card = binding.content
            val dnsRuleIcon = binding.dnsRuleIcon

            fun bind(ruleEntity: RuleEntity) {
                rule = ruleEntity
                val isDnsRule = RuleType.fromValue(rule.type) == RuleType.DNS
                dnsRuleIcon.isVisible = isDnsRule
                profileName.text = rule.displayName()
                profileType.text = rule.mkSummary()
                routeOutbound.text = rule.displayOutbound()
                card.setCardBackgroundColor(
                    itemView.context.getColorAttr(
                        com.google.android.material.R.attr.colorSurface
                    ),
                )

                // 根据路由类型设置文字颜色
                val colorRes = when (rule.outbound) {
                    -2L -> R.color.color_route_block   // 屏蔽：红色
                    -1L -> R.color.color_route_direct  // 直连：绿色
                    0L -> R.color.color_route_proxy    // 代理：蓝色
                    else -> R.color.color_route_config // 配置：紫色
                }
                routeOutbound.setTextColor(ContextCompat.getColor(itemView.context, colorRes))

                itemView.setOnClickListener(null)
                itemView.isClickable = false
                itemView.isFocusable = false
                enableSwitch.setOnCheckedChangeListener(null)
                enableSwitch.isChecked = rule.enabled
                enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    runOnDefaultDispatcher {
                        rule.enabled = isChecked
                        SagerDatabase.rulesDao.updateRule(rule)
                        onMainDispatcher {
                            needReload()
                        }
                    }
                }
                editButton.setOnClickListener {
                    startActivity(Intent(it.context, RouteSettingsActivity::class.java).apply {
                        putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, rule.id)
                    })
                }
                TooltipCompat.setTooltipText(duplicateButton, getString(R.string.duplicate))
                duplicateButton.setOnClickListener {
                    val ruleToDuplicate = rule
                    runOnDefaultDispatcher {
                        ProfileManager.duplicateRuleAfter(ruleToDuplicate)
                        reload()
                        onMainDispatcher {
                            needReload()
                            Toast.makeText(requireContext(), R.string.route_duplicated, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    }

}
