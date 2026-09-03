package io.nekohasekai.sagernet.ui.compose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

@Composable
internal fun MasqueProfileSettingsScreen(
    detourName: String,
    onSelectDetour: () -> Unit,
) {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val textKeys = remember {
        listOf(
            "name",
            "masqueProfileId",
            "profileAuthToken",
            "profilePrivateKey",
            "configPrivateKey",
            "configEndpointV4",
            "configEndpointV6",
            "configEndpointH2V4",
            "configEndpointH2V6",
            "configEndpointPubKey",
            "configLicense",
            "configId",
            "configAccessToken",
            "configIPv4",
            "configIPv6",
            "udpTimeout",
            "udpKeepalivePeriod",
            "udpInitialPacketSize",
            "reconnectDelay",
            "tlsSNI",
            "tlsCipherSuites",
            "tlsCurvePreferences",
            "tlsFragmentFallbackDelay",
        )
    }
    val textValues = remember {
        mutableStateMapOf<String, String>().apply {
            textKeys.forEach { put(it, store.getString(it).orEmpty()) }
        }
    }
    val booleanKeys = remember {
        listOf(
            "useHTTP2",
            "useIPv6",
            "profileRecreate",
            "tlsInsecure",
            "tlsFragment",
            "tlsRecordFragment",
            "tlsKernelTx",
            "tlsKernelRx",
        )
    }
    val booleanValues = remember {
        mutableStateMapOf<String, Boolean>().apply {
            booleanKeys.forEach { put(it, store.getBoolean(it, false)) }
        }
    }

    fun summary(value: String) = value.ifBlank { notSet }
    fun secretSummary(value: String) = value.takeIf(String::isNotBlank)
        ?.let { "\u2022".repeat(it.length) } ?: notSet

    @Composable
    fun textRow(
        @DrawableRes icon: Int,
        @StringRes title: Int,
        key: String,
        secret: Boolean = false,
        keyboardType: KeyboardType = KeyboardType.Text,
    ) {
        val value = textValues[key].orEmpty()
        val titleText = stringResource(title)
        ProfileActionRow(
            icon,
            title,
            if (secret) secretSummary(value) else summary(value),
        ) {
            context.showComposeTextInputDialog(
                title = titleText,
                initialValue = value,
                keyboardType = keyboardType,
                password = secret,
                onPositive = {
                    textValues[key] = it
                    store.putString(key, it)
                },
            )
        }
    }

    @Composable
    fun switchRow(@DrawableRes icon: Int, @StringRes title: Int, key: String) {
        val checked = booleanValues[key] == true
        ProfileSwitchRow(icon, title, checked) {
            booleanValues[key] = it
            store.putBoolean(key, it)
        }
    }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item { textRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name, "name") }
        item { ProfileCategory(R.string.masque_tunnel_settings) }
        item { switchRow(R.drawable.ic_baseline_http_24, R.string.masque_use_http2, "useHTTP2") }
        item { switchRow(R.drawable.baseline_public_24, R.string.masque_use_ipv6, "useIPv6") }
        item { ProfileCategory(R.string.masque_cloudflare_profile) }
        item {
            textRow(
                R.drawable.ic_baseline_fingerprint_24,
                R.string.masque_profile_id,
                "masqueProfileId",
            )
        }
        item {
            textRow(
                R.drawable.ic_settings_password,
                R.string.masque_profile_auth_token,
                "profileAuthToken",
                secret = true,
            )
        }
        item {
            textRow(
                R.drawable.ic_baseline_vpn_key_24,
                R.string.masque_profile_private_key,
                "profilePrivateKey",
                secret = true,
            )
        }
        item {
            ProfileActionRow(
                R.drawable.ic_baseline_call_split_24,
                R.string.masque_profile_detour,
                detourName,
                onClick = onSelectDetour,
            )
        }
        item {
            switchRow(
                R.drawable.ic_baseline_refresh_24,
                R.string.masque_profile_recreate,
                "profileRecreate",
            )
        }
        item { ProfileCategory(R.string.masque_config) }
        item {
            textRow(
                R.drawable.ic_baseline_vpn_key_24,
                R.string.masque_config_private_key,
                "configPrivateKey",
                secret = true,
            )
        }
        item {
            textRow(R.drawable.baseline_public_24, R.string.masque_config_endpoint_v4, "configEndpointV4")
        }
        item {
            textRow(R.drawable.baseline_public_24, R.string.masque_config_endpoint_v6, "configEndpointV6")
        }
        item {
            textRow(R.drawable.ic_baseline_http_24, R.string.masque_config_endpoint_h2_v4, "configEndpointH2V4")
        }
        item {
            textRow(R.drawable.ic_baseline_http_24, R.string.masque_config_endpoint_h2_v6, "configEndpointH2V6")
        }
        item {
            textRow(
                R.drawable.ic_baseline_vpn_key_24,
                R.string.masque_config_endpoint_pub_key,
                "configEndpointPubKey",
            )
        }
        item { textRow(R.drawable.ic_action_copyright, R.string.license, "configLicense") }
        item {
            textRow(R.drawable.ic_baseline_fingerprint_24, R.string.masque_config_id, "configId")
        }
        item {
            textRow(
                R.drawable.ic_settings_password,
                R.string.masque_config_access_token,
                "configAccessToken",
                secret = true,
            )
        }
        item { textRow(R.drawable.baseline_public_24, R.string.masque_config_ipv4, "configIPv4") }
        item { textRow(R.drawable.baseline_public_24, R.string.masque_config_ipv6, "configIPv6") }
        item { ProfileCategory(R.string.masterdnsvpn_advanced) }
        item { textRow(R.drawable.ic_baseline_timer_24, R.string.masque_udp_timeout, "udpTimeout") }
        item {
            textRow(
                R.drawable.ic_baseline_timelapse_24,
                R.string.masque_udp_keepalive_period,
                "udpKeepalivePeriod",
            )
        }
        item {
            textRow(
                R.drawable.ic_baseline_grid_3x3_24,
                R.string.masque_udp_initial_packet_size,
                "udpInitialPacketSize",
                keyboardType = KeyboardType.Number,
            )
        }
        item {
            textRow(
                R.drawable.ic_baseline_update_24,
                R.string.masque_reconnect_delay,
                "reconnectDelay",
            )
        }
        item { ProfileCategory(R.string.security_settings) }
        item { textRow(R.drawable.ic_action_copyright, R.string.sni, "tlsSNI") }
        item {
            switchRow(
                R.drawable.ic_notification_enhanced_encryption,
                R.string.allow_insecure,
                "tlsInsecure",
            )
        }
        item {
            textRow(
                R.drawable.ic_baseline_lock_24,
                R.string.masque_tls_cipher_suites,
                "tlsCipherSuites",
            )
        }
        item {
            textRow(
                R.drawable.ic_baseline_multiple_stop_24,
                R.string.masque_tls_curve_preferences,
                "tlsCurvePreferences",
            )
        }
        item {
            switchRow(R.drawable.ic_baseline_compress_24, R.string.tls_fragment, "tlsFragment")
        }
        item {
            textRow(
                R.drawable.ic_baseline_timer_24,
                R.string.tls_fragment_fallback_delay,
                "tlsFragmentFallbackDelay",
            )
        }
        item {
            switchRow(
                R.drawable.ic_baseline_texture_24,
                R.string.tls_record_fragment,
                "tlsRecordFragment",
            )
        }
        item {
            switchRow(
                R.drawable.ic_baseline_upload_24,
                R.string.masque_tls_kernel_tx,
                "tlsKernelTx",
            )
        }
        item {
            switchRow(
                R.drawable.ic_baseline_download_24,
                R.string.masque_tls_kernel_rx,
                "tlsKernelRx",
            )
        }
        item { SharedDialOptions() }
    }
}
