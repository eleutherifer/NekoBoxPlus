package io.nekohasekai.sagernet.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.R

internal data class CustomDnsServerSettingsUiState(
    val tag: String = "",
    val type: String = "udp",
    val enabled: Boolean = true,
    val server: String = "",
    val serverPort: String = "",
    val path: String = "",
    val method: String = "",
    val headers: String = "",
    val domainResolver: String = "dns-direct",
    val domainStrategy: String = "",
    val rewriteTtl: String = "",
    val clientSubnet: String = "",
    val detour: String = "direct",
    val bindInterface: String = "",
    val inet4BindAddress: String = "",
    val inet6BindAddress: String = "",
    val connectTimeout: String = "",
    val udpFragment: String = "",
    val tlsServerName: String = "",
    val tlsAlpn: String = "",
    val tlsCertificates: String = "",
)

private val dnsTypes = listOf("udp", "tcp", "tls", "quic", "https", "h3", "local")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomDnsServerSettingsScreen(
    state: CustomDnsServerSettingsUiState,
    canDelete: Boolean,
    @StringRes deletePrompt: Int?,
    onStateChange: (CustomDnsServerSettingsUiState) -> Unit,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
) {
    val remote = state.type != "local"
    val https = state.type == "https" || state.type == "h3"
    val tls = state.type in setOf("tls", "quic", "https", "h3")
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
                    if (canDelete) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                painterResource(R.drawable.ic_baseline_delete_24),
                                contentDescription = stringResource(R.string.delete),
                            )
                        }
                    }
                    IconButton(onClick = onSave) {
                        Icon(
                            painterResource(R.drawable.ic_action_done),
                            contentDescription = stringResource(R.string.apply),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = 6.dp,
                end = 16.dp,
                bottom = 16.dp,
            ),
        ) {
            item {
                DnsTextField(state.tag, { onStateChange(state.copy(tag = it)) }, R.string.dns_server_name)
                DnsDropdownField(
                    value = state.type,
                    options = dnsTypes.map { it to it },
                    label = R.string.dns_server_type,
                    compact = true,
                    onValueChange = { onStateChange(state.copy(type = it)) },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .clickable { onStateChange(state.copy(enabled = !state.enabled)) },
                ) {
                    Text(
                        stringResource(R.string.enable),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = state.enabled, onCheckedChange = { onStateChange(state.copy(enabled = it)) })
                }
                if (remote) {
                    DnsTextField(state.server, { onStateChange(state.copy(server = it)) }, R.string.server_address)
                    DnsTextField(
                        state.serverPort,
                        { onStateChange(state.copy(serverPort = it)) },
                        R.string.server_port,
                        keyboardType = KeyboardType.Number,
                    )
                }
                if (https) {
                    DnsTextField(state.path, { onStateChange(state.copy(path = it)) }, R.string.custom_dns_path)
                    DnsTextField(state.method, { onStateChange(state.copy(method = it)) }, R.string.custom_dns_http_method)
                    DnsTextField(
                        state.headers,
                        { onStateChange(state.copy(headers = it)) },
                        R.string.custom_dns_headers,
                        singleLine = false,
                    )
                }
                if (remote) {
                    DnsDropdownField(
                        value = state.domainResolver,
                        options = listOf(
                            "dns-direct" to stringResource(R.string.dns_route_direct),
                            "dns-remote" to stringResource(R.string.dns_route_remote),
                        ),
                        label = R.string.domain_resolver,
                        onValueChange = { onStateChange(state.copy(domainResolver = it)) },
                    )
                    DnsTextField(
                        state.domainStrategy,
                        { onStateChange(state.copy(domainStrategy = it)) },
                        R.string.domain_strategy,
                    )
                    DnsTextField(
                        state.rewriteTtl,
                        { onStateChange(state.copy(rewriteTtl = it)) },
                        R.string.dns_rewrite_ttl,
                        keyboardType = KeyboardType.Number,
                    )
                    DnsTextField(
                        state.clientSubnet,
                        { onStateChange(state.copy(clientSubnet = it)) },
                        R.string.dns_client_subnet,
                    )
                }
                DnsDropdownField(
                    value = state.detour,
                    options = listOf(
                        "direct" to stringResource(R.string.dns_route_direct),
                        "proxy" to stringResource(R.string.custom_dns_detour_proxy),
                    ),
                    label = R.string.custom_dns_detour,
                    onValueChange = { onStateChange(state.copy(detour = it)) },
                )
                DnsTextField(
                    state.bindInterface,
                    { onStateChange(state.copy(bindInterface = it)) },
                    R.string.custom_dns_bind_interface,
                )
                DnsTextField(
                    state.inet4BindAddress,
                    { onStateChange(state.copy(inet4BindAddress = it)) },
                    R.string.custom_dns_ipv4_bind_address,
                )
                DnsTextField(
                    state.inet6BindAddress,
                    { onStateChange(state.copy(inet6BindAddress = it)) },
                    R.string.custom_dns_ipv6_bind_address,
                )
                DnsTextField(
                    state.connectTimeout,
                    { onStateChange(state.copy(connectTimeout = it)) },
                    R.string.custom_dns_connect_timeout,
                    keyboardType = KeyboardType.Number,
                )
                if (remote) {
                    DnsTextField(
                        state.udpFragment,
                        { onStateChange(state.copy(udpFragment = it)) },
                        R.string.custom_dns_udp_fragment,
                    )
                }
                if (tls) {
                    DnsTextField(
                        state.tlsServerName,
                        { onStateChange(state.copy(tlsServerName = it)) },
                        R.string.custom_dns_tls_server_name,
                    )
                    DnsTextField(
                        state.tlsAlpn,
                        { onStateChange(state.copy(tlsAlpn = it)) },
                        R.string.custom_dns_tls_alpn,
                    )
                    DnsTextField(
                        state.tlsCertificates,
                        { onStateChange(state.copy(tlsCertificates = it)) },
                        R.string.custom_dns_tls_certificates,
                        singleLine = false,
                    )
                }
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

@Composable
private fun DnsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            stringResource(label),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) 39.dp else 73.dp)
                .padding(vertical = 10.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant, thickness = 1.dp)
    }
}

@Composable
private fun DnsDropdownField(
    value: String,
    options: List<Pair<String, String>>,
    @StringRes label: Int,
    compact: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val indicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { expanded = true },
                ),
        ) {
            Text(
                stringResource(label),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (compact) 39.dp else 45.dp)
                    .padding(horizontal = 6.dp),
            ) {
                Text(
                    options.firstOrNull { it.first == value }?.second ?: value,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp),
                    modifier = Modifier.weight(1f),
                )
                Canvas(Modifier.size(18.dp)) {
                    val width = size.width * 0.65f
                    val left = (size.width - width) / 2f
                    val top = size.height * 0.38f
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(left, top)
                        lineTo(left + width, top)
                        lineTo(size.width / 2f, top + width * 0.55f)
                        close()
                    }
                    drawPath(path, indicatorColor)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (option, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        expanded = false
                        onValueChange(option)
                    },
                )
            }
        }
    }
}
