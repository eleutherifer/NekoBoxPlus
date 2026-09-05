package io.nekohasekai.sagernet.ui.compose

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import io.nekohasekai.sagernet.R

data class GroupUiItem(
    val id: Long,
    val name: String,
    val username: String,
    val status: String,
    val traffic: String?,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val canDrag: Boolean,
    val canUpdate: Boolean,
    val isUpdating: Boolean,
    val progress: Float?,
    val canShareSubscription: Boolean,
    val canShareSubscriptionUrl: Boolean,
)

enum class GroupAction {
    Edit,
    Update,
    ShareUrlClipboard,
    ShareUrlQr,
    ShareUniversalClipboard,
    ShareUniversalQr,
    ExportClipboard,
    ExportFile,
    Clear,
    Delete,
}

private enum class GroupActionSubmenu(@param:StringRes val title: Int) {
    ShareUrl(R.string.share_subscription_url),
    ShareUniversal(R.string.share_subscription),
    Export(R.string.action_export),
}

enum class AddGroupAction { New, Clipboard, ScanQr }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    groups: List<GroupUiItem>,
    loading: Boolean,
    onOpenDrawer: () -> Unit,
    onUpdateAll: () -> Unit,
    onAdd: (AddGroupAction) -> Unit,
    onAction: (Long, GroupAction) -> Unit,
    shouldConfirmDelete: () -> Boolean,
    onMove: (Int, Int) -> Unit,
    onMoveFinished: () -> Unit,
) {
    var showUpdateAllConfirmation by remember { mutableStateOf(false) }
    var pendingClearGroupId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteGroupId by remember { mutableStateOf<Long?>(null) }
    var movingGroupId by remember { mutableStateOf<Long?>(null) }
    var tvDeletingGroupId by remember { mutableStateOf<Long?>(null) }

    fun finishTvMove() {
        if (movingGroupId == null) return
        movingGroupId = null
        onMoveFinished()
    }

    BackHandler(enabled = movingGroupId != null, onBack = ::finishTvMove)
    LaunchedEffect(groups.map(GroupUiItem::id), tvDeletingGroupId) {
        if (tvDeletingGroupId != null && groups.none { it.id == tvDeletingGroupId }) {
            tvDeletingGroupId = null
        }
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            val fromIndex = groups.indexOfFirst { it.id == from }
            val toIndex = groups.indexOfFirst { it.id == to }
            if (fromIndex >= 0 && toIndex >= 0) onMove(fromIndex, toIndex)
        },
        onMoveFinished = onMoveFinished,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_group)) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            painterResource(R.drawable.ic_navigation_menu),
                            contentDescription = stringResource(R.string.menu_group),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showUpdateAllConfirmation = true }) {
                        Icon(
                            painterResource(R.drawable.ic_baseline_update_24),
                            contentDescription = stringResource(R.string.update_all_subscription),
                        )
                    }
                    AddGroupMenu(onAdd)
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state = reorderState.listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups, key = { group -> group.id }) { group ->
                    val isDragging = reorderState.isDragging(group.id)
                    GroupRow(
                        group = group,
                        modifier = (if (isDragging || tvDeletingGroupId != null) {
                            Modifier
                        } else {
                            Modifier.animateItem()
                        })
                            .zIndex(if (isDragging) 1f else 0f),
                        onAction = { groupId, action ->
                            when (action) {
                                GroupAction.Clear -> pendingClearGroupId = groupId
                                GroupAction.Delete -> {
                                    if (shouldConfirmDelete()) {
                                        pendingDeleteGroupId = groupId
                                    } else {
                                        onAction(groupId, action)
                                    }
                                }
                                else -> onAction(groupId, action)
                            }
                        },
                        reorderState = reorderState,
                        moving = movingGroupId == group.id,
                        moveModeActive = movingGroupId != null,
                        onTvDelete = {
                            tvDeletingGroupId = group.id
                            if (shouldConfirmDelete()) pendingDeleteGroupId = group.id
                            else onAction(group.id, GroupAction.Delete)
                        },
                        onTvMoveStarted = { movingGroupId = group.id },
                        onTvMoveBy = { delta ->
                            val from = groups.indexOfFirst { it.id == group.id }
                            if (from >= 0) onMove(from, from + delta)
                        },
                        onTvMoveFinished = ::finishTvMove,
                    )
                }
            }
            if (loading && groups.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    if (showUpdateAllConfirmation) {
        GroupConfirmationDialog(
            title = stringResource(R.string.confirm),
            message = stringResource(R.string.update_all_subscription),
            dismissText = stringResource(R.string.no),
            onDismiss = { showUpdateAllConfirmation = false },
            onConfirm = {
                showUpdateAllConfirmation = false
                onUpdateAll()
            },
        )
    }

    pendingClearGroupId?.let { groupId ->
        GroupConfirmationDialog(
            title = stringResource(R.string.confirm),
            message = stringResource(R.string.clear_profiles_message),
            dismissText = stringResource(android.R.string.cancel),
            onDismiss = { pendingClearGroupId = null },
            onConfirm = {
                pendingClearGroupId = null
                onAction(groupId, GroupAction.Clear)
            },
        )
    }

    pendingDeleteGroupId?.let { groupId ->
        GroupConfirmationDialog(
            title = stringResource(R.string.delete_group_prompt),
            dismissText = stringResource(R.string.no),
            onDismiss = {
                pendingDeleteGroupId = null
                tvDeletingGroupId = null
            },
            onConfirm = {
                pendingDeleteGroupId = null
                onAction(groupId, GroupAction.Delete)
            },
        )
    }
}

