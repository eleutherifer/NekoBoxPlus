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
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean

@Composable
internal fun HysteriaProfileSettingsScreen() {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    val versionLabels = stringArrayResource(R.array.hysteria_version).toList()
    val authLabels = stringArrayResource(R.array.hysteria_auth_type).toList()
    val authValues = stringArrayResource(R.array.int_array_3).toList()
    val protocolLabels = stringArrayResource(R.array.hysteria_protocol).toList()
    val protocolValues = stringArrayResource(R.array.int_array_3).toList()
    val obfsTypes = stringArrayResource(R.array.hysteria2_obfs_types).toList()
    val ipVersionLabels = stringArrayResource(R.array.hysteria2_ip_versions_entry).toList()
    val ipVersionValues = stringArrayResource(R.array.hysteria2_ip_versions_value).toList()
    val profileNameTitle = stringResource(R.string.profile_name)
    val versionTitle = stringResource(R.string.protocol_version)
    val addressTitle = stringResource(R.string.server_address)
    val portsTitle = stringResource(R.string.server_port)
    val obfsTitle = stringResource(R.string.hysteria_obfs)
    val authTypeTitle = stringResource(R.string.hysteria_auth_type)
    val authPayloadTitle = stringResource(R.string.hysteria_auth_payload)
    val passwordTitle = stringResource(R.string.password)
    val protocolTitle = stringResource(R.string.protocol)
    val sniTitle = stringResource(R.string.sni)
    val alpnTitle = stringResource(R.string.alpn)
    val certificatesTitle = stringResource(R.string.certificates)
    val uploadTitle = stringResource(R.string.hysteria_upload_mbps)
    val downloadTitle = stringResource(R.string.hysteria_download_mbps)
    val streamWindowTitle = stringResource(R.string.hysteria_stream_receive_window)
    val connectionWindowTitle = stringResource(R.string.hysteria_connection_receive_window)
    val hopIntervalTitle = stringResource(R.string.hop_interval)
    val hopIntervalMaxTitle = stringResource(R.string.hysteria2_hop_interval_max)
    val bbrProfileTitle = stringResource(R.string.hysteria2_bbr_profile)
    val obfsTypeTitle = stringResource(R.string.hysteria2_obfs_type)
    val geckoMinTitle = stringResource(R.string.hysteria2_gecko_min_packet_size)
    val geckoMaxTitle = stringResource(R.string.hysteria2_gecko_max_packet_size)
    val realmUrlTitle = stringResource(R.string.hysteria2_realm_server_url)
    val realmTokenTitle = stringResource(R.string.hysteria2_realm_token)
    val realmIdTitle = stringResource(R.string.hysteria2_realm_id)
    val realmStunTitle = stringResource(R.string.hysteria2_realm_stun_servers)
    val realmIpVersionTitle = stringResource(R.string.hysteria2_realm_ip_version)
    val mappingTimeoutTitle = stringResource(R.string.hysteria2_realm_port_mapping_timeout)
    val mappingLifetimeTitle = stringResource(R.string.hysteria2_realm_port_mapping_lifetime)

    var profileName by remember { mutableStateOf(DataStore.profileName) }
    var version by remember { mutableIntStateOf(DataStore.protocolVersion) }
    var address by remember { mutableStateOf(DataStore.serverAddress) }
    var ports by remember { mutableStateOf(DataStore.serverPorts) }
    var obfs by remember { mutableStateOf(DataStore.serverObfs) }
    var authType by remember { mutableIntStateOf(DataStore.serverAuthType) }
    var authPayload by remember { mutableStateOf(DataStore.serverPassword) }
    var authPayloadVisible by remember { mutableStateOf(true) }
    var protocol by remember { mutableIntStateOf(DataStore.serverProtocolInt) }
    var sni by remember { mutableStateOf(DataStore.serverSNI) }
    var alpn by remember { mutableStateOf(DataStore.serverALPN) }
    var certificates by remember { mutableStateOf(DataStore.serverCertificates) }
    var allowInsecure by remember { mutableStateOf(DataStore.serverAllowInsecure) }
    var upload by remember { mutableStateOf(DataStore.serverUploadSpeed.toString()) }
    var download by remember { mutableStateOf(DataStore.serverDownloadSpeed.toString()) }
    var streamWindow by remember { mutableStateOf(DataStore.serverStreamReceiveWindow.toString()) }
    var connectionWindow by remember { mutableStateOf(DataStore.serverConnectionReceiveWindow.toString()) }
    var disableMtuDiscovery by remember { mutableStateOf(DataStore.serverDisableMtuDiscovery) }
    var hopInterval by remember { mutableIntStateOf(DataStore.serverHopInterval) }
    var hopIntervalMax by remember { mutableStateOf(store.getString("hysteria2HopIntervalMax").orEmpty()) }
    var bbrProfile by remember { mutableStateOf(store.getString("hysteria2BbrProfile").orEmpty()) }
    var brutalDebug by remember { mutableStateOf(store.getBoolean("hysteria2BrutalDebug", false)) }
    var obfsType by remember { mutableStateOf(store.getString("hysteria2ObfsType") ?: "salamander") }
    var geckoMin by remember { mutableStateOf(store.getString("hysteria2GeckoMinPacketSize").orEmpty()) }
    var geckoMax by remember { mutableStateOf(store.getString("hysteria2GeckoMaxPacketSize").orEmpty()) }
    var realmUrl by remember { mutableStateOf(store.getString("hysteria2RealmServerUrl").orEmpty()) }
    var realmToken by remember { mutableStateOf(store.getString("hysteria2RealmToken").orEmpty()) }
    var realmId by remember { mutableStateOf(store.getString("hysteria2RealmId").orEmpty()) }
    var realmStun by remember { mutableStateOf(store.getString("hysteria2RealmStunServers").orEmpty()) }
    var realmIpVersion by remember { mutableStateOf(store.getString("hysteria2RealmIpVersion") ?: "0") }
    var realmPortMapping by remember { mutableStateOf(store.getBoolean("hysteria2RealmPortMapping", false)) }
    var mappingTimeout by remember { mutableStateOf(store.getString("hysteria2RealmPortMappingTimeout").orEmpty()) }
    var mappingLifetime by remember { mutableStateOf(store.getString("hysteria2RealmPortMappingLifetime").orEmpty()) }

    fun edit(
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

    fun choose(title: String, labels: List<String>, selected: Int, update: (Int) -> Unit) {
        context.showComposeSingleChoiceDialog(
            title = title,
            items = labels,
            selectedIndex = selected.coerceIn(labels.indices),
            negativeButton = cancel,
            onItemSelected = update,
        )
    }

    fun summary(value: String) = value.ifBlank { notSet }
    fun secretSummary(value: String) = value.takeIf(String::isNotBlank)
        ?.let { "\u2022".repeat(it.length) } ?: notSet

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name, summary(profileName)) {
                edit(profileNameTitle, profileName) { profileName = it; DataStore.profileName = it }
            }
        }
        item {
            val selected = versionLabels.indexOf(version.toString()).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_update_24, R.string.protocol_version,
                versionLabels.getOrElse(selected) { version.toString() }) {
                choose(versionTitle, versionLabels, selected) {
                    version = versionLabels[it].toIntOrNull() ?: 1
                    DataStore.protocolVersion = version
                    authPayloadVisible = true
                }
            }
        }
        item { ProfileCategory(R.string.proxy_cat) }
        item {
            ProfileActionRow(R.drawable.ic_hardware_router, R.string.server_address, summary(address)) {
                edit(addressTitle, address) { address = it; DataStore.serverAddress = it }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.server_port, summary(ports)) {
                edit(portsTitle, ports) { ports = it; DataStore.serverPorts = it }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_texture_24, R.string.hysteria_obfs,
                secretSummary(obfs)) {
                edit(obfsTitle, obfs, password = true) { obfs = it; DataStore.serverObfs = it }
            }
        }
        if (version != 2) {
            item {
                val selected = authValues.indexOf(authType.toString()).coerceAtLeast(0)
                ProfileActionRow(R.drawable.ic_baseline_compare_arrows_24,
                    R.string.hysteria_auth_type, authLabels.getOrElse(selected) { authType.toString() }) {
                    choose(authTypeTitle, authLabels, selected) {
                        authType = authValues[it].toIntOrNull() ?: HysteriaBean.TYPE_NONE
                        DataStore.serverAuthType = authType
                        authPayloadVisible = authType != HysteriaBean.TYPE_NONE
                    }
                }
            }
        }
        if (authPayloadVisible) {
            item {
                ProfileActionRow(R.drawable.ic_settings_password,
                    if (version == 2) R.string.password else R.string.hysteria_auth_payload,
                    secretSummary(authPayload)) {
                    edit(if (version == 2) passwordTitle else authPayloadTitle, authPayload,
                        password = true) { authPayload = it; DataStore.serverPassword = it }
                }
            }
        }
        if (version != 2) {
            item {
                val selected = protocolValues.indexOf(protocol.toString()).coerceAtLeast(0)
                ProfileActionRow(R.drawable.ic_baseline_layers_24, R.string.protocol,
                    protocolLabels.getOrElse(selected) { protocol.toString() }) {
                    choose(protocolTitle, protocolLabels, selected) {
                        protocol = protocolValues[it].toIntOrNull() ?: 0
                        DataStore.serverProtocolInt = protocol
                    }
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_action_copyright, R.string.sni, summary(sni)) {
                edit(sniTitle, sni) { sni = it; DataStore.serverSNI = it }
            }
        }
        if (version != 2) {
            item {
                ProfileActionRow(R.drawable.ic_baseline_legend_toggle_24, R.string.alpn, summary(alpn)) {
                    edit(alpnTitle, alpn) { alpn = it; DataStore.serverALPN = it }
                }
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
            ProfileSwitchRow(R.drawable.ic_notification_enhanced_encryption, R.string.allow_insecure,
                allowInsecure, stringResource(R.string.allow_insecure_sum), dynamicSummary = false) {
                allowInsecure = it
                DataStore.serverAllowInsecure = it
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_file_file_upload, R.string.hysteria_upload_mbps,
                summary(upload)) {
                edit(uploadTitle, upload, KeyboardType.Number) {
                    upload = it
                    DataStore.serverUploadSpeed = it.toIntOrNull() ?: 0
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_download_24, R.string.hysteria_download_mbps,
                summary(download)) {
                edit(downloadTitle, download, KeyboardType.Number) {
                    download = it
                    DataStore.serverDownloadSpeed = it.toIntOrNull() ?: 0
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_texture_24,
                R.string.hysteria_stream_receive_window, summary(streamWindow)) {
                edit(streamWindowTitle, streamWindow, KeyboardType.Number) {
                    streamWindow = it
                    DataStore.serverStreamReceiveWindow = it.toIntOrNull() ?: 0
                }
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_transform_24,
                R.string.hysteria_connection_receive_window, summary(connectionWindow)) {
                edit(connectionWindowTitle, connectionWindow, KeyboardType.Number) {
                    connectionWindow = it
                    DataStore.serverConnectionReceiveWindow = it.toIntOrNull() ?: 0
                }
            }
        }
        item {
            ProfileSwitchRow(R.drawable.ic_baseline_multiple_stop_24,
                R.string.hysteria_disable_mtu_discovery, disableMtuDiscovery) {
                disableMtuDiscovery = it
                DataStore.serverDisableMtuDiscovery = it
            }
        }
        item {
            ProfileActionRow(R.drawable.ic_baseline_timelapse_24, R.string.hop_interval,
                summary(hopInterval.toString())) {
                edit(hopIntervalTitle, hopInterval.toString(), KeyboardType.Number) {
                    hopInterval = it.toIntOrNull() ?: 0
                    DataStore.serverHopInterval = hopInterval
                }
            }
        }
        if (version == 2) {
            item { ProfileCategory(R.string.hysteria2_advanced_options) }
            item {
                ProfileActionRow(R.drawable.ic_baseline_timelapse_24,
                    R.string.hysteria2_hop_interval_max, summary(hopIntervalMax)) {
                    edit(hopIntervalMaxTitle, hopIntervalMax) {
                        hopIntervalMax = it; store.putString("hysteria2HopIntervalMax", it)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_legend_toggle_24,
                    R.string.hysteria2_bbr_profile, summary(bbrProfile)) {
                    edit(bbrProfileTitle, bbrProfile) {
                        bbrProfile = it; store.putString("hysteria2BbrProfile", it)
                    }
                }
            }
            item {
                ProfileSwitchRow(R.drawable.ic_baseline_bug_report_24,
                    R.string.hysteria2_brutal_debug, brutalDebug) {
                    brutalDebug = it; store.putBoolean("hysteria2BrutalDebug", it)
                }
            }
            item {
                val selected = obfsTypes.indexOf(obfsType).coerceAtLeast(0)
                ProfileActionRow(R.drawable.ic_baseline_texture_24, R.string.hysteria2_obfs_type,
                    obfsTypes.getOrElse(selected) { obfsType }) {
                    choose(obfsTypeTitle, obfsTypes, selected) {
                        obfsType = obfsTypes[it]; store.putString("hysteria2ObfsType", obfsType)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_transform_24,
                    R.string.hysteria2_gecko_min_packet_size, summary(geckoMin)) {
                    edit(geckoMinTitle, geckoMin, KeyboardType.Number) {
                        geckoMin = it; store.putString("hysteria2GeckoMinPacketSize", it)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_transform_24,
                    R.string.hysteria2_gecko_max_packet_size, summary(geckoMax)) {
                    edit(geckoMaxTitle, geckoMax, KeyboardType.Number) {
                        geckoMax = it; store.putString("hysteria2GeckoMaxPacketSize", it)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_hardware_router,
                    R.string.hysteria2_realm_server_url, summary(realmUrl)) {
                    edit(realmUrlTitle, realmUrl) {
                        realmUrl = it; store.putString("hysteria2RealmServerUrl", it)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_settings_password, R.string.hysteria2_realm_token,
                    secretSummary(realmToken)) {
                    edit(realmTokenTitle, realmToken, password = true) {
                        realmToken = it; store.putString("hysteria2RealmToken", it)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_fingerprint_24,
                    R.string.hysteria2_realm_id, summary(realmId)) {
                    edit(realmIdTitle, realmId) {
                        realmId = it; store.putString("hysteria2RealmId", it)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_dns_24,
                    R.string.hysteria2_realm_stun_servers, summary(realmStun)) {
                    edit(realmStunTitle, realmStun) {
                        realmStun = it; store.putString("hysteria2RealmStunServers", it)
                    }
                }
            }
            item {
                val selected = ipVersionValues.indexOf(realmIpVersion).coerceAtLeast(0)
                ProfileActionRow(R.drawable.ic_baseline_compare_arrows_24,
                    R.string.hysteria2_realm_ip_version,
                    ipVersionLabels.getOrElse(selected) { realmIpVersion }) {
                    choose(realmIpVersionTitle, ipVersionLabels, selected) {
                        realmIpVersion = ipVersionValues[it]
                        store.putString("hysteria2RealmIpVersion", realmIpVersion)
                    }
                }
            }
            item {
                ProfileSwitchRow(R.drawable.ic_baseline_multiple_stop_24,
                    R.string.hysteria2_realm_port_mapping, realmPortMapping) {
                    realmPortMapping = it; store.putBoolean("hysteria2RealmPortMapping", it)
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_timer_24,
                    R.string.hysteria2_realm_port_mapping_timeout, summary(mappingTimeout),
                    enabled = realmPortMapping) {
                    edit(mappingTimeoutTitle, mappingTimeout) {
                        mappingTimeout = it
                        store.putString("hysteria2RealmPortMappingTimeout", it)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_timer_24,
                    R.string.hysteria2_realm_port_mapping_lifetime, summary(mappingLifetime),
                    enabled = realmPortMapping) {
                    edit(mappingLifetimeTitle, mappingLifetime) {
                        mappingLifetime = it
                        store.putString("hysteria2RealmPortMappingLifetime", it)
                    }
                }
            }
        }
        item { SharedDialOptions() }
        item { SharedTlsOptions() }
        item { SharedQuicOptions() }
    }
}
