package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import com.google.android.material.appbar.MaterialToolbar
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileTransferOperation
import io.nekohasekai.sagernet.database.ProfileTransferPolicy
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutGroupPickerBinding
import io.nekohasekai.sagernet.databinding.LayoutGroupPickerItemBinding
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListListener

class GroupPickerActivity : ThemedActivity(R.layout.layout_group_picker) {

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
        private const val EXTRA_OPERATION = "operation"
        private const val EXTRA_VISIBLE_GROUP_IDS = "visible_group_ids"

        fun createIntent(context: Context, operation: ProfileTransferOperation) =
            Intent(context, GroupPickerActivity::class.java).apply {
                putExtra(EXTRA_OPERATION, operation.name)
            }

        fun createNavigationIntent(context: Context, visibleGroupIds: LongArray) =
            Intent(context, GroupPickerActivity::class.java).apply {
                putExtra(EXTRA_VISIBLE_GROUP_IDS, visibleGroupIds)
            }
    }

    private lateinit var binding: LayoutGroupPickerBinding
    private val adapter = GroupAdapter()
    private var visibleGroupIds: LongArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutGroupPickerBinding.bind(findViewById(R.id.group_picker_root))

        val operation = intent.getStringExtra(EXTRA_OPERATION)
            ?.let { runCatching { ProfileTransferOperation.valueOf(it) }.getOrNull() }
        visibleGroupIds = intent.getLongArrayExtra(EXTRA_VISIBLE_GROUP_IDS)
        if (operation == null && visibleGroupIds == null) {
            finish()
            return
        }

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(
                when (operation) {
                    ProfileTransferOperation.COPY -> R.string.copy
                    ProfileTransferOperation.MOVE -> R.string.move
                    null -> R.string.go_to
                }
            )
            setDisplayHomeAsUpEnabled(true)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.groupList, ListListener)
        binding.groupList.layoutManager = FixedLinearLayoutManager(binding.groupList)
        binding.groupList.adapter = adapter
        loadGroups()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadGroups() {
        runOnDefaultDispatcher {
            val allGroups = SagerDatabase.groupDao.allGroups()
            val groups = visibleGroupIds?.let {
                GroupTabSelectionPolicy.navigatorGroups(allGroups, it)
            } ?: ProfileTransferPolicy.eligibleGroups(allGroups)
            binding.groupList.post {
                adapter.groups.clear()
                adapter.groups.addAll(groups)
                adapter.notifyDataSetChanged()
                binding.empty.isVisible = groups.isEmpty()
            }
        }
    }

    private inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>() {
        val groups = mutableListOf<ProxyGroup>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            GroupHolder(LayoutGroupPickerItemBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount() = groups.size

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groups[position])
        }
    }

    private inner class GroupHolder(
        private val itemBinding: LayoutGroupPickerItemBinding,
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        fun bind(group: ProxyGroup) {
            itemBinding.groupName.text = group.displayName()
            itemBinding.root.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_GROUP_ID, group.id))
                finish()
            }
        }
    }
}
