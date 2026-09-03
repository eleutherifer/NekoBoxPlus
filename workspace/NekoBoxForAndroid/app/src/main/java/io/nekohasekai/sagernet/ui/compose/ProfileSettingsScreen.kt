package io.nekohasekai.sagernet.ui.compose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean

@Composable
internal fun SocksProfileSettingsScreen() {
    val context = LocalContext.current
    val notSet = stringResource(R.string.not_set)
    val profileNameTitle = stringResource(R.string.profile_name)
    val versionTitle = stringResource(R.string.app_version)
    val serverAddressTitle = stringResource(R.string.server_address)
    val serverPortTitle = stringResource(R.string.server_port)
    val usernameTitle = stringResource(R.string.username_opt)
    val passwordTitle = stringResource(R.string.password_opt)
    val udpFragmentationTitle = stringResource(R.string.udp_fragmentation)
    val tcpKeepAliveTitle = stringResource(R.string.tcp_keep_alive)
    val tcpKeepAliveIntervalTitle = stringResource(R.string.tcp_keep_alive_interval)
    val cancelLabel = stringResource(android.R.string.cancel)
    val protocolLabels = stringArrayResource(R.array.socks_versions).toList()
    var profileName by remember { mutableStateOf(DataStore.profileName) }
    var protocol by remember { mutableIntStateOf(DataStore.serverProtocolInt) }
    var address by remember { mutableStateOf(DataStore.serverAddress) }
    var port by remember { mutableStateOf(DataStore.serverPort.toString()) }
    var username by remember { mutableStateOf(DataStore.serverUsername) }
    var password by remember { mutableStateOf(DataStore.serverPassword) }
    var udpOverTcp by remember {
        mutableStateOf(DataStore.profileCacheStore.getBoolean("sUoT", false))
    }
    var tcpFastOpen by remember {
        mutableStateOf(DataStore.profileCacheStore.getBoolean("tcpFastOpen", false))
    }
    var tcpMultiPath by remember {
        mutableStateOf(DataStore.profileCacheStore.getBoolean("tcpMultiPath", false))
    }
    var udpFragment by remember {
        mutableStateOf(DataStore.profileCacheStore.getString("udpFragment").orEmpty())
    }
    var disableTcpKeepAlive by remember {
        mutableStateOf(DataStore.profileCacheStore.getBoolean("disableTcpKeepAlive", false))
    }
    var tcpKeepAlive by remember {
        mutableStateOf(DataStore.profileCacheStore.getString("tcpKeepAlive").orEmpty())
    }
    var tcpKeepAliveInterval by remember {
        mutableStateOf(DataStore.profileCacheStore.getString("tcpKeepAliveInterval").orEmpty())
    }

    fun editText(
        title: CharSequence,
        initialValue: String,
        keyboardType: KeyboardType = KeyboardType.Text,
        maxLength: Int = Int.MAX_VALUE,
        secret: Boolean = false,
        update: (String) -> Unit,
    ) {
        context.showComposeTextInputDialog(
            title = title,
            initialValue = initialValue,
            keyboardType = keyboardType,
            maxLength = maxLength,
            password = secret,
            onPositive = update,
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        item {
            ProfileActionRow(
                icon = R.drawable.ic_social_emoji_symbols,
                title = R.string.profile_name,
                summary = profileName.ifBlank { notSet },
            ) {
                editText(profileNameTitle, profileName) {
                    profileName = it
                    DataStore.profileName = it
                }
            }
        }
        item { ProfileCategory(R.string.proxy_cat) }
        item {
            ProfileActionRow(
                icon = R.drawable.ic_baseline_nfc_24,
                title = R.string.app_version,
                summary = protocolLabels.getOrElse(protocol) { protocol.toString() },
            ) {
                context.showComposeSingleChoiceDialog(
                    title = versionTitle,
                    items = protocolLabels,
                    selectedIndex = protocol.coerceIn(protocolLabels.indices),
                    negativeButton = cancelLabel,
                    onItemSelected = {
                    protocol = it
                    DataStore.serverProtocolInt = it
                    },
                )
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_hardware_router, R.string.server_address,
                address.ifBlank { notSet }) {
                editText(serverAddressTitle, address) {
                    address = it
                    DataStore.serverAddress = it
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.server_port,
                port.ifBlank { notSet }) {
                editText(serverPortTitle, port, KeyboardType.Number, 5) {
                    port = it
                    DataStore.serverPort = it.toIntOrNull() ?: 0
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_person_24, R.string.username_opt,
                username.ifBlank { notSet }) {
                editText(usernameTitle, username) {
                    username = it
                    DataStore.serverUsername = it
                }
            }
        }
        if (protocol == SOCKSBean.PROTOCOL_SOCKS5) {
            item {
                ProfileActionRow(R.drawable.ic_settings_password, R.string.password_opt,
                    password.takeIf(String::isNotBlank)?.let { "\u2022".repeat(it.length) } ?: notSet) {
                    editText(passwordTitle, password,
                        KeyboardType.Password, secret = true) {
                        password = it
                        DataStore.serverPassword = it
                    }
                }
            }
        }
        item { ProfileCategory(R.string.sing_box_server) }
        item {
            ProfileSwitchRow(R.drawable.ic_baseline_compare_arrows_24, R.string.udp_over_tcp,
                udpOverTcp) {
                udpOverTcp = it
                DataStore.profileCacheStore.putBoolean("sUoT", it)
            }
        }
        item { ProfileCategory(R.string.sing_box_dial_options) }
        item {
            ProfileSwitchRow(R.drawable.ic_baseline_speed_24, R.string.tcp_fast_open, tcpFastOpen) {
                tcpFastOpen = it
                DataStore.profileCacheStore.putBoolean("tcpFastOpen", it)
            }
        }
        item {
            ProfileSwitchRow(R.drawable.ic_baseline_multiple_stop_24, R.string.multipath_tcp,
                tcpMultiPath) {
                tcpMultiPath = it
                DataStore.profileCacheStore.putBoolean("tcpMultiPath", it)
            }
        }
        item {
            val fragmentLabels = listOf(
                stringResource(R.string.connection_option_default),
                stringResource(R.string.connection_option_enabled),
                stringResource(R.string.connection_option_disabled),
            )
            val fragmentValues = listOf("", "true", "false")
            val selected = fragmentValues.indexOf(udpFragment).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_call_split_24, R.string.udp_fragmentation,
                fragmentLabels[selected]) {
                context.showComposeSingleChoiceDialog(
                    title = udpFragmentationTitle,
                    items = fragmentLabels,
                    selectedIndex = selected,
                    negativeButton = cancelLabel,
                    onItemSelected = {
                        udpFragment = fragmentValues[it]
                        DataStore.profileCacheStore.putString("udpFragment", udpFragment)
                    },
                )
            }
        }
        item {
            ProfileSwitchRow(R.drawable.ic_baseline_timer_24, R.string.disable_tcp_keep_alive,
                disableTcpKeepAlive) {
                disableTcpKeepAlive = it
                DataStore.profileCacheStore.putBoolean("disableTcpKeepAlive", it)
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_timer_24, R.string.tcp_keep_alive,
                tcpKeepAlive.ifBlank { notSet }) {
                editText(tcpKeepAliveTitle, tcpKeepAlive) {
                    tcpKeepAlive = it
                    DataStore.profileCacheStore.putString("tcpKeepAlive", it)
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_timelapse_24, R.string.tcp_keep_alive_interval,
                tcpKeepAliveInterval.ifBlank { notSet }) {
                editText(tcpKeepAliveIntervalTitle, tcpKeepAliveInterval) {
                    tcpKeepAliveInterval = it
                    DataStore.profileCacheStore.putString("tcpKeepAliveInterval", it)
                }
            }
        }
    }
}

@Composable
internal fun ShadowsocksRProfileSettingsScreen() {
    val context = LocalContext.current
    val notSet = stringResource(R.string.not_set)
    val cancelLabel = stringResource(android.R.string.cancel)
    val profileNameTitle = stringResource(R.string.profile_name)
    val serverAddressTitle = stringResource(R.string.server_address)
    val serverPortTitle = stringResource(R.string.server_port)
    val methodTitle = stringResource(R.string.enc_method)
    val passwordTitle = stringResource(R.string.password)
    val protocolTitle = stringResource(R.string.protocol)
    val protocolParamTitle = stringResource(R.string.protocol_param)
    val obfsTitle = stringResource(R.string.obfs)
    val obfsParamTitle = stringResource(R.string.obfs_param)
    val methods = stringArrayResource(R.array.ssr_enc_method_value).toList()
    val protocols = stringArrayResource(R.array.ssr_protocol_value).toList()
    val obfsValues = stringArrayResource(R.array.ssr_obfs_value).toList()

    var profileName by remember { mutableStateOf(DataStore.profileName) }
    var address by remember { mutableStateOf(DataStore.serverAddress) }
    var port by remember { mutableStateOf(DataStore.serverPort.toString()) }
    var method by remember { mutableStateOf(DataStore.serverMethod) }
    var password by remember { mutableStateOf(DataStore.serverPassword) }
    var protocol by remember { mutableStateOf(DataStore.serverProtocol) }
    var protocolParam by remember { mutableStateOf(DataStore.serverProtocolParam) }
    var obfs by remember { mutableStateOf(DataStore.serverObfs) }
    var obfsParam by remember { mutableStateOf(DataStore.serverObfsParam) }

    fun editText(
        title: String,
        initialValue: String,
        keyboardType: KeyboardType = KeyboardType.Text,
        maxLength: Int = Int.MAX_VALUE,
        secret: Boolean = false,
        update: (String) -> Unit,
    ) {
        context.showComposeTextInputDialog(
            title = title,
            initialValue = initialValue,
            keyboardType = keyboardType,
            maxLength = maxLength,
            password = secret,
            onPositive = update,
        )
    }

    fun choose(title: String, values: List<String>, selected: String, update: (String) -> Unit) {
        context.showComposeSingleChoiceDialog(
            title = title,
            items = values,
            selectedIndex = values.indexOf(selected).coerceAtLeast(0),
            negativeButton = cancelLabel,
            onItemSelected = { update(values[it]) },
        )
    }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name,
                profileName.ifBlank { notSet }) {
                editText(profileNameTitle, profileName) {
                    profileName = it
                    DataStore.profileName = it
                }
            }
        }
        item { ProfileCategory(R.string.proxy_cat) }
        item {
            ProfileActionRow(R.drawable.ic_hardware_router, R.string.server_address,
                address.ifBlank { notSet }) {
                editText(serverAddressTitle, address) {
                    address = it
                    DataStore.serverAddress = it
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.server_port,
                port.ifBlank { notSet }) {
                editText(serverPortTitle, port, KeyboardType.Number, 5) {
                    port = it
                    DataStore.serverPort = it.toIntOrNull() ?: 0
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_notification_enhanced_encryption, R.string.enc_method,
                method.ifBlank { notSet }) {
                choose(methodTitle, methods, method) {
                    method = it
                    DataStore.serverMethod = it
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_settings_password, R.string.password,
                password.takeIf(String::isNotBlank)?.let { "\u2022".repeat(it.length) } ?: notSet) {
                editText(passwordTitle, password, KeyboardType.Password, secret = true) {
                    password = it
                    DataStore.serverPassword = it
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_compare_arrows_24, R.string.protocol,
                protocol.ifBlank { notSet }) {
                choose(protocolTitle, protocols, protocol) {
                    protocol = it
                    DataStore.serverProtocol = it
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_format_align_left_24, R.string.protocol_param,
                protocolParam.ifBlank { notSet }) {
                editText(protocolParamTitle, protocolParam) {
                    protocolParam = it
                    DataStore.serverProtocolParam = it
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_texture_24, R.string.obfs,
                obfs.ifBlank { notSet }) {
                choose(obfsTitle, obfsValues, obfs) {
                    obfs = it
                    DataStore.serverObfs = it
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_format_align_left_24, R.string.obfs_param,
                obfsParam.ifBlank { notSet }) {
                editText(obfsParamTitle, obfsParam) {
                    obfsParam = it
                    DataStore.serverObfsParam = it
                }
            }
        }
        item { SharedDialOptions() }
    }
}

@Composable
internal fun SharedDialOptions() {
    val context = LocalContext.current
    val notSet = stringResource(R.string.not_set)
    val cancelLabel = stringResource(android.R.string.cancel)
    val fragmentationTitle = stringResource(R.string.udp_fragmentation)
    val keepAliveTitle = stringResource(R.string.tcp_keep_alive)
    val keepAliveIntervalTitle = stringResource(R.string.tcp_keep_alive_interval)
    var fastOpen by remember { mutableStateOf(DataStore.profileCacheStore.getBoolean("tcpFastOpen", false)) }
    var multiPath by remember { mutableStateOf(DataStore.profileCacheStore.getBoolean("tcpMultiPath", false)) }
    var fragmentation by remember { mutableStateOf(DataStore.profileCacheStore.getString("udpFragment").orEmpty()) }
    var disableKeepAlive by remember { mutableStateOf(DataStore.profileCacheStore.getBoolean("disableTcpKeepAlive", false)) }
    var keepAlive by remember { mutableStateOf(DataStore.profileCacheStore.getString("tcpKeepAlive").orEmpty()) }
    var keepAliveInterval by remember { mutableStateOf(DataStore.profileCacheStore.getString("tcpKeepAliveInterval").orEmpty()) }
    val labels = listOf(
        stringResource(R.string.connection_option_default),
        stringResource(R.string.connection_option_enabled),
        stringResource(R.string.connection_option_disabled),
    )
    val values = listOf("", "true", "false")
    val selected = values.indexOf(fragmentation).coerceAtLeast(0)

    Column {
        ProfileCategory(R.string.sing_box_dial_options)
        ProfileSwitchRow(R.drawable.ic_baseline_speed_24, R.string.tcp_fast_open, fastOpen) {
            fastOpen = it
            DataStore.profileCacheStore.putBoolean("tcpFastOpen", it)
        }
        ProfileSwitchRow(R.drawable.ic_baseline_multiple_stop_24, R.string.multipath_tcp, multiPath) {
            multiPath = it
            DataStore.profileCacheStore.putBoolean("tcpMultiPath", it)
        }
        ProfileActionRow(R.drawable.ic_baseline_call_split_24, R.string.udp_fragmentation,
            labels[selected]) {
            context.showComposeSingleChoiceDialog(
                title = fragmentationTitle,
                items = labels,
                selectedIndex = selected,
                negativeButton = cancelLabel,
                onItemSelected = {
                    fragmentation = values[it]
                    DataStore.profileCacheStore.putString("udpFragment", fragmentation)
                },
            )
        }
        ProfileSwitchRow(R.drawable.ic_baseline_timer_24, R.string.disable_tcp_keep_alive,
            disableKeepAlive) {
            disableKeepAlive = it
            DataStore.profileCacheStore.putBoolean("disableTcpKeepAlive", it)
        }
        ProfileActionRow(R.drawable.ic_baseline_timer_24, R.string.tcp_keep_alive,
            keepAlive.ifBlank { notSet }) {
            context.showComposeTextInputDialog(
                title = keepAliveTitle,
                initialValue = keepAlive,
                onPositive = {
                    keepAlive = it
                    DataStore.profileCacheStore.putString("tcpKeepAlive", it)
                },
            )
        }
        ProfileActionRow(R.drawable.ic_baseline_timelapse_24, R.string.tcp_keep_alive_interval,
            keepAliveInterval.ifBlank { notSet }) {
            context.showComposeTextInputDialog(
                title = keepAliveIntervalTitle,
                initialValue = keepAliveInterval,
                onPositive = {
                    keepAliveInterval = it
                    DataStore.profileCacheStore.putString("tcpKeepAliveInterval", it)
                },
            )
        }
    }
}

@Composable
internal fun ProfileCategory(@StringRes title: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 72.dp, top = 20.dp, end = 16.dp, bottom = 8.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = stringResource(title),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
internal fun ProfileActionRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    summary: String?,
    enabled: Boolean = true,
    dynamicSummary: Boolean = true,
    onClick: () -> Unit,
) = ProfileRow(icon, title, summary, enabled, dynamicSummary, onClick, null)

@Composable
@Suppress("ComposableLambdaParameterNaming", "ComposableLambdaParameterPosition")
internal fun ProfileCustomActionRow(
    @StringRes title: Int,
    summary: String? = null,
    enabled: Boolean = true,
    dynamicSummary: Boolean = true,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) = ProfileRow(0, title, summary, enabled, dynamicSummary, onClick, null, leading)

@Composable
internal fun ProfileSwitchRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    checked: Boolean,
    summary: String? = null,
    enabled: Boolean = true,
    dynamicSummary: Boolean = true,
    onChecked: (Boolean) -> Unit,
) = ProfileRow(
    icon = icon,
    title = title,
    summary = summary,
    enabled = enabled,
    dynamicSummary = dynamicSummary,
    onClick = { onChecked(!checked) },
    content = {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    },
)

@Composable
private fun ProfileRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    summary: String?,
    enabled: Boolean,
    dynamicSummary: Boolean,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)?,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusTarget(enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = if (summary == null) 56.dp else 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) {
            if (leading != null) {
                leading()
            } else {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.38f,
                    ),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(title),
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (enabled) 1f else 0.38f,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            summary?.let {
                Text(
                    text = if (dynamicSummary) it.take(PROFILE_SUMMARY_MAX_LENGTH) else it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.38f,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (dynamicSummary) PROFILE_SUMMARY_MAX_LINES else Int.MAX_VALUE,
                    overflow = if (dynamicSummary) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
            }
        }
        content?.invoke()
    }
}

private const val PROFILE_SUMMARY_MAX_LINES = 2
private const val PROFILE_SUMMARY_MAX_LENGTH = 300
