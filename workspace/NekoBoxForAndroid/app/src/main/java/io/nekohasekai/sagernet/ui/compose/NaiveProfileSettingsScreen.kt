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
internal fun NaiveProfileSettingsScreen() {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    val protocolLabels = stringArrayResource(R.array.naive_proto_entry).toList()
    val protocolValues = stringArrayResource(R.array.naive_proto_value).toList()
    val congestionLabels = stringArrayResource(R.array.naive_quic_congestion_control_entry).toList()
    val congestionValues = stringArrayResource(R.array.naive_quic_congestion_control_value).toList()
    val nameTitle = stringResource(R.string.profile_name)
    val addressTitle = stringResource(R.string.server_address)
    val portTitle = stringResource(R.string.server_port)
    val usernameTitle = stringResource(R.string.username_opt)
    val passwordTitle = stringResource(R.string.password_opt)
    val protocolTitle = stringResource(R.string.protocol)
    val congestionTitle = stringResource(R.string.naive_quic_congestion_control)
    val headersTitle = stringResource(R.string.extra_headers)
    val streamWindowTitle = stringResource(R.string.naive_stream_receive_window)
    val sessionWindowTitle = stringResource(R.string.naive_quic_session_receive_window)
    val sniTitle = stringResource(R.string.sni)
    val certificatesTitle = stringResource(R.string.certificates)
    val concurrencyTitle = stringResource(R.string.naive_insecure_concurrency)
    val concurrencyMessage = stringResource(R.string.naive_insecure_concurrency_summary)
    var name by remember { mutableStateOf(DataStore.profileName) }
    var address by remember { mutableStateOf(DataStore.serverAddress) }
    var port by remember { mutableStateOf(DataStore.serverPort.toString()) }
    var username by remember { mutableStateOf(DataStore.serverUsername) }
    var password by remember { mutableStateOf(DataStore.serverPassword) }
    var protocol by remember { mutableStateOf(DataStore.serverProtocol) }
    var congestion by remember { mutableStateOf(store.getString("quicCongestionControl").orEmpty()) }
    var headers by remember { mutableStateOf(DataStore.serverHeaders) }
    var streamWindow by remember { mutableStateOf(store.getString("streamReceiveWindow").orEmpty()) }
    var sessionWindow by remember {
        mutableStateOf(store.getString("quicSessionReceiveWindow").orEmpty())
    }
    var sni by remember { mutableStateOf(DataStore.serverSNI) }
    var certificates by remember { mutableStateOf(DataStore.serverCertificates) }
    var concurrency by remember { mutableIntStateOf(DataStore.serverInsecureConcurrency) }
    var udpOverTcp by remember { mutableStateOf(store.getBoolean("sUoT", false)) }

    fun edit(
        title: String,
        value: String,
        keyboard: KeyboardType = KeyboardType.Text,
        maxLength: Int = Int.MAX_VALUE,
        secret: Boolean = false,
        supportingText: String? = null,
        update: (String) -> Unit,
    ) = context.showComposeTextInputDialog(
        title = title,
        initialValue = value,
        keyboardType = keyboard,
        maxLength = maxLength,
        password = secret,
        supportingText = supportingText,
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
            ProfileActionRow(R.drawable.ic_baseline_person_24, R.string.username_opt, summary(username)) {
                edit(usernameTitle, username) { username = it; DataStore.serverUsername = it }
            }
        }
        item {
            val passwordSummary = password.takeIf(String::isNotBlank)
                ?.let { "\u2022".repeat(it.length) } ?: notSet
            ProfileActionRow(R.drawable.ic_settings_password, R.string.password_opt, passwordSummary) {
                edit(passwordTitle, password, KeyboardType.Password, secret = true) {
                    password = it
                    DataStore.serverPassword = it
                }
            }
        }
        item {
            val selected = protocolValues.indexOf(protocol).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_layers_24, R.string.protocol,
                protocolLabels[selected]) {
                choose(protocolTitle, protocolLabels, selected) {
                    protocol = protocolValues[it]
                    DataStore.serverProtocol = protocol
                }
            }
        }
        item {
            val selected = congestionValues.indexOf(congestion).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_speed_24,
                R.string.naive_quic_congestion_control, congestionLabels[selected]) {
                choose(congestionTitle, congestionLabels, selected) {
                    congestion = congestionValues[it]
                    store.putString("quicCongestionControl", congestion)
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_texture_24, R.string.extra_headers,
                summary(headers)) {
                edit(headersTitle, headers) { headers = it; DataStore.serverHeaders = it }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_grid_3x3_24,
                R.string.naive_stream_receive_window, summary(streamWindow)) {
                edit(streamWindowTitle, streamWindow) {
                    streamWindow = it
                    store.putString("streamReceiveWindow", it)
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_grid_3x3_24,
                R.string.naive_quic_session_receive_window, summary(sessionWindow)) {
                edit(sessionWindowTitle, sessionWindow) {
                    sessionWindow = it
                    store.putString("quicSessionReceiveWindow", it)
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_action_copyright, R.string.sni, summary(sni)) {
                edit(sniTitle, sni) { sni = it; DataStore.serverSNI = it }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_vpn_key_24, R.string.certificates,
                summary(certificates)) {
                edit(certificatesTitle, certificates) {
                    certificates = it
                    DataStore.serverCertificates = it
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_warning_24,
                R.string.naive_insecure_concurrency, concurrency.toString()) {
                edit(
                    concurrencyTitle,
                    concurrency.toString(),
                    KeyboardType.Number,
                    supportingText = concurrencyMessage,
                ) {
                    concurrency = it.toIntOrNull() ?: 0
                    DataStore.serverInsecureConcurrency = concurrency
                }
            }
        }
        item { ProfileCategory(R.string.sing_box_server) }
        item {
            ProfileSwitchRow(R.drawable.ic_baseline_compare_arrows_24, R.string.udp_over_tcp,
                udpOverTcp) {
                udpOverTcp = it
                store.putBoolean("sUoT", it)
            }
        }
        item { SharedDialOptions() }
        item { SharedTlsOptions() }
    }
}
