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
internal fun MieruProfileSettingsScreen() {
    val context = LocalContext.current
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    val protocolLabels = stringArrayResource(R.array.mieru_protocol).toList()
    val muxLabels = stringArrayResource(R.array.mieru_mux_level_entry).toList()
    val muxValues = stringArrayResource(R.array.mieru_mux_level_value).map(String::toInt)
    val handshakeLabels = stringArrayResource(R.array.mieru_handshake_mode_entry).toList()
    val handshakeValues = stringArrayResource(R.array.mieru_handshake_mode_value).map(String::toInt)
    val lowEntropyModes = stringArrayResource(R.array.mieru_low_entropy_mode).toList()
    val lowEntropyMaskRotations = stringArrayResource(R.array.mieru_low_entropy_mask_rotation).toList()
    val nameTitle = stringResource(R.string.profile_name)
    val addressTitle = stringResource(R.string.server_address)
    val portTitle = stringResource(R.string.server_port)
    val rangeTitle = stringResource(R.string.mieru_port_range)
    val protocolTitle = stringResource(R.string.protocol)
    val usernameTitle = stringResource(R.string.username)
    val passwordTitle = stringResource(R.string.password)
    val muxTitle = stringResource(R.string.mieru_multiplexing_level)
    val handshakeTitle = stringResource(R.string.mieru_handshake_mode)
    val patternTitle = stringResource(R.string.mieru_traffic_pattern)
    val lowEntropyModeTitle = stringResource(R.string.mieru_low_entropy_mode)
    val lowEntropyMaskRotationTitle = stringResource(R.string.mieru_low_entropy_mask_rotation)
    var name by remember { mutableStateOf(DataStore.profileName) }
    var address by remember { mutableStateOf(DataStore.serverAddress) }
    var port by remember { mutableStateOf(DataStore.serverPort.toString()) }
    var portRange by remember { mutableStateOf(DataStore.serverPorts) }
    var protocol by remember { mutableIntStateOf(DataStore.serverProtocolInt) }
    var username by remember { mutableStateOf(DataStore.serverUsername) }
    var password by remember { mutableStateOf(DataStore.serverPassword) }
    var mux by remember { mutableIntStateOf(DataStore.serverMieruMuxLevel) }
    var handshake by remember { mutableIntStateOf(DataStore.serverMieruHandshakeMode) }
    var pattern by remember { mutableStateOf(DataStore.serverMieruTrafficPattern) }
    var lowEntropyMode by remember { mutableStateOf(DataStore.serverMieruLowEntropyMode) }
    var lowEntropyMaskRotation by remember { mutableStateOf(DataStore.serverMieruLowEntropyMaskRotation) }

    fun edit(
        title: String,
        value: String,
        keyboard: KeyboardType = KeyboardType.Text,
        maxLength: Int = Int.MAX_VALUE,
        secret: Boolean = false,
        update: (String) -> Unit,
    ) = context.showComposeTextInputDialog(
        title = title, initialValue = value, keyboardType = keyboard,
        maxLength = maxLength, password = secret, onPositive = update,
    )
    fun choose(title: String, labels: List<String>, selected: Int, update: (Int) -> Unit) =
        context.showComposeSingleChoiceDialog(
            title = title, items = labels, selectedIndex = selected.coerceIn(labels.indices),
            negativeButton = cancel, onItemSelected = update,
        )
    fun summary(value: String) = value.ifBlank { notSet }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item { ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name,
            summary(name)) { edit(nameTitle, name) { name = it; DataStore.profileName = it } } }
        item { ProfileCategory(R.string.proxy_cat) }
        item { ProfileActionRow(R.drawable.ic_hardware_router, R.string.server_address,
            summary(address)) { edit(addressTitle, address) { address = it; DataStore.serverAddress = it } } }
        item { ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.server_port,
            summary(port), enabled = portRange.isBlank()) {
            edit(portTitle, port, KeyboardType.Number, 5) { port = it; DataStore.serverPort = it.toIntOrNull() ?: 0 }
        } }
        item { ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.mieru_port_range,
            summary(portRange)) { edit(rangeTitle, portRange) { portRange = it; DataStore.serverPorts = it } } }
        item { ProfileActionRow(R.drawable.ic_baseline_compare_arrows_24, R.string.protocol,
            protocolLabels.getOrElse(protocol) { protocol.toString() }) {
            choose(protocolTitle, protocolLabels, protocol) { protocol = it; DataStore.serverProtocolInt = it }
        } }
        item { ProfileActionRow(R.drawable.ic_baseline_person_24, R.string.username,
            summary(username)) { edit(usernameTitle, username) { username = it; DataStore.serverUsername = it } } }
        item { ProfileActionRow(R.drawable.ic_settings_password, R.string.password,
            password.takeIf(String::isNotBlank)?.let { "\u2022".repeat(it.length) } ?: notSet) {
            edit(passwordTitle, password, KeyboardType.Password, secret = true) {
                password = it; DataStore.serverPassword = it
            }
        } }
        item {
            val selected = muxValues.indexOf(mux).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_compare_arrows_24,
                R.string.mieru_multiplexing_level, muxLabels[selected]) {
                choose(muxTitle, muxLabels, selected) { mux = muxValues[it]; DataStore.serverMieruMuxLevel = mux }
            }
        }
        item {
            val selected = handshakeValues.indexOf(handshake).coerceAtLeast(0)
            ProfileActionRow(R.drawable.baseline_flight_takeoff_24,
                R.string.mieru_handshake_mode, handshakeLabels[selected]) {
                choose(handshakeTitle, handshakeLabels, selected) {
                    handshake = handshakeValues[it]; DataStore.serverMieruHandshakeMode = handshake
                }
            }
        }
        item { ProfileActionRow(R.drawable.ic_baseline_fingerprint_24, R.string.mieru_traffic_pattern,
            summary(pattern)) { edit(patternTitle, pattern) { pattern = it; DataStore.serverMieruTrafficPattern = it } } }
        item {
            val selected = lowEntropyModes.indexOf(lowEntropyMode).coerceAtLeast(0)
            val labels = lowEntropyModes.map(::summary)
            ProfileActionRow(R.drawable.ic_baseline_fingerprint_24, R.string.mieru_low_entropy_mode,
                labels[selected]) {
                choose(lowEntropyModeTitle, labels, selected) {
                    lowEntropyMode = lowEntropyModes[it]
                    DataStore.serverMieruLowEntropyMode = lowEntropyMode
                }
            }
        }
        item {
            val selected = lowEntropyMaskRotations.indexOf(lowEntropyMaskRotation).coerceAtLeast(0)
            val labels = lowEntropyMaskRotations.map(::summary)
            ProfileActionRow(R.drawable.ic_baseline_fingerprint_24,
                R.string.mieru_low_entropy_mask_rotation, labels[selected]) {
                choose(lowEntropyMaskRotationTitle, labels, selected) {
                    lowEntropyMaskRotation = lowEntropyMaskRotations[it]
                    DataStore.serverMieruLowEntropyMaskRotation = lowEntropyMaskRotation
                }
            }
        }
        item { SharedDialOptions() }
    }
}
