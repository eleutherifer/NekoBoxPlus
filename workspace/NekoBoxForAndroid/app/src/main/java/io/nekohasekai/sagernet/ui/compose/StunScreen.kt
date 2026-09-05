package io.nekohasekai.sagernet.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.StunPreset

internal data class StunResultPresentation(
    val assessmentTitle: String,
    val assessmentImpact: String,
    val technicalSummary: String,
    val servers: List<StunServerPresentation>,
)

internal data class StunServerPresentation(
    val name: String,
    val status: String,
    val details: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StunScreen(
    selectedPreset: StunPreset,
    customServers: String,
    customServersError: String?,
    isRunning: Boolean,
    progressText: String,
    result: StunResultPresentation?,
    detailsExpanded: Boolean,
    onClose: () -> Unit,
    onPresetSelected: (StunPreset) -> Unit,
    onCustomServersChanged: (String) -> Unit,
    onAction: () -> Unit,
    onToggleDetails: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stun_test)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painterResource(R.drawable.baseline_arrow_back_24),
                            contentDescription = stringResource(R.string.mal_close),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.stun_test_summary), style = MaterialTheme.typography.bodyMedium)
            ConfigurationCard(
                selectedPreset,
                customServers,
                customServersError,
                !isRunning,
                onPresetSelected,
                onCustomServersChanged,
            )
            if (isRunning) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(progressText, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (result != null) {
                SummaryCard(result, detailsExpanded, onToggleDetails)
                if (detailsExpanded) Details(result)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onAction) {
                    Icon(
                        painterResource(
                            if (isRunning) R.drawable.ic_navigation_close
                            else R.drawable.ic_baseline_play_arrow_24,
                        ),
                        contentDescription = null,
                    )
                    Text(
                        stringResource(if (isRunning) R.string.cancel else R.string.start),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigurationCard(
    selectedPreset: StunPreset,
    customServers: String,
    customServersError: String?,
    enabled: Boolean,
    onPresetSelected: (StunPreset) -> Unit,
    onCustomServersChanged: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (enabled) expanded = !expanded },
            ) {
                OutlinedTextField(
                    value = stringResource(selectedPreset.titleRes),
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.stun_server_preset)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    StunPreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(stringResource(preset.titleRes)) },
                            onClick = {
                                expanded = false
                                onPresetSelected(preset)
                            },
                        )
                    }
                }
            }
            Text(stringResource(selectedPreset.descriptionRes), style = MaterialTheme.typography.bodySmall)
            if (selectedPreset == StunPreset.CUSTOM) {
                OutlinedTextField(
                    value = customServers,
                    onValueChange = onCustomServersChanged,
                    enabled = enabled,
                    minLines = 3,
                    maxLines = 8,
                    label = { Text(stringResource(R.string.stun_custom_servers)) },
                    supportingText = {
                        Text(customServersError ?: stringResource(R.string.stun_custom_servers_helper))
                    },
                    isError = customServersError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(stringResource(R.string.stun_privacy_notice), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SummaryCard(
    result: StunResultPresentation,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(result.assessmentTitle, style = MaterialTheme.typography.titleMedium)
            Text(result.assessmentImpact, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.stun_result_disclaimer), style = MaterialTheme.typography.bodySmall)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onToggleDetails) {
                    Icon(painterResource(R.drawable.ic_baseline_info_24), contentDescription = null)
                    Text(
                        stringResource(
                            if (detailsExpanded) R.string.stun_hide_details
                            else R.string.stun_show_details,
                        ),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Details(result: StunResultPresentation) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.stun_technical_summary), style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(result.technicalSummary, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    Text(stringResource(R.string.stun_server_results), style = MaterialTheme.typography.titleMedium)
    result.servers.forEach { server ->
        Card(modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium)
                    Text(server.status, style = MaterialTheme.typography.labelLarge)
                    Text(server.details, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
