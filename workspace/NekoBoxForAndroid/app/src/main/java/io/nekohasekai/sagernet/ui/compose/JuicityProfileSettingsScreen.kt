package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

@Composable
internal fun JuicityProfileSettingsScreen() {
    val context = LocalContext.current
    val notSet = stringResource(R.string.not_set)
    val nameTitle = stringResource(R.string.profile_name)
    val addressTitle = stringResource(R.string.server_address)
    val portTitle = stringResource(R.string.server_port)
    val uuidTitle = stringResource(R.string.uuid)
    val passwordTitle = stringResource(R.string.password)
    val sniTitle = stringResource(R.string.sni)
    val pinTitle = stringResource(R.string.pinned_certchain_sha256)
    var name by remember { mutableStateOf(DataStore.profileName) }
    var address by remember { mutableStateOf(DataStore.serverAddress) }
    var port by remember { mutableStateOf(DataStore.serverPort.toString()) }
    var uuid by remember { mutableStateOf(DataStore.serverUserId) }
    var password by remember { mutableStateOf(DataStore.serverPassword) }
    var sni by remember { mutableStateOf(DataStore.serverSNI) }
    var pin by remember { mutableStateOf(DataStore.serverPinnedCertChainSha256) }
    var insecure by remember { mutableStateOf(DataStore.serverAllowInsecure) }

    fun edit(
        title: String,
        value: String,
        keyboardType: KeyboardType = KeyboardType.Text,
        maxLength: Int = Int.MAX_VALUE,
        secret: Boolean = false,
        update: (String) -> Unit,
    ) = context.showComposeTextInputDialog(
        title = title,
        initialValue = value,
        keyboardType = keyboardType,
        maxLength = maxLength,
        password = secret,
        onPositive = update,
    )
    fun summary(value: String) = value.ifBlank { notSet }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item { ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name,
            summary(name)) { edit(nameTitle, name) { name = it; DataStore.profileName = it } } }
        item { ProfileCategory(R.string.proxy_cat) }
        item { ProfileActionRow(R.drawable.ic_hardware_router, R.string.server_address,
            summary(address)) { edit(addressTitle, address) { address = it; DataStore.serverAddress = it } } }
        item { ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.server_port,
            summary(port)) { edit(portTitle, port, KeyboardType.Number, 5) {
            port = it; DataStore.serverPort = it.toIntOrNull() ?: 0
        } } }
        item { ProfileActionRow(R.drawable.ic_baseline_person_24, R.string.uuid, summary(uuid)) {
            edit(uuidTitle, uuid) { uuid = it; DataStore.serverUserId = it }
        } }
        item { ProfileActionRow(R.drawable.ic_settings_password, R.string.password,
            password.takeIf(String::isNotBlank)?.let { "\u2022".repeat(it.length) } ?: notSet) {
            edit(passwordTitle, password, KeyboardType.Password, secret = true) {
                password = it; DataStore.serverPassword = it
            }
        } }
        item { ProfileActionRow(R.drawable.ic_action_copyright, R.string.sni, summary(sni)) {
            edit(sniTitle, sni) { sni = it; DataStore.serverSNI = it }
        } }
        item { ProfileActionRow(R.drawable.ic_baseline_push_pin_24, R.string.pinned_certchain_sha256,
            summary(pin)) { edit(pinTitle, pin) { pin = it; DataStore.serverPinnedCertChainSha256 = it } } }
        item { ProfileSwitchRow(R.drawable.ic_notification_enhanced_encryption,
            R.string.allow_insecure, insecure) { insecure = it; DataStore.serverAllowInsecure = it } }
        item { SharedDialOptions() }
        item { SharedTlsOptions() }
    }
}

