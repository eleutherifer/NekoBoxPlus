package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutToolbarLayoutBinding
import io.nekohasekai.sagernet.ui.toolbar.ProfileToolbarActionCatalog
import io.nekohasekai.sagernet.ui.toolbar.ProfileToolbarActionId
import io.nekohasekai.sagernet.ui.toolbar.ProfileToolbarLayout
import io.nekohasekai.sagernet.widget.ListListener

class ToolbarLayoutActivity : ThemedActivity() {
    private lateinit var binding: LayoutToolbarLayoutBinding
    private lateinit var adapter: ToolbarActionAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutToolbarLayoutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(R.string.toolbar_layout)
            setNavigationIcon(R.drawable.baseline_arrow_back_24)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.toolbar_layout_menu)
            setOnMenuItemClickListener {
                if (it.itemId == R.id.action_restore_default_toolbar) {
                    confirmRestoreDefault()
                    true
                } else {
                    false
                }
            }
        }

        adapter = ToolbarActionAdapter()
        binding.toolbarActionList.layoutManager = LinearLayoutManager(this)
        binding.toolbarActionList.adapter = adapter
        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled() = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                val row = adapter.rowAt(viewHolder.bindingAdapterPosition)
                return if (row is ToolbarActionRow.Action && row.active) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    makeMovementFlags(0, 0)
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return adapter.moveActive(
                    viewHolder.bindingAdapterPosition,
                    target.bindingAdapterPosition,
                )
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).also { it.attachToRecyclerView(binding.toolbarActionList) }
    }

    private fun confirmRestoreDefault() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.toolbar_restore_default)
            .setMessage(R.string.toolbar_restore_default_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.toolbar_restore_default) { _, _ ->
                adapter.restoreDefault()
            }
            .show()
    }

    private sealed interface ToolbarActionRow {
        data class Header(val titleRes: Int) : ToolbarActionRow
        data class Action(val id: ProfileToolbarActionId, val active: Boolean) : ToolbarActionRow
    }

    private inner class ToolbarActionAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var layout = ProfileToolbarLayout.decode(DataStore.toolbarLayout)
        private var rows = buildRows()

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is ToolbarActionRow.Header -> VIEW_TYPE_HEADER
            is ToolbarActionRow.Action -> VIEW_TYPE_ACTION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_TYPE_HEADER) {
                HeaderHolder(inflater.inflate(R.layout.item_toolbar_action_header, parent, false))
            } else {
                ActionHolder(inflater.inflate(R.layout.item_toolbar_action, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderHolder -> holder.bind(rows[position] as ToolbarActionRow.Header)
                is ActionHolder -> holder.bind(rows[position] as ToolbarActionRow.Action)
            }
        }

        fun rowAt(position: Int): ToolbarActionRow? = rows.getOrNull(position)

        fun moveActive(fromPosition: Int, toPosition: Int): Boolean {
            val from = rows.getOrNull(fromPosition) as? ToolbarActionRow.Action ?: return false
            val to = rows.getOrNull(toPosition) as? ToolbarActionRow.Action ?: return false
            if (!from.active || !to.active) return false
            val fromIndex = layout.active.indexOf(from.id)
            val toIndex = layout.active.indexOf(to.id)
            if (fromIndex < 0 || toIndex < 0) return false
            layout = layout.move(fromIndex, toIndex)
            DataStore.toolbarLayout = ProfileToolbarLayout.encode(layout)
            rows = buildRows()
            notifyItemMoved(fromPosition, toPosition)
            return true
        }

        private fun buildRows() = buildList {
            add(ToolbarActionRow.Header(R.string.toolbar_actions_active))
            layout.active.forEach { add(ToolbarActionRow.Action(it, true)) }
            add(ToolbarActionRow.Header(R.string.toolbar_actions_inactive))
            layout.inactive.forEach { add(ToolbarActionRow.Action(it, false)) }
        }

        private fun toggle(id: ProfileToolbarActionId, active: Boolean) {
            layout = if (active) layout.activate(id) else layout.deactivate(id)
            persistAndRefresh()
        }

        private fun persistAndRefresh() {
            DataStore.toolbarLayout = ProfileToolbarLayout.encode(layout)
            rows = buildRows()
            notifyDataSetChanged()
        }

        fun restoreDefault() {
            layout = ProfileToolbarLayout.DEFAULT
            DataStore.toolbarLayout = ""
            rows = buildRows()
            notifyDataSetChanged()
        }

        private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(row: ToolbarActionRow.Header) {
                (itemView as TextView).setText(row.titleRes)
            }
        }

        private inner class ActionHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val checkbox: MaterialCheckBox = view.findViewById(R.id.action_enabled)
            private val icon: ImageView = view.findViewById(R.id.action_icon)
            private val title: TextView = view.findViewById(R.id.action_title)
            private val dragHandle: ImageView = view.findViewById(R.id.action_drag_handle)

            fun bind(row: ToolbarActionRow.Action) {
                val action = ProfileToolbarActionCatalog[row.id]
                checkbox.setOnCheckedChangeListener(null)
                checkbox.isChecked = row.active
                checkbox.isEnabled = row.active ||
                    layout.active.size < ProfileToolbarLayout.MAX_ACTIVE_ACTIONS
                checkbox.contentDescription = getString(
                    R.string.toolbar_action_checkbox_description,
                    getString(action.titleRes),
                )
                checkbox.setOnCheckedChangeListener { _, checked -> toggle(row.id, checked) }
                icon.setImageResource(action.iconRes)
                title.setText(action.titleRes)
                dragHandle.isVisible = row.active
                dragHandle.setOnClickListener {
                    if (row.active) itemTouchHelper.startDrag(this)
                }
                dragHandle.setOnTouchListener { view, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && row.active) {
                        view.performClick()
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ACTION = 1
    }
}
