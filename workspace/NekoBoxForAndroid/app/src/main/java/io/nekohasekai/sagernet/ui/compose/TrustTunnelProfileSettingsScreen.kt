package io.nekohasekai.sagernet.ui.compose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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

private const val TRUST_TUNNEL_PROTOCOL = "trustTunnelProtocol"
private const val TRUST_TUNNEL_CRONET_STACK = "trustTunnelCronetStack"
private const val TRUST_TUNNEL_HTTPS = "https"
private const val TRUST_TUNNEL_FORCE_QUIC = "force_quic"
private const val TRUST_TUNNEL_NO_CRONET = "no"

@Composable
internal fun TrustTunnelProfileSettingsScreen() {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    val protocolLabels = stringArrayResource(R.array.trusttunnel_protocol_entry).toList()
    val protocolValues = stringArrayResource(R.array.trusttunnel_protocol_value).toList()
    val cronetLabels = stringArrayResource(R.array.trusttunnel_cronet_stack_entry).toList()
    val cronetValues = stringArrayResource(R.array.trusttunnel_cronet_stack_value).toList()
    val congestionLabels =
        stringArrayResource(R.array.trusttunnel_quic_congestion_controller_entry).toList()
    val congestionValues =
        stringArrayResource(R.array.trusttunnel_quic_congestion_controller_value).toList()
    val utlsLabels = stringArrayResource(R.array.utls_fingerprint_entry).toList()
    val utlsValues = stringArrayResource(R.array.utls_fingerprint_value).toList()
    val protocolTitle = stringResource(R.string.trusttunnel_protocol)
    val cronetTitle = stringResource(R.string.trusttunnel_use_chrome_network_stack)
    val congestionTitle = stringResource(R.string.tuic_congestion_controller)
    val utlsTitle = stringResource(R.string.utls_fingerprint)

    var name by remember { mutableStateOf(store.getString("name").orEmpty()) }
    var address by remember { mutableStateOf(store.getString("serverAddress").orEmpty()) }
    var port by remember { mutableStateOf(store.getString("serverPort").orEmpty()) }
    var username by remember { mutableStateOf(store.getString("username").orEmpty()) }
    var password by remember { mutableStateOf(store.getString("password").orEmpty()) }
    var healthCheck by remember { mutableStateOf(store.getBoolean("healthCheck", false)) }
    var protocol by remember {
        mutableStateOf(store.getString(TRUST_TUNNEL_PROTOCOL) ?: TRUST_TUNNEL_HTTPS)
    }
    var cronetStack by remember {
        mutableStateOf(store.getString(TRUST_TUNNEL_CRONET_STACK) ?: TRUST_TUNNEL_NO_CRONET)
    }
    var congestion by remember {
        mutableStateOf(store.getString("quicCongestionControl").orEmpty())
    }
    var clientRandomPrefix by remember {
        mutableStateOf(store.getString("clientRandomPrefix").orEmpty())
    }
    var serverName by remember { mutableStateOf(store.getString("serverName").orEmpty()) }
    var allowInsecure by remember { mutableStateOf(store.getBoolean("allowInsecure", false)) }
    var alpn by remember { mutableStateOf(store.getString("alpn").orEmpty()) }
    var certificates by remember { mutableStateOf(store.getString("certificates").orEmpty()) }
    var certPublicKey by remember {
        mutableStateOf(store.getString("certPublicKeySha256").orEmpty())
    }
    var utls by remember { mutableStateOf(store.getString("utlsFingerprint").orEmpty()) }
    var tlsFragment by remember { mutableStateOf(store.getBoolean("tlsFragment", false)) }
    var fragmentDelay by remember {
        mutableStateOf(store.getString("tlsFragmentFallbackDelay").orEmpty())
    }
    var recordFragment by remember {
        mutableStateOf(store.getBoolean("tlsRecordFragment", false))
    }
    var ech by remember { mutableStateOf(store.getBoolean("ech", false)) }
    var echConfig by remember { mutableStateOf(store.getString("echConfig").orEmpty()) }
    var echQueryServerName by remember {
        mutableStateOf(store.getString("echQueryServerName").orEmpty())
    }
    var clientCert by remember { mutableStateOf(store.getString("clientCert").orEmpty()) }
    var clientKey by remember { mutableStateOf(store.getString("clientKey").orEmpty()) }

    val forceQuic = protocol == TRUST_TUNNEL_FORCE_QUIC
    val cronetSelected = cronetStack != TRUST_TUNNEL_NO_CRONET
    val nativeTlsEnabled = !forceQuic && !cronetSelected

    fun summary(value: String) = value.ifBlank { notSet }
    fun secretSummary(value: String) = value.takeIf(String::isNotBlank)
        ?.let { "\u2022".repeat(it.length) } ?: notSet
    fun edit(
        title: CharSequence,
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
    fun choose(
        title: CharSequence,
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

    @Composable
    fun textRow(
        @DrawableRes icon: Int,
        @StringRes title: Int,
        value: String,
        key: String,
        enabled: Boolean = true,
        secret: Boolean = false,
        keyboardType: KeyboardType = KeyboardType.Text,
        maxLength: Int = Int.MAX_VALUE,
        updateState: (String) -> Unit,
    ) {
        val titleText = stringResource(title)
        ProfileActionRow(
            icon = icon,
            title = title,
            summary = if (secret) secretSummary(value) else summary(value),
            enabled = enabled,
        ) {
            edit(titleText, value, keyboardType, maxLength, secret) {
                updateState(it)
                store.putString(key, it)
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            textRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name, name, "name") {
                name = it
            }
        }
        item { ProfileCategory(R.string.proxy_cat) }
        item {
            textRow(R.drawable.ic_hardware_router, R.string.server_address, address, "serverAddress") {
                address = it
            }
        }
        item {
            textRow(
                R.drawable.ic_maps_directions_boat,
                R.string.server_port,
                port,
                "serverPort",
                keyboardType = KeyboardType.Number,
                maxLength = 5,
            ) { port = it }
        }
        item {
            textRow(R.drawable.ic_baseline_person_24, R.string.username, username, "username") {
                username = it
            }
        }
        item {
            textRow(
                R.drawable.ic_settings_password,
                R.string.password,
                password,
                "password",
                secret = true,
            ) { password = it }
        }
        item {
            ProfileSwitchRow(R.drawable.ic_baseline_speed_24, R.string.health_check, healthCheck) {
                healthCheck = it
                store.putBoolean("healthCheck", it)
            }
        }
        item {
            val selected = protocolValues.indexOf(protocol).coerceAtLeast(0)
            ProfileActionRow(
                R.drawable.ic_baseline_fast_forward_24,
                R.string.trusttunnel_protocol,
                protocolLabels.getOrElse(selected) { protocol },
            ) {
                choose(protocolTitle, protocolLabels, selected) {
                    protocol = protocolValues[it]
                    store.putString(TRUST_TUNNEL_PROTOCOL, protocol)
                }
            }
        }
        item {
            val selected = cronetValues.indexOf(cronetStack).coerceAtLeast(0)
            ProfileActionRow(
                R.drawable.ic_baseline_fast_forward_24,
                R.string.trusttunnel_use_chrome_network_stack,
                cronetLabels.getOrElse(selected) { cronetStack },
            ) {
                choose(
                    cronetTitle,
                    cronetLabels,
                    selected,
                ) {
                    cronetStack = cronetValues[it]
                    store.putString(TRUST_TUNNEL_CRONET_STACK, cronetStack)
                }
            }
        }
        item {
            val selected = congestionValues.indexOf(congestion).coerceAtLeast(0)
            ProfileActionRow(
                R.drawable.ic_baseline_compare_arrows_24,
                R.string.tuic_congestion_controller,
                congestionLabels.getOrElse(selected) { summary(congestion) },
                enabled = protocol != TRUST_TUNNEL_HTTPS,
            ) {
                choose(
                    congestionTitle,
                    congestionLabels,
                    selected,
                ) {
                    congestion = congestionValues[it]
                    store.putString("quicCongestionControl", congestion)
                }
            }
        }
        item {
            textRow(
                R.drawable.ic_baseline_shuffle_24,
                R.string.client_random_prefix,
                clientRandomPrefix,
                "clientRandomPrefix",
                enabled = !cronetSelected,
            ) { clientRandomPrefix = it }
        }
        item { ProfileCategory(R.string.security_settings) }
        item {
            textRow(R.drawable.ic_action_copyright, R.string.sni, serverName, "serverName") {
                serverName = it
            }
        }
        item {
            ProfileSwitchRow(
                R.drawable.ic_action_lock_open,
                R.string.allow_insecure,
                allowInsecure,
                enabled = nativeTlsEnabled,
            ) {
                allowInsecure = it
                store.putBoolean("allowInsecure", it)
            }
        }
        item {
            textRow(
                R.drawable.ic_baseline_legend_toggle_24,
                R.string.alpn,
                alpn,
                "alpn",
                enabled = !forceQuic,
            ) { alpn = it }
        }
        item {
            textRow(
                R.drawable.ic_baseline_vpn_key_24,
                R.string.certificates,
                certificates,
                "certificates",
            ) { certificates = it }
        }
        item {
            textRow(
                R.drawable.ic_baseline_fingerprint_24,
                R.string.cert_public_key_sha256,
                certPublicKey,
                "certPublicKeySha256",
            ) { certPublicKey = it }
        }
        item {
            val selected = utlsValues.indexOf(utls).coerceAtLeast(0)
            ProfileActionRow(
                R.drawable.ic_baseline_fingerprint_24,
                R.string.utls_fingerprint,
                utlsLabels.getOrElse(selected) { summary(utls) },
                enabled = nativeTlsEnabled,
            ) {
                choose(utlsTitle, utlsLabels, selected) {
                    utls = utlsValues[it]
                    store.putString("utlsFingerprint", utls)
                    if (utls == "cronet") {
                        cronetStack = TRUST_TUNNEL_HTTPS
                        store.putString(TRUST_TUNNEL_CRONET_STACK, cronetStack)
                    }
                }
            }
        }
        item {
            ProfileSwitchRow(
                R.drawable.ic_baseline_texture_24,
                R.string.tls_fragment,
                tlsFragment,
                enabled = nativeTlsEnabled && !recordFragment,
            ) {
                tlsFragment = it
                store.putBoolean("tlsFragment", it)
            }
        }
        item {
            textRow(
                R.drawable.ic_baseline_timelapse_24,
                R.string.tls_fragment_fallback_delay,
                fragmentDelay,
                "tlsFragmentFallbackDelay",
                enabled = nativeTlsEnabled && tlsFragment,
            ) { fragmentDelay = it }
        }
        item {
            ProfileSwitchRow(
                R.drawable.ic_baseline_fingerprint_24,
                R.string.tls_record_fragment,
                recordFragment,
                enabled = nativeTlsEnabled && !tlsFragment,
            ) {
                recordFragment = it
                store.putBoolean("tlsRecordFragment", it)
            }
        }
        item { ProfileCategory(R.string.ech) }
        item {
            ProfileSwitchRow(R.drawable.ic_action_lock, R.string.ech, ech) {
                ech = it
                store.putBoolean("ech", it)
            }
        }
        item {
            textRow(
                R.drawable.ic_baseline_format_align_left_24,
                R.string.ech_config,
                echConfig,
                "echConfig",
                enabled = ech,
            ) { echConfig = it }
        }
        item {
            textRow(
                R.drawable.ic_baseline_center_focus_weak_24,
                R.string.ech_query_server_name,
                echQueryServerName,
                "echQueryServerName",
                enabled = ech,
            ) { echQueryServerName = it }
        }
        item { ProfileCategory(R.string.mutual_tls) }
        item {
            textRow(
                R.drawable.ic_action_lock,
                R.string.certificates,
                clientCert,
                "clientCert",
            ) { clientCert = it }
        }
        item {
            textRow(
                R.drawable.ic_baseline_vpn_key_24,
                R.string.ssh_private_key,
                clientKey,
                "clientKey",
            ) { clientKey = it }
        }
        item { SharedDialOptions() }
        item { SharedTlsOptions() }
    }
}
