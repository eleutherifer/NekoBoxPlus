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
internal fun TuicProfileSettingsScreen() {
    val context = LocalContext.current
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    val relayLabels = stringArrayResource(R.array.tuic_udp_relay_mode_entry).toList()
    val relayValues = stringArrayResource(R.array.tuic_udp_relay_mode_value).toList()
    val congestionLabels = stringArrayResource(R.array.tuic_congestion_controller_entry).toList()
    val congestionValues = stringArrayResource(R.array.tuic_congestion_controller_value).toList()
    val nameTitle = stringResource(R.string.profile_name)
    val addressTitle = stringResource(R.string.server_address)
    val portTitle = stringResource(R.string.server_port)
    val uuidTitle = stringResource(R.string.uuid)
    val passwordTitle = stringResource(R.string.password)
    val alpnTitle = stringResource(R.string.alpn)
    val certificatesTitle = stringResource(R.string.certificates)
    val relayTitle = stringResource(R.string.tuic_udp_relay_mode)
    val congestionTitle = stringResource(R.string.tuic_congestion_controller)
    val sniTitle = stringResource(R.string.sni)
    var name by remember { mutableStateOf(DataStore.profileName) }
    var address by remember { mutableStateOf(DataStore.serverAddress) }
    var port by remember { mutableStateOf(DataStore.serverPort.toString()) }
    var uuid by remember { mutableStateOf(DataStore.serverUsername) }
    var password by remember { mutableStateOf(DataStore.serverPassword) }
    var alpn by remember { mutableStateOf(DataStore.serverALPN) }
    var certificates by remember { mutableStateOf(DataStore.serverCertificates) }
    var relay by remember { mutableStateOf(DataStore.serverUDPRelayMode) }
    var congestion by remember { mutableStateOf(DataStore.serverCongestionController) }
    var disableSni by remember { mutableStateOf(DataStore.serverDisableSNI) }
    var sni by remember { mutableStateOf(DataStore.serverSNI) }
    var reduceRtt by remember { mutableStateOf(DataStore.serverReduceRTT) }
    var insecure by remember { mutableStateOf(DataStore.serverAllowInsecure) }

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
    fun choose(title: String, labels: List<String>, selected: Int, update: (Int) -> Unit) =
        context.showComposeSingleChoiceDialog(
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
            ProfileActionRow(R.drawable.ic_baseline_person_24, R.string.uuid, summary(uuid)) {
                edit(uuidTitle, uuid) { uuid = it; DataStore.serverUsername = it }
            }
        }
        item {
            val passwordSummary = password.takeIf(String::isNotBlank)
                ?.let { "\u2022".repeat(it.length) } ?: notSet
            ProfileActionRow(R.drawable.ic_settings_password, R.string.password, passwordSummary) {
                edit(passwordTitle, password, KeyboardType.Password, secret = true) {
                    password = it
                    DataStore.serverPassword = it
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_legend_toggle_24, R.string.alpn, summary(alpn)) {
                edit(alpnTitle, alpn) { alpn = it; DataStore.serverALPN = it }
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
            val selected = relayValues.indexOf(relay).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_add_road_24, R.string.tuic_udp_relay_mode,
                relayLabels[selected]) {
                choose(relayTitle, relayLabels, selected) {
                    relay = relayValues[it]
                    DataStore.serverUDPRelayMode = relay
                }
            }
        }
        item {
            val selected = congestionValues.indexOf(congestion).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_compare_arrows_24,
                R.string.tuic_congestion_controller, congestionLabels[selected]) {
                choose(congestionTitle, congestionLabels, selected) {
                    congestion = congestionValues[it]
                    DataStore.serverCongestionController = congestion
                }
            }
        }
        item {
            ProfileSwitchRow(R.drawable.ic_baseline_fingerprint_24, R.string.tuic_disable_sni,
                disableSni) {
                disableSni = it
                DataStore.serverDisableSNI = it
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_action_copyright, R.string.sni, summary(sni),
                enabled = !disableSni) {
                edit(sniTitle, sni) { sni = it; DataStore.serverSNI = it }
            }
        }
        item {
            ProfileSwitchRow(R.drawable.baseline_flight_takeoff_24, R.string.tuic_reduce_rtt,
                reduceRtt) {
                reduceRtt = it
                DataStore.serverReduceRTT = it
            }
        }
        item {
            ProfileSwitchRow(R.drawable.ic_notification_enhanced_encryption,
                R.string.allow_insecure, insecure) {
                insecure = it
                DataStore.serverAllowInsecure = it
            }
        }
        item { SharedDialOptions() }
        item { SharedTlsOptions() }
        item { SharedQuicOptions() }
    }
}
