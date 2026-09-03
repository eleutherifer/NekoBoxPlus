package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileTransferOperation
import io.nekohasekai.sagernet.database.ProfileTransferPolicy
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ui.compose.GroupPickerItem
import io.nekohasekai.sagernet.ui.compose.GroupPickerScreen
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupPickerActivity : ThemedActivity() {

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

    private var groups by mutableStateOf<List<GroupPickerItem>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val operation = intent.getStringExtra(EXTRA_OPERATION)
            ?.let { runCatching { ProfileTransferOperation.valueOf(it) }.getOrNull() }
        val visibleGroupIds = intent.getLongArrayExtra(EXTRA_VISIBLE_GROUP_IDS)
        if (operation == null && visibleGroupIds == null) {
            finish()
            return
        }
        val titleRes = when (operation) {
            ProfileTransferOperation.COPY -> R.string.copy
            ProfileTransferOperation.MOVE -> R.string.move
            null -> R.string.go_to
        }

        setContent {
            NekoComposeTheme {
                GroupPickerScreen(
                    titleRes = titleRes,
                    groups = groups,
                    onClose = ::finish,
                    onGroupSelected = ::selectGroup,
                )
            }
        }
        loadGroups(visibleGroupIds)
    }

    private fun loadGroups(visibleGroupIds: LongArray?) {
        lifecycleScope.launch {
            groups = withContext(Dispatchers.Default) {
                val allGroups = SagerDatabase.groupDao.allGroups()
                val eligibleGroups = visibleGroupIds?.let {
                    GroupTabSelectionPolicy.navigatorGroups(allGroups, it)
                } ?: ProfileTransferPolicy.eligibleGroups(allGroups)
                eligibleGroups.map { GroupPickerItem(it.id, it.displayName()) }
            }
        }
    }

    private fun selectGroup(groupId: Long) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_GROUP_ID, groupId))
        finish()
    }
}
