package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
internal fun SnellProfileSettingsScreen() {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val cancelLabel = stringResource(android.R.string.cancel)
    val reuseSummary = stringResource(R.string.snell_reuse_summary)
    val quicProxySummary = stringResource(R.string.snell_quic_proxy_summary)
    val versions = stringArrayResource(R.array.snell_versions_value).toList()
    val networkLabelsAll = stringArrayResource(R.array.snell_network_entry).toList()
    val networkValuesAll = stringArrayResource(R.array.snell_network_value).toList()
    val obfsLabelsAll = stringArrayResource(R.array.snell_obfs_modes_entry).toList()
    val obfsValuesAll = stringArrayResource(R.array.snell_obfs_modes_value).toList()
    val modeLabels = stringArrayResource(R.array.snell_v6_modes_entry).toList()
    val modeValues = stringArrayResource(R.array.snell_v6_modes_value).toList()
    val nameTitle = stringResource(R.string.profile_name)
    val addressTitle = stringResource(R.string.server_address)
    val portTitle = stringResource(R.string.server_port)
    val pskTitle = stringResource(R.string.snell_psk)
    val versionTitle = stringResource(R.string.snell_version)
    val networkTitle = stringResource(R.string.snell_network)
    val obfsModeTitle = stringResource(R.string.snell_obfs_mode)
    val obfsHostTitle = stringResource(R.string.snell_obfs_host)
    val userKeyTitle = stringResource(R.string.snell_user_key)
    val modeTitle = stringResource(R.string.snell_v6_mode)

    var name by remember { mutableStateOf(store.getString("name").orEmpty()) }
    var address by remember { mutableStateOf(store.getString("serverAddress").orEmpty()) }
    var port by remember { mutableStateOf(store.getString("serverPort").orEmpty()) }
    var psk by remember { mutableStateOf(store.getString("psk").orEmpty()) }
    var version by remember {
        mutableIntStateOf(store.getString("version")?.toIntOrNull() ?: 4)
    }
    var network by remember { mutableStateOf(store.getString("network").orEmpty()) }
    var obfsMode by remember { mutableStateOf(store.getString("obfsMode").orEmpty()) }
    var obfsHost by remember { mutableStateOf(store.getString("obfsHost").orEmpty()) }
    var userKey by remember { mutableStateOf(store.getString("userKey").orEmpty()) }
    var mode by remember {
        mutableStateOf(store.getString("mode").orEmpty().ifBlank { "default" })
    }
    var reuse by remember { mutableStateOf(store.getBoolean("reuse", false)) }
    var quicProxyMode by remember { mutableStateOf(store.getBoolean("quicProxyMode", false)) }

    val networkLabels = if (version <= 2) networkLabelsAll.take(2) else networkLabelsAll
    val networkValues = if (version <= 2) networkValuesAll.take(2) else networkValuesAll
    val obfsLabels = if (version in 4..5) obfsLabelsAll.take(2) else obfsLabelsAll
    val obfsValues = if (version in 4..5) obfsValuesAll.take(2) else obfsValuesAll

    fun editText(
        title: String,
        value: String,
        keyboardType: KeyboardType = KeyboardType.Text,
        maxLength: Int = Int.MAX_VALUE,
        password: Boolean = false,
        update: (String) -> Unit,
    ) = context.showComposeTextInputDialog(
        title = title,
        initialValue = value,
        keyboardType = keyboardType,
        maxLength = maxLength,
        password = password,
        onPositive = update,
    )

    fun choose(
        title: String,
        labels: List<String>,
        values: List<String>,
        value: String,
        update: (String) -> Unit,
    ) = context.showComposeSingleChoiceDialog(
        title = title,
        items = labels,
        selectedIndex = values.indexOf(value).coerceAtLeast(0),
        negativeButton = cancelLabel,
        onItemSelected = { update(values[it]) },
    )

    fun secretSummary(value: String) = value.takeIf(String::isNotBlank)
        ?.let { "\u2022".repeat(it.length) } ?: notSet

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        item {
            ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name,
                name.ifBlank { notSet }) {
                editText(nameTitle, name) { name = it; store.putString("name", it) }
            }
        }
        item { ProfileCategory(R.string.proxy_cat) }
        item {
            ProfileActionRow(R.drawable.ic_hardware_router, R.string.server_address,
                address.ifBlank { notSet }) {
                editText(addressTitle, address) {
                    address = it
                    store.putString("serverAddress", it)
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.server_port,
                port.ifBlank { notSet }) {
                editText(portTitle, port, KeyboardType.Number, 5) {
                    port = it
                    store.putString("serverPort", it)
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_settings_password, R.string.snell_psk,
                secretSummary(psk)) {
                editText(pskTitle, psk, password = true) {
                    psk = it
                    store.putString("psk", it)
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_legend_toggle_24, R.string.snell_version,
                version.toString()) {
                choose(versionTitle, versions, versions, version.toString()) { selected ->
                    version = selected.toInt()
                    store.putString("version", selected)
                    if (version <= 2 && network == "udp") {
                        network = ""
                        store.putString("network", "")
                    }
                    if (version in 4..5 && obfsMode == "tls") {
                        obfsMode = ""
                        store.putString("obfsMode", "")
                    }
                    if (version == 6 && mode.isBlank()) {
                        mode = "default"
                        store.putString("mode", mode)
                    }
                }
            }
        }
        item {
            val summaryIndex = networkValues.indexOf(network).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_compare_arrows_24, R.string.snell_network,
                networkLabels[summaryIndex]) {
                choose(networkTitle, networkLabels, networkValues, network) {
                    network = it
                    store.putString("network", it)
                }
            }
        }
        if (version != 6) {
            item {
                val summaryIndex = obfsValues.indexOf(obfsMode).coerceAtLeast(0)
                ProfileActionRow(R.drawable.ic_baseline_add_road_24, R.string.snell_obfs_mode,
                    obfsLabels[summaryIndex]) {
                    choose(obfsModeTitle, obfsLabels, obfsValues, obfsMode) {
                        obfsMode = it
                        store.putString("obfsMode", it)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_hardware_router, R.string.snell_obfs_host,
                    obfsHost.ifBlank { notSet }) {
                    editText(obfsHostTitle, obfsHost) {
                        obfsHost = it
                        store.putString("obfsHost", it)
                    }
                }
            }
        }
        if (version >= 4) {
            item {
                ProfileActionRow(R.drawable.ic_settings_password, R.string.snell_user_key,
                    secretSummary(userKey)) {
                    editText(userKeyTitle, userKey, password = true) {
                        userKey = it
                        store.putString("userKey", it)
                    }
                }
            }
        }
        if (version == 6) {
            item {
                val summaryIndex = modeValues.indexOf(mode).coerceAtLeast(0)
                ProfileActionRow(R.drawable.ic_baseline_compare_arrows_24, R.string.snell_v6_mode,
                    modeLabels[summaryIndex]) {
                    choose(modeTitle, modeLabels, modeValues, mode) {
                        mode = it
                        store.putString("mode", it)
                    }
                }
            }
            item {
                ProfileSwitchRow(
                    icon = R.drawable.ic_baseline_speed_24,
                    title = R.string.snell_quic_proxy,
                    checked = quicProxyMode,
                    summary = quicProxySummary,
                    dynamicSummary = false,
                ) {
                    quicProxyMode = it
                    store.putBoolean("quicProxyMode", it)
                }
            }
        }
        item {
            ProfileSwitchRow(
                icon = R.drawable.ic_baseline_refresh_24,
                title = R.string.snell_reuse,
                checked = reuse,
                summary = reuseSummary,
                dynamicSummary = false,
                enabled = version >= 4,
            ) {
                reuse = it
                store.putBoolean("reuse", it)
            }
        }
        item { SharedDialOptions() }
    }
}
