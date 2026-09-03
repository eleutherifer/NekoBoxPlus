package io.nekohasekai.sagernet.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.toolbar.ProfileToolbarActionCatalog
import io.nekohasekai.sagernet.ui.toolbar.ProfileToolbarActionId
import io.nekohasekai.sagernet.ui.toolbar.ProfileToolbarLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolbarLayoutScreen(
    layout: ProfileToolbarLayout,
    showRestoreConfirmation: Boolean,
    onClose: () -> Unit,
    onToggle: (ProfileToolbarActionId, Boolean) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRequestRestore: () -> Unit,
    onDismissRestore: () -> Unit,
    onRestore: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.toolbar_layout)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painterResource(R.drawable.baseline_arrow_back_24),
                            contentDescription = stringResource(R.string.mal_close),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRequestRestore) {
                        Icon(
                            painterResource(R.drawable.baseline_undo_24),
                            contentDescription = stringResource(R.string.toolbar_restore_default),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        ToolbarActionsList(
            layout = layout,
            onToggle = onToggle,
            onMove = onMove,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .navigationBarsPadding(),
        )
    }
    if (showRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissRestore,
            title = { Text(stringResource(R.string.toolbar_restore_default)) },
            text = { Text(stringResource(R.string.toolbar_restore_default_confirmation)) },
            confirmButton = {
                TextButton(onClick = onRestore) {
                    Text(stringResource(R.string.toolbar_restore_default))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRestore) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ToolbarActionsList(
    layout: ProfileToolbarLayout,
    onToggle: (ProfileToolbarActionId, Boolean) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draggedId by remember { mutableStateOf<ProfileToolbarActionId?>(null) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ACTION_ROW_HEIGHT.toPx() }

    LazyColumn(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item(key = "active-header") {
            SectionHeader(stringResource(R.string.toolbar_actions_active))
        }
        items(layout.active, key = { "active-${it.value}" }) { id ->
            ToolbarActionItem(
                id = id,
                active = true,
                toggleEnabled = true,
                dragging = draggedId == id,
                dragOffset = if (draggedId == id) dragOffset else 0f,
                onToggle = { onToggle(id, it) },
                modifier = Modifier.pointerInput(id, layout.active.size) {
                    detectDragGestures(
                        onDragStart = {
                            draggedId = id
                            draggedIndex = layout.active.indexOf(id)
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            draggedId = null
                            draggedIndex = -1
                            dragOffset = 0f
                        },
                        onDragEnd = {
                            draggedId = null
                            draggedIndex = -1
                            dragOffset = 0f
                        },
                    ) { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                        while (dragOffset > rowHeightPx / 2f && draggedIndex < layout.active.lastIndex) {
                            onMove(draggedIndex, draggedIndex + 1)
                            draggedIndex++
                            dragOffset -= rowHeightPx
                        }
                        while (dragOffset < -rowHeightPx / 2f && draggedIndex > 0) {
                            onMove(draggedIndex, draggedIndex - 1)
                            draggedIndex--
                            dragOffset += rowHeightPx
                        }
                    }
                },
            )
        }
        item(key = "inactive-header") {
            SectionHeader(stringResource(R.string.toolbar_actions_inactive))
        }
        items(layout.inactive, key = { "inactive-${it.value}" }) { id ->
            ToolbarActionItem(
                id = id,
                active = false,
                toggleEnabled = layout.active.size < ProfileToolbarLayout.MAX_ACTIVE_ACTIONS,
                dragging = false,
                dragOffset = 0f,
                onToggle = { onToggle(id, it) },
                modifier = Modifier,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun ToolbarActionItem(
    id: ProfileToolbarActionId,
    active: Boolean,
    toggleEnabled: Boolean,
    dragging: Boolean,
    dragOffset: Float,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
) {
    val action = ProfileToolbarActionCatalog[id]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ACTION_ROW_HEIGHT)
            .graphicsLayer {
                translationY = dragOffset
                shadowElevation = if (dragging) 8.dp.toPx() else 0f
            }
            .tvFocusTarget(toggleEnabled)
            .toggleable(
                value = active,
                enabled = toggleEnabled,
                onValueChange = onToggle,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = active,
            onCheckedChange = null,
            enabled = toggleEnabled,
            modifier = Modifier.size(48.dp),
        )
        Icon(
            painterResource(action.iconRes),
            contentDescription = null,
            modifier = Modifier.padding(8.dp).size(24.dp),
        )
        Text(
            text = stringResource(action.titleRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Icon(
                painterResource(R.drawable.ic_navigation_menu),
                contentDescription = stringResource(R.string.toolbar_reorder_action),
                modifier = modifier.padding(12.dp).size(24.dp),
            )
        }
    }
}

private val ACTION_ROW_HEIGHT = 64.dp
