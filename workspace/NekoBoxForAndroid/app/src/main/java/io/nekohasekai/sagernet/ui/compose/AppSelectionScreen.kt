package io.nekohasekai.sagernet.ui.compose

import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.SelectableApp
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class AppRoutingMode { Off, Proxy, Bypass }

internal enum class AppSelectionAction { Invert, Clear, Export, Import }

internal data class AppSelectionNotice(
    val id: Long,
    @param:StringRes val messageRes: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSelectionScreen(
    title: String,
    apps: List<SelectableApp>,
    allAppsEmpty: Boolean,
    loading: Boolean,
    query: String,
    showSystemApps: Boolean,
    routingMode: AppRoutingMode?,
    actionsExpanded: Boolean,
    clipboardActionsInToolbar: Boolean,
    notice: AppSelectionNotice?,
    isSelected: (SelectableApp) -> Boolean,
    onClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onRoutingModeChange: (AppRoutingMode) -> Unit,
    onAutoSelect: () -> Unit,
    onToggle: (SelectableApp) -> Unit,
    onAction: (AppSelectionAction) -> Unit,
    onActionsExpandedChange: (Boolean) -> Unit,
    onNoticeShown: () -> Unit,
    onOpenSettings: () -> Unit,
    showRegionPicker: Boolean = false,
    confirmRoutingSelection: Boolean = false,
    onRegionSelected: (String) -> Unit = {},
    onDismissRegionPicker: () -> Unit = {},
    onConfirmRoutingSelection: () -> Unit = {},
    onDismissRoutingSelection: () -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val snackbarHostState = remember { SnackbarHostState() }
    val noticeMessage = notice?.let { stringResource(it.messageRes) }
    LaunchedEffect(notice?.id) {
        if (notice != null && noticeMessage != null) {
            snackbarHostState.showSnackbar(noticeMessage)
            onNoticeShown()
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painterResource(R.drawable.ic_navigation_close),
                            contentDescription = stringResource(R.string.mal_close),
                        )
                    }
                },
                actions = {
                    if (clipboardActionsInToolbar) {
                        IconButton(onClick = { onAction(AppSelectionAction.Export) }) {
                            Icon(
                                painterResource(R.drawable.ic_baseline_content_copy_24),
                                contentDescription = stringResource(R.string.action_export_clipboard),
                            )
                        }
                        IconButton(onClick = { onAction(AppSelectionAction.Import) }) {
                            Icon(
                                painterResource(R.drawable.ic_baseline_content_paste_24),
                                contentDescription = stringResource(R.string.action_import),
                            )
                        }
                    }
                    AppSelectionActionsMenu(
                        expanded = actionsExpanded,
                        includeClipboard = !clipboardActionsInToolbar,
                        onExpandedChange = onActionsExpandedChange,
                        onAction = onAction,
                    )
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .navigationBarsPadding(),
        ) {
            AppSelectionControls(
                query = query,
                showSystemApps = showSystemApps,
                routingMode = routingMode,
                onQueryChange = onQueryChange,
                onShowSystemAppsChange = onShowSystemAppsChange,
                onRoutingModeChange = onRoutingModeChange,
                onAutoSelect = onAutoSelect,
            )
            Box(Modifier.fillMaxSize()) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    allAppsEmpty -> AppListUnavailable(onOpenSettings)
                    else -> AppList(
                        apps = apps,
                        isSelected = isSelected,
                        onToggle = onToggle,
                    )
                }
            }
        }
    }
    if (showRegionPicker) {
        AlertDialog(
            onDismissRequest = onDismissRegionPicker,
            title = { Text(stringResource(R.string.routing_select_region)) },
            text = {
                Column {
                    listOf(
                        "ru" to R.string.routing_region_russia,
                        "cn" to R.string.routing_region_china,
                        "ir" to R.string.routing_region_iran,
                        "other" to R.string.routing_region_other,
                    ).forEach { (region, label) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = { onRegionSelected(region) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismissRegionPicker) { Text(stringResource(R.string.no)) }
            },
        )
    }
    if (confirmRoutingSelection) {
        AlertDialog(
            onDismissRequest = onDismissRoutingSelection,
            title = { Text(stringResource(R.string.confirm)) },
            text = { Text(stringResource(R.string.auto_select_proxy_apps_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmRoutingSelection) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissRoutingSelection) { Text(stringResource(R.string.no)) }
            },
        )
    }
}

@Composable
private fun AppSelectionActionsMenu(
    expanded: Boolean,
    includeClipboard: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAction: (AppSelectionAction) -> Unit,
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(
                painterResource(R.drawable.ic_baseline_more_vert_24),
                contentDescription = stringResource(R.string.toolbar_more_actions),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            val actions = buildList {
                add(AppSelectionAction.Invert to R.string.invert_selections)
                add(AppSelectionAction.Clear to R.string.clear_selections)
                if (includeClipboard) {
                    add(AppSelectionAction.Export to R.string.action_export_clipboard)
                    add(AppSelectionAction.Import to R.string.action_import)
                }
            }
            actions.forEach { (action, title) ->
                DropdownMenuItem(
                    text = { Text(stringResource(title)) },
                    onClick = {
                        onExpandedChange(false)
                        onAction(action)
                    },
                )
            }
        }
    }
}

