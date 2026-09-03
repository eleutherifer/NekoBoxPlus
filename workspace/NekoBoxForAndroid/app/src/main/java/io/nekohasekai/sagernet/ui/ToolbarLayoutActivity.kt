package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.ToolbarLayoutScreen
import io.nekohasekai.sagernet.ui.toolbar.ProfileToolbarActionId
import io.nekohasekai.sagernet.ui.toolbar.ProfileToolbarLayout

class ToolbarLayoutActivity : ThemedActivity() {
    private var layout by mutableStateOf(ProfileToolbarLayout.DEFAULT)
    private var showRestoreConfirmation by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layout = ProfileToolbarLayout.decode(DataStore.toolbarLayout)
        setContent {
            NekoComposeTheme {
                ToolbarLayoutScreen(
                    layout = layout,
                    showRestoreConfirmation = showRestoreConfirmation,
                    onClose = ::finish,
                    onToggle = ::toggle,
                    onMove = ::move,
                    onRequestRestore = { showRestoreConfirmation = true },
                    onDismissRestore = { showRestoreConfirmation = false },
                    onRestore = ::restoreDefault,
                )
            }
        }
    }

    private fun toggle(id: ProfileToolbarActionId, active: Boolean) {
        updateLayout(if (active) layout.activate(id) else layout.deactivate(id))
    }

    private fun move(fromIndex: Int, toIndex: Int) {
        updateLayout(layout.move(fromIndex, toIndex))
    }

    private fun updateLayout(updated: ProfileToolbarLayout) {
        if (updated == layout) return
        layout = updated
        DataStore.toolbarLayout = ProfileToolbarLayout.encode(updated)
    }

    private fun restoreDefault() {
        showRestoreConfirmation = false
        layout = ProfileToolbarLayout.DEFAULT
        DataStore.toolbarLayout = ""
    }
}
