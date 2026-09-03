package io.nekohasekai.sagernet.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.routing.RoutingSettingKind

data class RoutingImportPreviewSetting(
    val kind: RoutingSettingKind,
    val title: String,
    val summary: String,
    val mutedSummary: Boolean,
)

data class RoutingImportPreviewRule(
    val index: Int,
    val title: String,
    val summary: String,
    val mutedSummary: Boolean,
)

data class RoutingImportPreviewData(
    val name: String,
    val source: String,
    val settings: List<RoutingImportPreviewSetting>,
    val warnings: List<String>,
    val rules: List<RoutingImportPreviewRule>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingImportPreviewScreen(
    data: RoutingImportPreviewData?,
    selectedSettings: Set<RoutingSettingKind>,
    selectedRules: Set<Int>,
    importing: Boolean,
    downloadingAssets: Boolean,
    showOverwriteConfirmation: Boolean,
    fatalErrorMessage: String?,
    importErrorMessage: String?,
    showReconnectConfirmation: Boolean,
    onClose: () -> Unit,
    onSettingChecked: (RoutingSettingKind, Boolean) -> Unit,
    onRuleChecked: (Int, Boolean) -> Unit,
    onImport: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onConfirmImport: () -> Unit,
    onDismissFatalError: () -> Unit,
    onDismissImportError: () -> Unit,
    onReconnect: (Boolean) -> Unit,
) {
    BackHandler { if (!importing) onClose() }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routing_import_preview)) },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !importing) {
                        Icon(
                            painterResource(R.drawable.baseline_arrow_back_24),
                            contentDescription = stringResource(R.string.mal_close),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Column {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onClose, enabled = !importing) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        Button(
                            onClick = onImport,
                            enabled = data != null && !importing,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            if (importing && !downloadingAssets) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(stringResource(R.string.action_import_routing))
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (data == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                item {
                    Text(
                        text = data.name,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        text = data.source,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
                if (data.settings.isNotEmpty()) {
                    item { SectionTitle(R.string.routing_import_settings) }
                    data.settings.forEach { setting ->
                        item(key = "setting-${setting.kind}") {
                            PreviewCheckRow(
                                title = setting.title,
                                summary = setting.summary,
                                mutedSummary = setting.mutedSummary,
                                checked = setting.kind in selectedSettings,
                                enabled = !importing,
                                onCheckedChange = { onSettingChecked(setting.kind, it) },
                            )
                        }
                    }
                }
                data.warnings.forEach { warning ->
                    item {
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                if (data.rules.isNotEmpty()) {
                    item { SectionTitle(R.string.routing_import_rules) }
                    data.rules.forEach { rule ->
                        item(key = "rule-${rule.index}") {
                            PreviewCheckRow(
                                title = rule.title,
                                summary = rule.summary,
                                mutedSummary = rule.mutedSummary,
                                checked = rule.index in selectedRules,
                                enabled = !importing,
                                onCheckedChange = { onRuleChecked(rule.index, it) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showOverwriteConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissConfirmation,
            title = { Text(stringResource(R.string.confirm)) },
            text = { Text(stringResource(R.string.routing_import_overwrite_warning)) },
            dismissButton = {
                TextButton(onClick = onDismissConfirmation) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmImport) {
                    Text(stringResource(R.string.action_import_routing))
                }
            },
        )
    }
    if (downloadingAssets) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.routing_import_downloading_resources),
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            },
        )
    }
    fatalErrorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissFatalError,
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismissFatalError) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
    importErrorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissImportError,
            title = { Text(stringResource(R.string.routing_import_applied_download_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismissImportError) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
    if (showReconnectConfirmation) {
        AlertDialog(
            onDismissRequest = { onReconnect(false) },
            title = { Text(stringResource(R.string.routing_import_complete)) },
            text = { Text(stringResource(R.string.routing_import_reconnect)) },
            dismissButton = {
                TextButton(onClick = { onReconnect(false) }) {
                    Text(stringResource(R.string.no))
                }
            },
            confirmButton = {
                TextButton(onClick = { onReconnect(true) }) {
                    Text(stringResource(R.string.yes))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PreviewCheckRow(
    title: String,
    summary: String,
    mutedSummary: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusTarget(enabled)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
        )
        Column(Modifier.padding(start = 8.dp, top = 10.dp, end = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = if (mutedSummary) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
