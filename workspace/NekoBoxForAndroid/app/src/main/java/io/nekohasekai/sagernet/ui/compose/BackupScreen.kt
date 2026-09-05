package io.nekohasekai.sagernet.ui.compose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R

@Composable
fun BackupScreen(
    gitConfigured: Boolean,
    gitOperationInProgress: Boolean,
    onShare: (BackupSelection) -> Unit,
    onExport: (BackupSelection) -> Unit,
    onImport: () -> Unit,
    onWebDavSettings: () -> Unit,
    onWebDavBackup: () -> Unit,
    onWebDavRestore: () -> Unit,
    onGitConfigure: () -> Unit,
    onGitBackup: () -> Unit,
    onGitRestore: () -> Unit,
    onGitCompact: () -> Unit,
    gitRestoreOptions: List<GitRestoreOption>?,
    onDismissGitRestore: () -> Unit,
    onSelectGitRestore: (String) -> Unit,
    showGitCompactDialog: Boolean,
    onDismissGitCompact: () -> Unit,
    onConfirmGitCompact: (String) -> Unit,
) {
    var configurations by rememberSaveable { mutableStateOf(true) }
    var rules by rememberSaveable { mutableStateOf(true) }
    var settings by rememberSaveable { mutableStateOf(true) }
    val selection = { BackupSelection(configurations, rules, settings) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackupCard {
            Text(
                text = stringResource(R.string.backup_local),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
            BackupCheckbox(
                checked = configurations,
                label = R.string.backup_groups_and_configurations,
                onCheckedChange = { configurations = it },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            BackupCheckbox(
                checked = rules,
                label = R.string.backup_rules,
                onCheckedChange = { rules = it },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            BackupCheckbox(
                checked = settings,
                label = R.string.backup_settings,
                onCheckedChange = { settings = it },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                text = stringResource(R.string.backup_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
            )
            BackupActions {
                BackupButton(R.string.share) { onShare(selection()) }
                BackupButton(R.string.action_export_file) { onExport(selection()) }
                BackupButton(R.string.action_import_file, onClick = onImport)
            }
        }

        BackupCard {
            Text(
                text = stringResource(R.string.backup_webdav),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            BackupActions {
                BackupButton(R.string.webdav_settings, onClick = onWebDavSettings)
                BackupButton(R.string.backup_to_webdav, onClick = onWebDavBackup)
                BackupButton(R.string.restore_from_webdav, onClick = onWebDavRestore)
            }
        }

        BackupCard(modifier = Modifier.padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.git_backup_https),
                    style = MaterialTheme.typography.titleLarge,
                )
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BackupButton(
                        label = R.string.git_configure,
                        icon = R.drawable.ic_action_settings,
                        enabled = !gitOperationInProgress,
                        onClick = onGitConfigure,
                    )
                    if (gitConfigured) {
                        BackupButton(
                            label = R.string.git_backup_now,
                            icon = R.drawable.ic_baseline_save_24,
                            enabled = !gitOperationInProgress,
                            onClick = onGitBackup,
                        )
                        BackupButton(
                            label = R.string.git_restore,
                            icon = R.drawable.ic_baseline_folder_open_24,
                            enabled = !gitOperationInProgress,
                            onClick = onGitRestore,
                        )
                        BackupButton(
                            label = R.string.git_compact,
                            icon = R.drawable.ic_action_lock,
                            enabled = !gitOperationInProgress,
                            onClick = onGitCompact,
                        )
                    }
                }
            }
        }
    }

    gitRestoreOptions?.let { options ->
        var selectedCommitId by rememberSaveable(options) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = onDismissGitRestore,
            title = { Text(stringResource(R.string.git_select_version)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(options, key = GitRestoreOption::commitId) { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedCommitId = option.commitId }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedCommitId == option.commitId,
                                onClick = { selectedCommitId = option.commitId },
                            )
                            Text(option.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissGitRestore) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { selectedCommitId?.let(onSelectGitRestore) },
                    enabled = selectedCommitId != null,
                ) {
                    Text(stringResource(R.string.git_restore))
                }
            },
        )
    }

    if (showGitCompactDialog) {
        var versions by rememberSaveable { mutableStateOf("10") }
        AlertDialog(
            onDismissRequest = onDismissGitCompact,
            title = { Text(stringResource(R.string.git_compact)) },
            text = {
                Column {
                    Text(stringResource(R.string.git_compact_hint))
                    TextField(
                        value = versions,
                        onValueChange = { if (it.length <= 5) versions = it },
                        label = { Text(stringResource(R.string.git_compact_versions)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissGitCompact) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirmGitCompact(versions) }) {
                    Text(stringResource(R.string.git_compact))
                }
            },
        )
    }
}

data class BackupSelection(
    val configurations: Boolean,
    val rules: Boolean,
    val settings: Boolean,
)

data class GitRestoreOption(
    val commitId: String,
    val label: String,
)

@Composable
private fun BackupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun BackupCheckbox(
    checked: Boolean,
    @StringRes label: Int,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .tvFocusTarget()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun BackupActions(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun BackupButton(
    @StringRes label: Int,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().tvFocusTarget(enabled),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            Text(
                text = stringResource(label),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
