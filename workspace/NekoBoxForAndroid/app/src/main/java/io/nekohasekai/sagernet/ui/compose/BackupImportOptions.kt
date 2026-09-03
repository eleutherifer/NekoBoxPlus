package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R

internal data class BackupImportSelection(
    val profiles: Boolean = true,
    val rules: Boolean = true,
    val settings: Boolean = true,
)

@Composable
internal fun BackupImportDialog(
    selection: BackupImportSelection,
    hasProfiles: Boolean,
    hasRules: Boolean,
    hasSettings: Boolean,
    showGitWarning: Boolean,
    onSelectionChanged: (BackupImportSelection) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = stringResource(R.string.backup_import),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                if (showGitWarning) {
                    Text(
                        text = stringResource(R.string.git_destructive_restore_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                BackupImportOptions(
                    selection = selection,
                    hasProfiles = hasProfiles,
                    hasRules = hasRules,
                    hasSettings = hasSettings,
                    onSelectionChanged = onSelectionChanged,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(onClick = onImport) {
                        Text(stringResource(R.string.backup_import))
                    }
                }
            }
        }
    }
}

@Composable
internal fun BackupImportOptions(
    selection: BackupImportSelection,
    hasProfiles: Boolean,
    hasRules: Boolean,
    hasSettings: Boolean,
    onSelectionChanged: (BackupImportSelection) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (hasProfiles) {
            ImportOption(
                checked = selection.profiles,
                label = stringResource(R.string.backup_groups_and_configurations),
                onCheckedChange = { onSelectionChanged(selection.copy(profiles = it)) },
            )
        }
        if (hasRules) {
            ImportOption(
                checked = selection.rules,
                label = stringResource(R.string.backup_rules),
                onCheckedChange = { onSelectionChanged(selection.copy(rules = it)) },
            )
        }
        if (hasSettings) {
            ImportOption(
                checked = selection.settings,
                label = stringResource(R.string.backup_settings),
                onCheckedChange = { onSelectionChanged(selection.copy(settings = it)) },
            )
        }
        Text(
            text = stringResource(R.string.backup_import_summary),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
        )
    }
}

@Composable
private fun ImportOption(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .tvFocusTarget()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