@Composable
private fun GroupConfirmationDialog(
    title: String,
    message: String? = null,
    dismissText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    GroupDialogSurface(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = if (message == null) 16.dp else 0.dp, end = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(dismissText) }
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.yes)) }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun GroupDialogSurface(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            content = content,
        )
    }
}

@Composable
private fun AddGroupMenu(onAdd: (AddGroupAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painterResource(R.drawable.ic_av_playlist_add),
                contentDescription = stringResource(R.string.group_create),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AddGroupAction.entries.forEach { action ->
                val title = when (action) {
                    AddGroupAction.New -> R.string.action_new
                    AddGroupAction.Clipboard -> R.string.action_from_clipboard
                    AddGroupAction.ScanQr -> R.string.add_profile_methods_scan_qr_code
                }
                DropdownMenuItem(
                    text = { Text(stringResource(title)) },
                    onClick = { expanded = false; onAdd(action) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupRow(
    group: GroupUiItem,
    modifier: Modifier,
    onAction: (Long, GroupAction) -> Unit,
    reorderState: ReorderableLazyListState,
    moving: Boolean,
    moveModeActive: Boolean,
    onTvDelete: () -> Unit,
    onTvMoveStarted: () -> Unit,
    onTvMoveBy: (Int) -> Unit,
    onTvMoveFinished: () -> Unit,
) {
    var deleteRequested by remember(group.id) { mutableStateOf(false) }
    var tvActionsExpanded by remember(group.id) { mutableStateOf(false) }
    val cardFocusRequester = remember(group.id) { FocusRequester() }
    val firstActionFocusRequester = remember(group.id) { FocusRequester() }
    val menuFocusRequester = remember(group.id) { FocusRequester() }
    val isDragging = reorderState.isDragging(group.id)
    LaunchedEffect(moving) {
        if (moving) cardFocusRequester.requestFocus()
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (
                it == SwipeToDismissBoxValue.EndToStart &&
                group.canDelete &&
                !deleteRequested
            ) {
                deleteRequested = true
                onAction(group.id, GroupAction.Delete)
            }
            false
        },
    )
    SwipeToDismissBox(
        modifier = modifier
            .pointerInput(group.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    deleteRequested = false
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                }
            },
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = group.canDelete,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (isDragging) {
                            Modifier.dragTargetOutline(MaterialTheme.colorScheme.primary)
                        } else {
                            Modifier.background(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp),
                            )
                        },
                    )
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (!isDragging) {
                    Icon(
                        painterResource(R.drawable.ic_action_delete),
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
    ) {
        Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(cardFocusRequester)
                .tvCardActions(
                    enabled = !moveModeActive || moving,
                    showFocusIndicator = !moving,
                    onClick = {
                        if (moving) {
                            onTvMoveFinished()
                        } else {
                            firstActionFocusRequester.requestFocus()
                        }
                    },
                    onLongClick = { if (!moveModeActive) tvActionsExpanded = true },
                )
                .onPreviewKeyEvent { event ->
                    if (!moving || event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            onTvMoveBy(-1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            onTvMoveBy(1)
                            true
                        }
                        else -> false
                    }
                }
                .then(
                    if (moving) Modifier.dragTargetOutline(MaterialTheme.colorScheme.primary)
                    else Modifier,
                )
                .reorderableItem(reorderState, group.id, group.canDrag && !moveModeActive),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Box {
                Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = group.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (group.username.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = group.username,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (group.canEdit) {
                            IconButton(
                                onClick = { onAction(group.id, GroupAction.Edit) },
                                enabled = !group.isUpdating,
                                modifier = Modifier
                                    .size(40.dp)
                                    .then(
                                        if (group.canEdit && !group.isUpdating) {
                                            Modifier.focusRequester(firstActionFocusRequester)
                                        } else Modifier,
                                    )
                                    .focusProperties { canFocus = !moveModeActive }
                                    .alpha(if (group.isUpdating) 0f else 1f),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_image_edit),
                                    contentDescription = stringResource(R.string.edit),
                                )
                            }
                        }
                        if (group.canUpdate) {
                            IconButton(
                                onClick = { onAction(group.id, GroupAction.Update) },
                                enabled = !group.isUpdating,
                                modifier = Modifier
                                    .size(40.dp)
                                    .then(
                                        if (
                                            (!group.canEdit || group.isUpdating) &&
                                            group.canUpdate && !group.isUpdating
                                        ) {
                                            Modifier.focusRequester(firstActionFocusRequester)
                                        } else Modifier,
                                    )
                                    .focusProperties { canFocus = !moveModeActive }
                                    .alpha(if (group.isUpdating) 0f else 1f),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_baseline_update_24),
                                    contentDescription = stringResource(R.string.group_update),
                                )
                            }
                        }
                        GroupActionsMenu(
                            group,
                            onAction,
                            moveModeActive,
                            if (group.isUpdating || (!group.canEdit && !group.canUpdate)) {
                                firstActionFocusRequester
                            } else null,
                        )
                    }
                    group.traffic?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = group.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (group.isUpdating) {
                    if (group.progress == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { group.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        DropdownMenu(
            expanded = tvActionsExpanded,
            onDismissRequest = { tvActionsExpanded = false },
        ) {
            TvMenuInitialFocus(menuFocusRequester, tvActionsExpanded)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_action_delete), contentDescription = null)
                },
                enabled = group.canDelete,
                onClick = {
                    tvActionsExpanded = false
                    onTvDelete()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move)) },
                leadingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_baseline_drag_indicator_24),
                        contentDescription = null,
                    )
                },
                enabled = group.canDrag,
                onClick = {
                    tvActionsExpanded = false
                    onTvMoveStarted()
                },
            )
        }
        }
    }
}

