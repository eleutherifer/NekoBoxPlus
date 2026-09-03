package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.compose.showComposeMessageDialog
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GroupInterfaceAdapter(val context: ThemedActivity) : GroupManager.Interface {

    override suspend fun confirm(message: String): Boolean {
        return suspendCoroutine {
            runOnMainDispatcher {
                context.showComposeMessageDialog(
                    title = context.getText(R.string.confirm),
                    message = message,
                    positiveButton = context.getText(R.string.yes),
                    negativeButton = context.getText(R.string.no),
                    onPositive = { it.resume(true) },
                    onNegative = { it.resume(false) },
                    onCancel = { it.resume(false) },
                )
            }
        }
    }

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
        byUser: Boolean
    ) {
        if (!byUser) return

        if (changed == 0 && duplicate.isEmpty()) {
            context.snackbar(
                    context.getString(
                            R.string.group_no_difference, group.displayName()
                    )
            ).show()
        } else {
            context.snackbar(context.getString(R.string.group_updated, group.name, changed)).show()

            var status = ""
            if (added.isNotEmpty()) {
                status += context.getString(
                        R.string.group_added, added.joinToString("\n", postfix = "\n\n")
                )
            }
            if (updated.isNotEmpty()) {
                status += context.getString(R.string.group_changed,
                        updated.map { it }.joinToString("\n", postfix = "\n\n") {
                            if (it.key == it.value) it.key else "${it.key} => ${it.value}"
                        })
            }
            if (deleted.isNotEmpty()) {
                status += context.getString(
                        R.string.group_deleted, deleted.joinToString("\n", postfix = "\n\n")
                )
            }
            if (duplicate.isNotEmpty()) {
                status += context.getString(
                        R.string.group_duplicate, duplicate.joinToString("\n", postfix = "\n\n")
                )
            }

            if (!DataStore.enableGroupUpdateDialog) return

            onMainDispatcher {
                delay(1000L)

                context.showComposeMessageDialog(
                    title = context.getString(R.string.group_diff, group.displayName()),
                    message = status.trim(),
                )
            }

        }

    }

    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
        onMainDispatcher {
            context.snackbar(message).show()
        }
    }


    override suspend fun alert(message: String) {
        return suspendCoroutine {
            runOnMainDispatcher {
                context.showComposeMessageDialog(
                    title = context.getText(R.string.ooc_warning),
                    message = message,
                    onPositive = { it.resume(Unit) },
                    onCancel = { it.resume(Unit) },
                )
            }
        }
    }

}