@Composable
internal fun SharedTlsOptions() {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val curveTitle = stringResource(R.string.tls_curve_preferences)
    val timeoutTitle = stringResource(R.string.tls_handshake_timeout)
    val publicKeyTitle = stringResource(R.string.tls_certificate_public_key_sha256)
    val xrayTitle = stringResource(R.string.tls_xray_certificate_sha256)
    val certificateTitle = stringResource(R.string.tls_client_certificate)
    val clientKeyTitle = stringResource(R.string.tls_client_key)
    val echTitle = stringResource(R.string.ech_query_server_name)
    var curve by remember { mutableStateOf(store.getString("tlsCurvePreferences").orEmpty()) }
    var timeout by remember { mutableStateOf(store.getString("tlsHandshakeTimeout").orEmpty()) }
    var publicKey by remember { mutableStateOf(store.getString("tlsCertificatePublicKeySha256").orEmpty()) }
    var xray by remember { mutableStateOf(store.getString("tlsXrayCertificateSha256").orEmpty()) }
    var certificate by remember { mutableStateOf(store.getString("tlsClientCertificate").orEmpty()) }
    var clientKey by remember { mutableStateOf(store.getString("tlsClientKey").orEmpty()) }
    var ech by remember { mutableStateOf(store.getString("echQueryServerName").orEmpty()) }
    fun edit(title: String, value: String, secret: Boolean = false, update: (String) -> Unit) =
        context.showComposeTextInputDialog(title, value, password = secret, onPositive = update)
    fun summary(value: String, secret: Boolean = false) = when {
        value.isBlank() -> notSet
        secret -> "\u2022".repeat(value.length)
        else -> value
    }

    Column {
        ProfileCategory(R.string.sing_box_tls_13_options)
        ProfileActionRow(R.drawable.ic_baseline_multiple_stop_24, R.string.tls_curve_preferences,
            summary(curve)) { edit(curveTitle, curve) { curve = it; store.putString("tlsCurvePreferences", it) } }
        ProfileActionRow(R.drawable.ic_baseline_timer_24, R.string.tls_handshake_timeout,
            summary(timeout)) { edit(timeoutTitle, timeout) { timeout = it; store.putString("tlsHandshakeTimeout", it) } }
        ProfileActionRow(R.drawable.ic_baseline_fingerprint_24, R.string.tls_certificate_public_key_sha256,
            summary(publicKey)) { edit(publicKeyTitle, publicKey) { publicKey = it; store.putString("tlsCertificatePublicKeySha256", it) } }
        ProfileActionRow(R.drawable.ic_baseline_fingerprint_24, R.string.tls_xray_certificate_sha256,
            summary(xray)) { edit(xrayTitle, xray) { xray = it; store.putString("tlsXrayCertificateSha256", it) } }
        ProfileActionRow(R.drawable.ic_action_copyright, R.string.tls_client_certificate,
            summary(certificate)) { edit(certificateTitle, certificate) { certificate = it; store.putString("tlsClientCertificate", it) } }
        ProfileActionRow(R.drawable.ic_baseline_vpn_key_24, R.string.tls_client_key,
            summary(clientKey, true)) { edit(clientKeyTitle, clientKey, true) { clientKey = it; store.putString("tlsClientKey", it) } }
        ProfileActionRow(R.drawable.ic_baseline_dns_24, R.string.ech_query_server_name,
            summary(ech)) { edit(echTitle, ech) { ech = it; store.putString("echQueryServerName", it) } }
    }
}

@Composable
internal fun SharedQuicOptions() {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val idleTitle = stringResource(R.string.quic_idle_timeout)
    val keepAliveTitle = stringResource(R.string.quic_keep_alive_period)
    val streamWindowTitle = stringResource(R.string.quic_stream_receive_window)
    val connectionWindowTitle = stringResource(R.string.quic_connection_receive_window)
    val streamsTitle = stringResource(R.string.quic_max_concurrent_streams)
    val packetSizeTitle = stringResource(R.string.quic_initial_packet_size)
    var idle by remember { mutableStateOf(store.getString("quicIdleTimeout").orEmpty()) }
    var keepAlive by remember { mutableStateOf(store.getString("quicKeepAlivePeriod").orEmpty()) }
    var streamWindow by remember { mutableStateOf(store.getString("quicStreamReceiveWindow").orEmpty()) }
    var connectionWindow by remember {
        mutableStateOf(store.getString("quicConnectionReceiveWindow").orEmpty())
    }
    var streams by remember { mutableStateOf(store.getString("quicMaxConcurrentStreams").orEmpty()) }
    var packetSize by remember { mutableStateOf(store.getString("quicInitialPacketSize").orEmpty()) }
    var disablePathMtu by remember {
        mutableStateOf(store.getBoolean("quicDisablePathMtuDiscovery", false))
    }
    fun edit(title: String, value: String, number: Boolean = false, update: (String) -> Unit) =
        context.showComposeTextInputDialog(
            title = title,
            initialValue = value,
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
            onPositive = update,
        )
    fun summary(value: String) = value.ifBlank { notSet }

    Column {
        ProfileCategory(R.string.sing_box_quic_options)
        ProfileActionRow(R.drawable.ic_baseline_timer_24, R.string.quic_idle_timeout,
            summary(idle)) { edit(idleTitle, idle) { idle = it; store.putString("quicIdleTimeout", it) } }
        ProfileActionRow(R.drawable.ic_baseline_timer_24, R.string.quic_keep_alive_period,
            summary(keepAlive)) { edit(keepAliveTitle, keepAlive) {
            keepAlive = it; store.putString("quicKeepAlivePeriod", it)
        } }
        ProfileActionRow(R.drawable.ic_baseline_timer_24, R.string.quic_stream_receive_window,
            summary(streamWindow)) { edit(streamWindowTitle, streamWindow, true) {
            streamWindow = it; store.putString("quicStreamReceiveWindow", it)
        } }
        ProfileActionRow(R.drawable.ic_baseline_timer_24, R.string.quic_connection_receive_window,
            summary(connectionWindow)) { edit(connectionWindowTitle, connectionWindow, true) {
            connectionWindow = it; store.putString("quicConnectionReceiveWindow", it)
        } }
        ProfileActionRow(R.drawable.ic_baseline_timer_24, R.string.quic_max_concurrent_streams,
            summary(streams)) { edit(streamsTitle, streams, true) {
            streams = it; store.putString("quicMaxConcurrentStreams", it)
        } }
        ProfileActionRow(R.drawable.ic_baseline_timer_24, R.string.quic_initial_packet_size,
            summary(packetSize)) { edit(packetSizeTitle, packetSize, true) {
            packetSize = it; store.putString("quicInitialPacketSize", it)
        } }
        ProfileSwitchRow(R.drawable.ic_baseline_multiple_stop_24,
            R.string.quic_disable_path_mtu_discovery, disablePathMtu) {
            disablePathMtu = it
            store.putBoolean("quicDisablePathMtuDiscovery", it)
        }
    }
}
