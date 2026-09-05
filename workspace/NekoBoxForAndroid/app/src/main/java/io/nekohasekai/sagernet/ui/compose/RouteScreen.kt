package io.nekohasekai.sagernet.ui.compose

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.CustomDnsServerEntity
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleType

enum class RouteScreenAction {
    RESET,
    MANAGE_ASSETS,
    IMPORT_CLIPBOARD,
}

enum class RouteScreenExportFormat {
    NEKOBOX_PLUS,
    HAPP,
    INCY,
}

enum class RouteScreenExportDestination {
    CLIPBOARD,
    SHARE,
    QR_CODE,
}

private enum class OverflowPage {
    MAIN,
    EXPORT_FORMAT,
    EXPORT_DESTINATION,
}

private data class PendingRouteExport(
    val format: RouteScreenExportFormat,
    val destination: RouteScreenExportDestination,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(
    rules: List<RuleEntity>,
    onOpenDrawer: () -> Unit,
    onAddNormalRoute: () -> Unit,
    onAddDnsRoute: () -> Unit,
    onAction: (RouteScreenAction) -> Unit,
    onExport: (RouteScreenExportFormat, RouteScreenExportDestination, String) -> Unit,
    shouldConfirmDelete: () -> Boolean,
    exportWarningMessage: String?,
    onDismissExportWarning: () -> Unit,
    onOpenDocumentation: () -> Unit,
    onEnabledChange: (Long, Boolean) -> Unit,
    onEdit: (Long) -> Unit,
    onDuplicate: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onMove: (Int, Int) -> Unit,
    onMoveFinished: () -> Unit,
) {
    var addExpanded by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var overflowPage by remember { mutableStateOf(OverflowPage.MAIN) }
    var exportFormat by remember { mutableStateOf(RouteScreenExportFormat.NEKOBOX_PLUS) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    var pendingExport by remember { mutableStateOf<PendingRouteExport?>(null) }
    var movingRuleId by remember { mutableStateOf<Long?>(null) }
    var tvDeletingRuleId by remember { mutableStateOf<Long?>(null) }

    fun finishTvMove() {
        if (movingRuleId == null) return
        movingRuleId = null
        onMoveFinished()
    }

    BackHandler(enabled = movingRuleId != null, onBack = ::finishTvMove)
    LaunchedEffect(rules.map(RuleEntity::id), tvDeletingRuleId) {
        if (tvDeletingRuleId != null && rules.none { it.id == tvDeletingRuleId }) {
            tvDeletingRuleId = null
        }
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            val fromIndex = rules.indexOfFirst { it.id == from }
            val toIndex = rules.indexOfFirst { it.id == to }
            if (fromIndex >= 0 && toIndex >= 0) onMove(fromIndex, toIndex)
        },
        onMoveFinished = onMoveFinished,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_route)) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            painterResource(R.drawable.ic_navigation_menu),
                            contentDescription = stringResource(R.string.menu_route),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { addExpanded = true }) {
                            Icon(
                                painterResource(R.drawable.ic_baseline_add_road_24),
                                contentDescription = stringResource(R.string.route_add),
                            )
                        }
                        DropdownMenu(
                            expanded = addExpanded,
                            onDismissRequest = { addExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.route_normal)) },
                                onClick = {
                                    addExpanded = false
                                    onAddNormalRoute()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dns_rule)) },
                                onClick = {
                                    addExpanded = false
                                    onAddDnsRoute()
                                },
                            )
                        }
                    }
                    Box {
                        IconButton(
                            onClick = {
                                overflowPage = OverflowPage.MAIN
                                overflowExpanded = true
                            },
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_baseline_more_vert_24),
                                contentDescription = stringResource(R.string.toolbar_more_actions),
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            when (overflowPage) {
                                OverflowPage.MAIN -> {
                                    RouteMenuItem(R.string.route_reset) {
                                        overflowExpanded = false
                                        showResetConfirmation = true
                                    }
                                    RouteMenuItem(R.string.route_manage_assets) {
                                        overflowExpanded = false
                                        onAction(RouteScreenAction.MANAGE_ASSETS)
                                    }
                                    RouteMenuItem(R.string.action_from_clipboard) {
                                        overflowExpanded = false
                                        onAction(RouteScreenAction.IMPORT_CLIPBOARD)
                                    }
                                    RouteMenuItem(R.string.action_export) {
                                        overflowPage = OverflowPage.EXPORT_FORMAT
                                    }
                                }

                                OverflowPage.EXPORT_FORMAT -> {
                                    RouteMenuItem(R.string.routing_format_nekobox_plus) {
                                        exportFormat = RouteScreenExportFormat.NEKOBOX_PLUS
                                        overflowPage = OverflowPage.EXPORT_DESTINATION
                                    }
                                    RouteMenuItem(R.string.routing_format_happ) {
                                        exportFormat = RouteScreenExportFormat.HAPP
                                        overflowPage = OverflowPage.EXPORT_DESTINATION
                                    }
                                    RouteMenuItem(R.string.routing_format_incy) {
                                        exportFormat = RouteScreenExportFormat.INCY
                                        overflowPage = OverflowPage.EXPORT_DESTINATION
                                    }
                                }

                                OverflowPage.EXPORT_DESTINATION -> {
                                    RouteMenuItem(R.string.routing_export_to_clipboard) {
                                        overflowExpanded = false
                                        pendingExport = PendingRouteExport(
                                            exportFormat, RouteScreenExportDestination.CLIPBOARD,
                                        )
                                    }
                                    RouteMenuItem(R.string.share) {
                                        overflowExpanded = false
                                        pendingExport = PendingRouteExport(
                                            exportFormat, RouteScreenExportDestination.SHARE,
                                        )
                                    }
                                    RouteMenuItem(R.string.routing_export_qr_code) {
                                        overflowExpanded = false
                                        pendingExport = PendingRouteExport(
                                            exportFormat, RouteScreenExportDestination.QR_CODE,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = reorderState.listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(4.dp),
        ) {
            item(key = "route-documentation") {
                CompactActionCard(R.string.route_warn, onOpenDocumentation)
            }
            items(rules, key = { rule -> rule.id }) { rule ->
                val isDragging = reorderState.isDragging(rule.id)
                RouteDismissRow(
                    rule = rule,
                    modifier = (if (isDragging || tvDeletingRuleId != null) {
                        Modifier
                    } else {
                        Modifier.animateItem()
                    })
                        .zIndex(if (isDragging) 1f else 0f),
                    onEnabledChange = onEnabledChange,
                    onEdit = onEdit,
                    onDuplicate = onDuplicate,
                    onDelete = { ruleId ->
                        if (shouldConfirmDelete()) pendingDeleteId = ruleId else onDelete(ruleId)
                    },
                    reorderState = reorderState,
                    moving = movingRuleId == rule.id,
                    moveModeActive = movingRuleId != null,
                    onTvDelete = {
                        tvDeletingRuleId = rule.id
                        if (shouldConfirmDelete()) pendingDeleteId = rule.id else onDelete(rule.id)
                    },
                    onTvMoveStarted = { movingRuleId = rule.id },
                    onTvMoveBy = { delta ->
                        val from = rules.indexOfFirst { it.id == rule.id }
                        if (from >= 0) onMove(from, from + delta)
                    },
                    onTvMoveFinished = ::finishTvMove,
                )
            }
        }
    }

    if (showResetConfirmation) {
        RouteConfirmationDialog(
            title = stringResource(R.string.confirm),
            message = stringResource(R.string.clear_profiles_message),
            onDismiss = { showResetConfirmation = false },
            onConfirm = {
                showResetConfirmation = false
                onAction(RouteScreenAction.RESET)
            },
        )
    }

    pendingDeleteId?.let { ruleId ->
        RouteConfirmationDialog(
            title = stringResource(R.string.delete_route_prompt),
            onDismiss = {
                pendingDeleteId = null
                tvDeletingRuleId = null
            },
            onConfirm = {
                pendingDeleteId = null
                onDelete(ruleId)
            },
        )
    }

    pendingExport?.let { request ->
        RouteExportNameDialog(
            onDismiss = { pendingExport = null },
            onConfirm = { name ->
                pendingExport = null
                onExport(request.format, request.destination, name)
            },
        )
    }

    exportWarningMessage?.let { message ->
        RouteMessageDialog(
            title = stringResource(R.string.routing_export_complete_with_warnings),
            message = message,
            onDismiss = onDismissExportWarning,
        )
    }
}

@Composable
private fun RouteExportNameDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val defaultName = stringResource(R.string.routing_export_default_name)
    var name by remember {
        mutableStateOf(TextFieldValue(defaultName, selection = TextRange(0, defaultName.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    RouteDialogSurface(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) {
            Text(
                stringResource(R.string.routing_export_name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.routing_export_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = { onConfirm(name.text) }) {
                    Text(stringResource(R.string.action_export))
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
}

@Composable
private fun RouteConfirmationDialog(
    title: String,
    message: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RouteDialogSurface(onDismiss = onDismiss) {
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
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.no)) }
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.yes)) }
            }
        }
    }
}

