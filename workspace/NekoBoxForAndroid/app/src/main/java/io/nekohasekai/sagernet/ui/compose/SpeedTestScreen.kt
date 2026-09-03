package io.nekohasekai.sagernet.ui.compose

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.SpeedTestData
import io.nekohasekai.sagernet.ui.SPEED_TEST_SERVER_AUTO
import io.nekohasekai.sagernet.ui.SPEED_TEST_SERVER_CUSTOM
import io.nekohasekai.sagernet.ui.SPEED_TEST_SERVER_ID
import io.nekohasekai.sagernet.ui.SpeedTestSettings
import io.nekohasekai.sagernet.ui.formatSpeedBits
import io.nekohasekai.sagernet.ui.validateSpeedTestSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeedTestScreen(
    status: SpeedTestData,
    actionEnabled: Boolean,
    settings: SpeedTestSettings?,
    showMeteredConfirmation: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onShowSettings: () -> Unit,
    onDismissSettings: () -> Unit,
    onSaveSettings: (SpeedTestSettings) -> Unit,
    onAction: () -> Unit,
    onDismissMeteredConfirmation: () -> Unit,
    onConfirmMetered: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.speed_test)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.baseline_arrow_back_24),
                            contentDescription = stringResource(R.string.mal_close),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onShowSettings) {
                        Icon(
                            painterResource(R.drawable.ic_action_settings),
                            contentDescription = stringResource(R.string.speed_test_settings),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        SpeedTestContent(
            status = status,
            actionEnabled = actionEnabled,
            onAction = onAction,
            contentPadding = contentPadding,
        )
    }

    settings?.let {
        SpeedTestSettingsDialog(
            initial = it,
            onDismiss = onDismissSettings,
            onSave = onSaveSettings,
        )
    }

    if (showMeteredConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissMeteredConfirmation,
            title = { Text(stringResource(R.string.speed_test_metered_title)) },
            text = { Text(stringResource(R.string.speed_test_metered_message)) },
            dismissButton = {
                TextButton(onClick = onDismissMeteredConfirmation) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmMetered) {
                    Text(stringResource(R.string.speed_test_continue))
                }
            },
        )
    }
}

