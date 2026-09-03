package io.nekohasekai.sagernet.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.R

internal data class AdblockCustomFilterListItem(
    val index: Int,
    val url: String,
    val title: String,
    val summary: String,
    val enabled: Boolean,
    val updating: Boolean,
)

internal data class AdblockBundledFilterListItem(
    val id: String,
    val title: String,
    val summary: String,
    val enabled: Boolean,
    val updating: Boolean,
)

internal sealed interface AdblockBundledListItem {
    data class Category(val title: String) : AdblockBundledListItem
    data class Filter(val value: AdblockBundledFilterListItem) : AdblockBundledListItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdblockBundledFiltersScreen(
    items: List<AdblockBundledListItem>,
    reloadPrompt: Int,
    onClose: () -> Unit,
    onUpdateAll: () -> Unit,
    onToggle: (AdblockBundledFilterListItem) -> Unit,
    onUpdate: (AdblockBundledFilterListItem) -> Unit,
    onApplyReload: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val reloadMessage = stringResource(R.string.need_reload)
    val applyLabel = stringResource(R.string.apply)

    BackHandler(onBack = onClose)
    LaunchedEffect(reloadPrompt) {
        if (reloadPrompt > 0) {
            val result = snackbarHostState.showSnackbar(
                message = reloadMessage,
                actionLabel = applyLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onApplyReload()
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.adblock_bundled_filters)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_navigation_close),
                            contentDescription = stringResource(android.R.string.cancel),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onUpdateAll) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_update_24),
                            contentDescription = stringResource(R.string.adblock_update_all),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .navigationBarsPadding(),
        ) {
            items(items, key = {
                when (it) {
                    is AdblockBundledListItem.Category -> "category.${it.title}"
                    is AdblockBundledListItem.Filter -> "filter.${it.value.id}"
                }
            }) { item ->
                when (item) {
                    is AdblockBundledListItem.Category -> BundledFilterCategory(item.title)
                    is AdblockBundledListItem.Filter -> {
                        val filter = item.value
                        FilterRow(
                            title = filter.title,
                            summary = filter.summary,
                            enabled = filter.enabled,
                            updating = filter.updating,
                            onToggle = { onToggle(filter) },
                            onUpdate = { onUpdate(filter) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdblockCustomFiltersScreen(
    filters: List<AdblockCustomFilterListItem>,
    reloadPrompt: Int,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onUpdateAll: () -> Unit,
    onToggle: (AdblockCustomFilterListItem) -> Unit,
    onUpdate: (AdblockCustomFilterListItem) -> Unit,
    onLongClick: (AdblockCustomFilterListItem) -> Unit,
    onApplyReload: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val reloadMessage = stringResource(R.string.need_reload)
    val applyLabel = stringResource(R.string.apply)

    BackHandler(onBack = onClose)
    LaunchedEffect(reloadPrompt) {
        if (reloadPrompt > 0) {
            val result = snackbarHostState.showSnackbar(
                message = reloadMessage,
                actionLabel = applyLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onApplyReload()
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.adblock_custom_filters)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_navigation_close),
                            contentDescription = stringResource(android.R.string.cancel),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_add_24),
                            contentDescription = stringResource(R.string.adblock_add_filter),
                        )
                    }
                    IconButton(onClick = onUpdateAll) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_update_24),
                            contentDescription = stringResource(R.string.adblock_update_all),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .navigationBarsPadding(),
        ) {
            if (filters.isEmpty()) {
                item {
                    EmptyCustomFiltersRow()
                }
            } else {
                items(filters, key = { "${it.index}.${it.url}" }) { filter ->
                    CustomFilterRow(
                        filter = filter,
                        onToggle = { onToggle(filter) },
                        onUpdate = { onUpdate(filter) },
                        onLongClick = { onLongClick(filter) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CustomFilterRow(
    filter: AdblockCustomFilterListItem,
    onToggle: () -> Unit,
    onUpdate: () -> Unit,
    onLongClick: () -> Unit,
) {
    FilterRow(
        title = filter.title,
        summary = filter.summary,
        enabled = filter.enabled,
        updating = filter.updating,
        onToggle = onToggle,
        onUpdate = onUpdate,
        onLongClick = onLongClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterRow(
    title: String,
    summary: String,
    enabled: Boolean,
    updating: Boolean,
    onToggle: () -> Unit,
    onUpdate: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onToggle,
                onLongClick = onLongClick,
            )
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1F)
                .padding(vertical = 16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 21.5.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.padding(start = 16.dp)) {
            AdblockFilterWidget(
                checked = enabled,
                updateEnabled = enabled,
                running = updating,
                onUpdate = onUpdate,
            )
        }
    }
}

@Composable
private fun BundledFilterCategory(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge.copy(lineHeight = 19.sp),
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EmptyCustomFiltersRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_filter_list_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.adblock_custom_filters_empty),
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