@Composable
private fun AppSelectionControls(
    query: String,
    showSystemApps: Boolean,
    routingMode: AppRoutingMode?,
    onQueryChange: (String) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onRoutingModeChange: (AppRoutingMode) -> Unit,
    onAutoSelect: () -> Unit,
) {
    Card(
        modifier = Modifier.padding(4.dp).fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (routingMode != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppRoutingMode.entries.forEach { mode ->
                        val title = when (mode) {
                            AppRoutingMode.Off -> R.string.off
                            AppRoutingMode.Proxy -> R.string.route_proxy
                            AppRoutingMode.Bypass -> R.string.bypass_apps
                        }
                        FilterChip(
                            selected = routingMode == mode,
                            onClick = { onRoutingModeChange(mode) },
                            label = { Text(stringResource(title)) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = showSystemApps,
                    onClick = { onShowSystemAppsChange(!showSystemApps) },
                    label = { Text(stringResource(R.string.show_system_apps)) },
                )
                if (routingMode != null) {
                    AssistChip(
                        onClick = onAutoSelect,
                        label = { Text(stringResource(R.string.auto_select_proxy_apps)) },
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.search_apps)) },
            )
        }
    }
}

@Composable
private fun AppListUnavailable(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.app_list_permission_denied),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.open_app_settings))
        }
    }
}

@Composable
private fun AppList(
    apps: List<SelectableApp>,
    isSelected: (SelectableApp) -> Boolean,
    onToggle: (SelectableApp) -> Unit,
) {
    val listState = rememberLazyListState()
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
        ) {
            items(apps, key = { it.packageName }) { app ->
                AppRow(app, isSelected(app), onToggle)
            }
        }
        if (apps.size > 20) {
            AppFastScroller(
                apps = apps,
                viewportHeight = maxHeight,
                firstVisibleIndex = firstVisibleIndex,
                onScrollTo = { listState.scrollToItem(it) },
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(32.dp),
            )
        }
    }
}

@Composable
private fun AppRow(
    app: SelectableApp,
    selected: Boolean,
    onToggle: (SelectableApp) -> Unit,
) {
    val context = LocalContext.current
    val icon = remember(app.packageName) {
        runCatching {
            PackageCache.installedApps[app.packageName]?.loadIcon(context.packageManager)
        }.getOrNull() ?: context.packageManager.defaultActivityIcon
    }
    Card(
        onClick = { onToggle(app) },
        modifier = Modifier.fillMaxWidth().padding(4.dp).tvFocusTarget(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AndroidView(
                factory = { viewContext ->
                    ImageView(viewContext).apply {
                        importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                },
                update = { it.setImageDrawable(icon) },
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    text = app.name,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${app.packageName} (${app.uid})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(checked = selected, onCheckedChange = null)
        }
    }
}

@Composable
private fun AppFastScroller(
    apps: List<SelectableApp>,
    viewportHeight: androidx.compose.ui.unit.Dp,
    firstVisibleIndex: Int,
    onScrollTo: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumbHeight = 48.dp
    val travel = (viewportHeight - thumbHeight).coerceAtLeast(1.dp)
    val travelPx = with(density) { travel.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val listFraction = if (apps.size <= 1) 0f else {
        firstVisibleIndex.toFloat() / (apps.lastIndex).toFloat()
    }
    val thumbFraction = if (dragging) dragFraction else listFraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier.pointerInput(apps.size, travelPx) {
            detectVerticalDragGestures(
                onDragStart = {
                    dragging = true
                    dragFraction = listFraction.coerceIn(0f, 1f)
                },
                onDragCancel = { dragging = false },
                onDragEnd = { dragging = false },
            ) { change, amount ->
                change.consume()
                dragFraction = (dragFraction + amount / travelPx).coerceIn(0f, 1f)
                scope.launch { onScrollTo((dragFraction * apps.lastIndex).roundToInt()) }
            }
        },
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = travel * thumbFraction)
                .padding(end = 4.dp)
                .width(4.dp)
                .height(thumbHeight)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        if (dragging) {
            val index = (thumbFraction * apps.lastIndex).roundToInt().coerceIn(apps.indices)
            Text(
                text = apps[index].name.firstOrNull()?.uppercase() ?: "",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 28.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