@Composable
private fun SpeedTestContent(
    status: SpeedTestData,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(phaseText(status.phase)),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        if (status.phase == SpeedTestData.PHASE_FINDING_SERVER) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { status.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            if (isTelevisionUi()) {
                Row(Modifier.padding(20.dp)) {
                    SpeedMetric(
                        R.string.speed_test_download_label,
                        status.downloadRate,
                        Modifier.weight(1f),
                    )
                    SpeedMetric(
                        R.string.speed_test_upload_label,
                        status.uploadRate,
                        Modifier.weight(1f),
                    )
                }
            } else {
                Column(Modifier.padding(20.dp)) {
                    SpeedMetric(R.string.speed_test_download_label, status.downloadRate)
                    SpeedMetric(
                        R.string.speed_test_upload_label,
                        status.uploadRate,
                        Modifier.padding(top = 20.dp),
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                DetailText(
                    if (status.latencyMilliseconds > 0) {
                        stringResource(R.string.speed_test_latency, status.latencyMilliseconds)
                    } else {
                        stringResource(R.string.speed_test_latency_empty)
                    },
                )
                DetailText(
                    if (status.downloadedBytes > 0 || status.uploadedBytes > 0) {
                        stringResource(
                            R.string.speed_test_transferred,
                            Formatter.formatFileSize(context, status.downloadedBytes),
                            Formatter.formatFileSize(context, status.uploadedBytes),
                        )
                    } else {
                        stringResource(R.string.speed_test_transferred_empty)
                    },
                    Modifier.padding(top = 12.dp),
                )
                DetailText(serverText(status), Modifier.padding(top = 12.dp))
                DetailText(routeText(status), Modifier.padding(top = 12.dp))
                val error = speedTestErrorText(status)
                if (error.isNotEmpty()) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }

        Button(
            onClick = onAction,
            enabled = actionEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        ) {
            Text(
                stringResource(
                    when {
                        status.isRunning -> R.string.stop
                        status.phase in SpeedTestData.PHASE_COMPLETE..SpeedTestData.PHASE_CANCELLED ->
                            R.string.speed_test_again
                        else -> R.string.start
                    },
                ),
            )
        }
    }
}

@Composable
private fun SpeedMetric(@StringRes label: Int, rate: Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        MetricLabel(label)
        Text(
            formatSpeedBits(rate),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun MetricLabel(id: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(id),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier,
    )
}

@Composable
private fun DetailText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}

private fun phaseText(phase: Int): Int = when (phase) {
    SpeedTestData.PHASE_FINDING_SERVER -> R.string.speed_test_finding_server
    SpeedTestData.PHASE_DOWNLOAD -> R.string.speed_test_downloading
    SpeedTestData.PHASE_UPLOAD -> R.string.speed_test_uploading
    SpeedTestData.PHASE_COMPLETE -> R.string.speed_test_complete
    SpeedTestData.PHASE_ERROR -> R.string.speed_test_failed
    SpeedTestData.PHASE_CANCELLED -> R.string.speed_test_cancelled
    else -> R.string.speed_test_ready
}

@Composable
private fun serverText(status: SpeedTestData): String = when {
    status.serverName.isBlank() -> stringResource(R.string.speed_test_server_empty)
    status.serverCountry.isBlank() || status.serverCountry == "?" ->
        stringResource(R.string.speed_test_server, status.serverName)
    else -> stringResource(
        R.string.speed_test_server_with_country,
        status.serverName,
        status.serverCountry,
    )
}

@Composable
private fun routeText(status: SpeedTestData): String = if (status.runId == 0L) {
    stringResource(R.string.speed_test_route_empty)
} else {
    stringResource(
        R.string.speed_test_route,
        stringResource(
            if (status.usingProxy) R.string.speed_test_route_proxy
            else R.string.speed_test_route_direct,
        ),
    )
}

@Composable
private fun speedTestErrorText(status: SpeedTestData): String = when (status.errorCode) {
    "" -> ""
    "no_server" -> stringResource(R.string.speed_test_error_no_server)
    "no_reachable_server" -> stringResource(R.string.speed_test_error_no_reachable_server)
    "server_list_failed" -> stringResource(R.string.speed_test_error_server_list)
    "server_list_timeout" -> stringResource(R.string.speed_test_error_server_timeout)
    "latency_failed" -> stringResource(R.string.speed_test_error_latency)
    "download_failed" -> stringResource(R.string.speed_test_error_download)
    "upload_failed" -> stringResource(R.string.speed_test_error_upload)
    "invalid_configuration" -> stringResource(R.string.speed_test_error_invalid_configuration)
    "proxy_changing" -> stringResource(R.string.speed_test_proxy_changing)
    "service_unavailable" -> stringResource(R.string.speed_test_service_unavailable)
    else -> stringResource(
        R.string.speed_test_error_generic,
        status.errorMessage.ifBlank { status.errorCode },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedTestSettingsDialog(
    initial: SpeedTestSettings,
    onDismiss: () -> Unit,
    onSave: (SpeedTestSettings) -> Unit,
) {
    val serverLabels = listOf(
        stringResource(R.string.speed_test_server_auto),
        stringResource(R.string.speed_test_server_id),
        stringResource(R.string.speed_test_server_search),
        stringResource(R.string.speed_test_server_custom),
    )
    val resultLabels = listOf(
        stringResource(R.string.speed_test_final_average),
        stringResource(R.string.speed_test_final_last),
        stringResource(R.string.speed_test_final_minimum),
        stringResource(R.string.speed_test_final_maximum),
    )
    var mode by remember { mutableIntStateOf(initial.serverMode.coerceIn(serverLabels.indices)) }
    var finalResult by remember { mutableIntStateOf(initial.finalResult.coerceIn(resultLabels.indices)) }
    var serverValue by remember { mutableStateOf(initial.serverValue) }
    var duration by remember { mutableStateOf((initial.durationMillis / 1000).toString()) }
    var connections by remember { mutableStateOf(initial.connections.toString()) }
    var durationError by remember { mutableStateOf(false) }
    var connectionsError by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf(false) }

    fun reset() {
        val defaults = SpeedTestSettings()
        mode = defaults.serverMode
        finalResult = defaults.finalResult
        serverValue = defaults.serverValue
        duration = (defaults.durationMillis / 1000).toString()
        connections = defaults.connections.toString()
        durationError = false
        connectionsError = false
        serverError = false
    }

    fun save() {
        val candidate = SpeedTestSettings(
            durationMillis = (duration.toIntOrNull() ?: 0) * 1000,
            connections = connections.toIntOrNull() ?: 0,
            serverMode = mode,
            serverValue = serverValue.trim(),
            finalResult = finalResult,
        )
        durationError = candidate.durationMillis !in 1000..30000
        connectionsError = candidate.connections !in 1..16
        serverError = !durationError && !connectionsError && !validateSpeedTestSettings(candidate)
        if (validateSpeedTestSettings(candidate)) onSave(candidate)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speed_test_settings)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                DropdownField(
                    label = stringResource(R.string.speed_test_server_mode),
                    options = serverLabels,
                    selected = mode,
                    onSelected = {
                        mode = it
                        serverError = false
                    },
                )
                DropdownField(
                    label = stringResource(R.string.speed_test_final_result),
                    options = resultLabels,
                    selected = finalResult,
                    onSelected = { finalResult = it },
                )
                if (mode != SPEED_TEST_SERVER_AUTO) {
                    TextField(
                        value = serverValue,
                        onValueChange = {
                            serverValue = it
                            serverError = false
                        },
                        label = {
                            Text(
                                stringResource(
                                    when (mode) {
                                        SPEED_TEST_SERVER_ID -> R.string.speed_test_server_id_hint
                                        SPEED_TEST_SERVER_CUSTOM -> R.string.speed_test_server_custom_hint
                                        else -> R.string.speed_test_server_search_hint
                                    },
                                ),
                            )
                        },
                        isError = serverError,
                        supportingText = if (serverError) {
                            { Text(stringResource(R.string.speed_test_invalid_settings)) }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (mode == SPEED_TEST_SERVER_ID) {
                                KeyboardType.Number
                            } else {
                                KeyboardType.Uri
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextField(
                    value = duration,
                    onValueChange = {
                        if (it.length <= 2) duration = it.filter(Char::isDigit)
                        durationError = false
                    },
                    label = { Text(stringResource(R.string.speed_test_duration_seconds)) },
                    isError = durationError,
                    supportingText = { Text(stringResource(R.string.speed_test_duration_range)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = connections,
                    onValueChange = {
                        if (it.length <= 2) connections = it.filter(Char::isDigit)
                        connectionsError = false
                    },
                    label = { Text(stringResource(R.string.speed_test_connections)) },
                    isError = connectionsError,
                    supportingText = { Text(stringResource(R.string.speed_test_connections_range)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = ::reset) {
                    Text(stringResource(R.string.speed_test_reset))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = ::save) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        TextField(
            value = options[selected],
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(index)
                        expanded = false
                    },
                )
            }
        }
    }
}