@Composable
private fun RouteMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    RouteDialogSurface(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RouteDialogSurface(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteDismissRow(
    rule: RuleEntity,
    modifier: Modifier,
    onEnabledChange: (Long, Boolean) -> Unit,
    onEdit: (Long) -> Unit,
    onDuplicate: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    reorderState: ReorderableLazyListState,
    moving: Boolean,
    moveModeActive: Boolean,
    onTvDelete: () -> Unit,
    onTvMoveStarted: () -> Unit,
    onTvMoveBy: (Int) -> Unit,
    onTvMoveFinished: () -> Unit,
) {
    var deleteRequested by remember(rule.id) { mutableStateOf(false) }
    var tvActionsExpanded by remember(rule.id) { mutableStateOf(false) }
    val cardFocusRequester = remember(rule.id) { FocusRequester() }
    val firstActionFocusRequester = remember(rule.id) { FocusRequester() }
    val menuFocusRequester = remember(rule.id) { FocusRequester() }
    val isDragging = reorderState.isDragging(rule.id)
    LaunchedEffect(moving) {
        if (moving) cardFocusRequester.requestFocus()
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && !deleteRequested) {
                deleteRequested = true
                onDelete(rule.id)
            }
            false
        },
    )
    SwipeToDismissBox(
        modifier = modifier
            .padding(4.dp)
            .pointerInput(rule.id) {
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
        enableDismissFromEndToStart = true,
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
        RouteItemCard(
            modifier = Modifier
                .focusRequester(cardFocusRequester)
                .tvCardActions(
                    enabled = !moveModeActive || moving,
                    showFocusIndicator = !moving,
                    onClick = {
                        if (moving) onTvMoveFinished() else firstActionFocusRequester.requestFocus()
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
                .reorderableItem(reorderState, rule.id, !moveModeActive),
            name = rule.displayName(),
            summary = rule.mkSummary(),
            outbound = rule.displayOutbound(),
            enabled = rule.enabled,
            isDnsRule = RuleType.fromValue(rule.type) == RuleType.DNS,
            outboundColor = when (rule.outbound) {
                -2L -> R.color.color_route_block
                -1L -> R.color.color_route_direct
                0L -> R.color.color_route_proxy
                else -> R.color.color_route_config
            },
            secondaryActionIcon = R.drawable.ic_baseline_content_copy_24,
            secondaryActionDescription = R.string.duplicate,
            onEnabledChange = { onEnabledChange(rule.id, it) },
            onEdit = { onEdit(rule.id) },
            onSecondaryAction = { onDuplicate(rule.id) },
            actionsFocusable = !moveModeActive,
            firstActionFocusRequester = firstActionFocusRequester,
        )
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
                onClick = {
                    tvActionsExpanded = false
                    onTvMoveStarted()
                },
            )
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomDnsServersScreen(
    servers: List<CustomDnsServerEntity>,
    @StringRes deletePrompt: Int?,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onEnabledChange: (CustomDnsServerEntity, Boolean) -> Unit,
    onEdit: (CustomDnsServerEntity) -> Unit,
    onDelete: (CustomDnsServerEntity) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dns_servers)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painterResource(R.drawable.baseline_arrow_back_24),
                            contentDescription = stringResource(android.R.string.cancel),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(
                            painterResource(R.drawable.ic_baseline_add_24),
                            contentDescription = stringResource(R.string.add_dns_server),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 4.dp),
        ) {
            itemsIndexed(servers, key = { _, server -> server.id }) { _, server ->
                CustomDnsServerDismissRow(
                    server = server,
                    onEnabledChange = { onEnabledChange(server, it) },
                    onEdit = { onEdit(server) },
                    onDelete = { onDelete(server) },
                )
            }
        }
    }
    deletePrompt?.let {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(it)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) { Text(stringResource(R.string.no)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDnsServerDismissRow(
    server: CustomDnsServerEntity,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var deleteRequested by remember(server.id) { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && !deleteRequested) {
                deleteRequested = true
                onDelete()
            }
            false
        },
    )
    SwipeToDismissBox(
        modifier = Modifier.pointerInput(server.id) {
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
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    painterResource(R.drawable.ic_action_delete),
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        RouteItemCard(
            name = server.tag,
            summary = server.displaySummary(),
            outbound = server.type.uppercase(),
            enabled = server.enabled,
            isDnsRule = false,
            outboundColor = null,
            secondaryActionIcon = R.drawable.ic_baseline_delete_24,
            secondaryActionDescription = R.string.delete,
            onEnabledChange = onEnabledChange,
            onEdit = onEdit,
            onSecondaryAction = onDelete,
        )
    }
}

@Composable
private fun RouteMenuItem(
    text: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        onClick = onClick,
    )
}
