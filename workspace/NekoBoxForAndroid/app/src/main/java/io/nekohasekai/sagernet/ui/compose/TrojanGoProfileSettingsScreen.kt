package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

@Composable
internal fun TrojanGoProfileSettingsScreen() {
    val context = LocalContext.current
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    val networkLabels = stringArrayResource(R.array.trojan_go_networks_entry).toList()
    val networkValues = stringArrayResource(R.array.trojan_go_networks_value).toList()
    val encryptionLabels = stringArrayResource(R.array.trojan_go_security_entry).toList()
    val encryptionValues = stringArrayResource(R.array.trojan_go_security_value).toList()
    val methods = stringArrayResource(R.array.trojan_go_methods).toList()
    val nameTitle = stringResource(R.string.profile_name)
    val addressTitle = stringResource(R.string.server_address)
    val portTitle = stringResource(R.string.server_port)
    val passwordTitle = stringResource(R.string.password)
    val sniTitle = stringResource(R.string.sni)
    val networkTitle = stringResource(R.string.network)
    val encryptionTitle = stringResource(R.string.encryption)
    val hostTitle = stringResource(R.string.ws_host)
    val pathTitle = stringResource(R.string.ws_path)
    val methodTitle = stringResource(R.string.enc_method)
    val insecureSummary = stringResource(R.string.allow_insecure_sum)
    var name by remember { mutableStateOf(DataStore.profileName) }
    var address by remember { mutableStateOf(DataStore.serverAddress) }
    var port by remember { mutableStateOf(DataStore.serverPort.toString()) }
    var password by remember { mutableStateOf(DataStore.serverPassword) }
    var sni by remember { mutableStateOf(DataStore.serverSNI) }
    var insecure by remember { mutableStateOf(DataStore.serverAllowInsecure) }
    var network by remember { mutableStateOf(DataStore.serverNetwork) }
    var encryption by remember { mutableStateOf(DataStore.serverEncryption) }
    var host by remember { mutableStateOf(DataStore.serverHost) }
    var path by remember { mutableStateOf(DataStore.serverPath) }
    var method by remember { mutableStateOf(DataStore.serverMethod) }
    var shadowsocksPassword by remember { mutableStateOf(DataStore.serverPassword1) }

    fun edit(
        title: String,
        value: String,
        keyboard: KeyboardType = KeyboardType.Text,
        maxLength: Int = Int.MAX_VALUE,
        secret: Boolean = false,
        update: (String) -> Unit,
    ) = context.showComposeTextInputDialog(
        title = title,
        initialValue = value,
        keyboardType = keyboard,
        maxLength = maxLength,
        password = secret,
        onPositive = update,
    )
    fun choose(
        title: String,
        labels: List<String>,
        selected: Int,
        update: (Int) -> Unit,
    ) = context.showComposeSingleChoiceDialog(
        title = title,
        items = labels,
        selectedIndex = selected.coerceIn(labels.indices),
        negativeButton = cancel,
        onItemSelected = update,
    )
    fun summary(value: String) = value.ifBlank { notSet }
    fun secretSummary(value: String) = value.takeIf(String::isNotBlank)
        ?.let { "\u2022".repeat(it.length) } ?: notSet

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name, summary(name)) {
                edit(nameTitle, name) { name = it; DataStore.profileName = it }
            }
        }
        item { ProfileCategory(R.string.proxy_cat) }
        item {
            ProfileActionRow(R.drawable.ic_hardware_router, R.string.server_address, summary(address)) {
                edit(addressTitle, address) { address = it; DataStore.serverAddress = it }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.server_port, summary(port)) {
                edit(portTitle, port, KeyboardType.Number, 5) {
                    port = it
                    DataStore.serverPort = it.toIntOrNull() ?: 0
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_settings_password, R.string.password, secretSummary(password)) {
                edit(passwordTitle, password, KeyboardType.Password, secret = true) {
                    password = it
                    DataStore.serverPassword = it
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_action_copyright, R.string.sni, summary(sni)) {
                edit(sniTitle, sni) { sni = it; DataStore.serverSNI = it }
            }
        }
        item {
            ProfileSwitchRow(
                R.drawable.ic_notification_enhanced_encryption,
                R.string.allow_insecure,
                insecure,
                summary = insecureSummary,
                dynamicSummary = false,
            ) {
                insecure = it
                DataStore.serverAllowInsecure = it
            }
        }
        item {
            val selected = networkValues.indexOf(network).coerceAtLeast(0)
            ProfileActionRow(
                R.drawable.ic_baseline_compare_arrows_24,
                R.string.network,
                networkLabels[selected],
            ) {
                choose(networkTitle, networkLabels, selected) {
                    network = networkValues[it]
                    DataStore.serverNetwork = network
                }
            }
        }
        item {
            val selected = encryptionValues.indexOf(encryption).coerceAtLeast(0)
            ProfileActionRow(
                R.drawable.ic_baseline_layers_24,
                R.string.encryption,
                encryptionLabels[selected],
            ) {
                choose(encryptionTitle, encryptionLabels, selected) {
                    encryption = encryptionValues[it]
                    DataStore.serverEncryption = encryption
                    if (encryption == "ss" && method !in methods) {
                        method = methods.first()
                        DataStore.serverMethod = method
                    }
                }
            }
        }
        if (network == "ws") {
            item { ProfileCategory(R.string.cag_ws) }
            item {
                ProfileActionRow(R.drawable.ic_baseline_airplanemode_active_24, R.string.ws_host,
                    summary(host)) {
                    edit(hostTitle, host) { host = it; DataStore.serverHost = it }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_format_align_left_24, R.string.ws_path,
                    summary(path)) {
                    edit(pathTitle, path) { path = it; DataStore.serverPath = it }
                }
            }
        }
        if (encryption == "ss") {
            item { ProfileCategory(R.string.ss_cat) }
            item {
                val selected = methods.indexOf(method).coerceAtLeast(0)
                ProfileActionRow(
                    R.drawable.ic_notification_enhanced_encryption,
                    R.string.enc_method,
                    methods[selected],
                ) {
                    choose(methodTitle, methods, selected) {
                        method = methods[it]
                        DataStore.serverMethod = method
                    }
                }
            }
            item {
                ProfileActionRow(
                    R.drawable.ic_settings_password,
                    R.string.password,
                    secretSummary(shadowsocksPassword),
                ) {
                    edit(passwordTitle, shadowsocksPassword, KeyboardType.Password, secret = true) {
                        shadowsocksPassword = it
                        DataStore.serverPassword1 = it
                    }
                }
            }
        }
        item { SharedDialOptions() }
        item { SharedTlsOptions() }
    }
}
