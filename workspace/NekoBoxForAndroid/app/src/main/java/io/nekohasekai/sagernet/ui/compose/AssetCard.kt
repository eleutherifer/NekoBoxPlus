package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R

data class AssetUiItem(
    val path: String,
    val name: String,
    val status: String,
    val managed: Boolean,
    val updating: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(
    assets: List<AssetUiItem>,
    updatesInProgress: Boolean,
    onClose: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onUpdate: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.route_assets)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_navigation_close),
                            contentDescription = stringResource(android.R.string.cancel),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onImport) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_note_add),
                            contentDescription = stringResource(R.string.action_import_file),
                        )
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { if (!updatesInProgress) onRefresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                itemsIndexed(assets, key = { _, asset -> asset.path }) { _, asset ->
                    AssetDismissRow(
                        asset = asset,
                        onUpdate = { onUpdate(asset.path) },
                        onRemove = { onRemove(asset.path) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssetDismissRow(
    asset: AssetUiItem,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    var removalRequested by remember(asset.path) { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (
                it == SwipeToDismissBoxValue.EndToStart &&
                !asset.managed &&
                !removalRequested
            ) {
                removalRequested = true
                onRemove()
            }
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !asset.managed,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 8.dp, top = 8.dp, end = 8.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_delete),
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        AssetCard(
            name = asset.name,
            status = asset.status,
            managed = asset.managed,
            updating = asset.updating,
            onUpdate = onUpdate,
        )
    }
}

@Composable
fun AssetCard(
    name: String,
    status: String,
    managed: Boolean,
    updating: Boolean,
    onUpdate: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 8.dp, end = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (updating) 1f else 0f),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 15.dp, top = 15.dp, end = 8.dp, bottom = 12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp),
            )
            TextButton(
                onClick = onUpdate,
                enabled = managed && !updating,
                modifier = Modifier.alpha(if (managed && !updating) 1f else 0f),
            ) {
                Text(stringResource(R.string.group_update))
            }
        }
    }
}