@Composable
private fun GroupActionsMenu(
    group: GroupUiItem,
    onAction: (Long, GroupAction) -> Unit,
    moveModeActive: Boolean,
    focusRequester: FocusRequester?,
) {
    var expanded by remember(group.id) { mutableStateOf(false) }
    var submenu by remember(group.id) { mutableStateOf<GroupActionSubmenu?>(null) }
    val dismiss = {
        submenu = null
        expanded = false
    }
    val select: (GroupAction) -> Unit = { action ->
        dismiss()
        onAction(group.id, action)
    }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier,
                )
                .focusProperties { canFocus = !moveModeActive },
        ) {
            Icon(
                painterResource(R.drawable.ic_baseline_more_vert_24),
                contentDescription = stringResource(R.string.toolbar_more_actions),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
            val currentSubmenu = submenu
            if (currentSubmenu == null) {
                if (group.canShareSubscriptionUrl) {
                    GroupSubmenuItem(GroupActionSubmenu.ShareUrl.title) {
                        submenu = GroupActionSubmenu.ShareUrl
                    }
                }
                if (group.canShareSubscription) {
                    GroupSubmenuItem(GroupActionSubmenu.ShareUniversal.title) {
                        submenu = GroupActionSubmenu.ShareUniversal
                    }
                }
                GroupSubmenuItem(GroupActionSubmenu.Export.title) {
                    submenu = GroupActionSubmenu.Export
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clear_profiles)) },
                    onClick = { select(GroupAction.Clear) },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(currentSubmenu.title)) },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.baseline_arrow_back_24),
                            contentDescription = null,
                        )
                    },
                    onClick = { submenu = null },
                )
                when (currentSubmenu) {
                    GroupActionSubmenu.ShareUrl -> {
                        GroupActionItem(R.string.action_export_clipboard) {
                            select(GroupAction.ShareUrlClipboard)
                        }
                        GroupActionItem(R.string.share_qr_nfc) {
                            select(GroupAction.ShareUrlQr)
                        }
                    }
                    GroupActionSubmenu.ShareUniversal -> {
                        GroupActionItem(R.string.action_export_clipboard) {
                            select(GroupAction.ShareUniversalClipboard)
                        }
                        GroupActionItem(R.string.share_qr_nfc) {
                            select(GroupAction.ShareUniversalQr)
                        }
                    }
                    GroupActionSubmenu.Export -> {
                        GroupActionItem(R.string.action_export_clipboard) {
                            select(GroupAction.ExportClipboard)
                        }
                        GroupActionItem(R.string.action_export_file) {
                            select(GroupAction.ExportFile)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupSubmenuItem(@StringRes title: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(title)) },
        trailingIcon = {
            Icon(
                painterResource(R.drawable.baseline_arrow_back_24),
                contentDescription = null,
                modifier = Modifier.rotate(180f),
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun GroupActionItem(@StringRes title: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(title)) },
        onClick = onClick,
    )
}
