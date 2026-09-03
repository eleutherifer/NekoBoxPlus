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
internal fun AmneziaWGProfileSettingsScreen() {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val keys = remember {
        listOf(
            "name", "serverAddress", "serverPort", "localAddress", "privateKey",
            "peerPublicKey", "peerPreSharedKey", "peerPersistentKeepalive", "mtu",
            "reserved", "jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3",
            "h4", "i1", "i2", "i3", "i4", "i5", "s3", "s4",
            "headerProtectionKey", "contentPaddingAddition", "rekeyAfterTime",
            "rekeyTimeout", "rejectAfterTime", "keepaliveTimeout", "maxHandshakeAttempts",
        )
    }
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            keys.forEach { put(it, store.getString(it).orEmpty()) }
        }
    }
    val booleanValues = remember {
        mutableStateMapOf(
            "randomTrailers" to store.getBoolean("randomTrailers", false),
            "disableCookies" to store.getBoolean("disableCookies", false),
        )
    }

    @Composable
    fun textRow(
        @DrawableRes icon: Int,
        @StringRes title: Int,
        key: String,
        secret: Boolean = false,
        number: Boolean = false,
        maxLength: Int = Int.MAX_VALUE,
    ) {
        val value = values[key].orEmpty()
        val summary = if (secret && value.isNotBlank()) "\u2022".repeat(value.length)
        else value.ifBlank { notSet }
        val titleText = stringResource(title)
        ProfileActionRow(icon, title, summary) {
            context.showComposeTextInputDialog(
                title = titleText,
                initialValue = value,
                keyboardType = if (secret) KeyboardType.Password else if (number) KeyboardType.Number else KeyboardType.Text,
                maxLength = maxLength,
                password = secret,
                onPositive = {
                    values[key] = it
                    store.putString(key, it)
                },
            )
        }
    }

    @Composable
    fun switchRow(@DrawableRes icon: Int, @StringRes title: Int, key: String) {
        ProfileSwitchRow(icon, title, booleanValues[key] == true) {
            booleanValues[key] = it
            store.putBoolean(key, it)
        }
    }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item { textRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name, "name") }
        item { ProfileCategory(R.string.proxy_cat) }
        item { textRow(R.drawable.ic_hardware_router, R.string.server_address, "serverAddress") }
        item { textRow(R.drawable.ic_maps_directions_boat, R.string.server_port, "serverPort", number = true, maxLength = 5) }
        item { textRow(R.drawable.ic_baseline_domain_24, R.string.wireguard_local_address, "localAddress") }
        item { textRow(R.drawable.ic_baseline_vpn_key_24, R.string.ssh_private_key, "privateKey", secret = true) }
        item { textRow(R.drawable.ic_action_copyright, R.string.wireguard_public_key, "peerPublicKey") }
        item { textRow(R.drawable.ic_settings_password, R.string.wireguard_psk, "peerPreSharedKey", secret = true) }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.wireguard_persistent_keepalive, "peerPersistentKeepalive", number = true) }
        item { textRow(R.drawable.baseline_public_24, R.string.mtu, "mtu", number = true) }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.wireguard_reserved, "reserved") }

        item { ProfileCategory(R.string.amneziawg_obfuscation_awg10) }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_jc, "jc", number = true) }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_jmin, "jmin", number = true) }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_jmax, "jmax", number = true) }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_s1, "s1", number = true) }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_s2, "s2", number = true) }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_h1, "h1") }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_h2, "h2") }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_h3, "h3") }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_h4, "h4") }

        item { ProfileCategory(R.string.amneziawg_obfuscation_awg15) }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_i1, "i1") }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_i2, "i2") }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_i3, "i3") }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_i4, "i4") }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_i5, "i5") }

        item { ProfileCategory(R.string.amneziawg_obfuscation_awg20) }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_s3, "s3", number = true) }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_s4, "s4", number = true) }

        item { ProfileCategory(R.string.amneziawg_obfuscation_awg30) }
        item { textRow(R.drawable.ic_baseline_vpn_key_24, R.string.amneziawg_header_protection_key, "headerProtectionKey", secret = true) }
        item { textRow(R.drawable.ic_device_data_usage, R.string.amneziawg_content_padding_addition, "contentPaddingAddition", number = true) }
        item { textRow(R.drawable.ic_baseline_timer_24, R.string.amneziawg_rekey_after_time, "rekeyAfterTime") }
        item { textRow(R.drawable.ic_baseline_timer_24, R.string.amneziawg_rekey_timeout, "rekeyTimeout") }
        item { textRow(R.drawable.ic_baseline_timer_24, R.string.amneziawg_reject_after_time, "rejectAfterTime") }
        item { textRow(R.drawable.ic_baseline_timer_24, R.string.amneziawg_keepalive_timeout, "keepaliveTimeout") }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_max_handshake_attempts, "maxHandshakeAttempts", number = true) }

        item { ProfileCategory(R.string.amneziawg_obfuscation_awg31) }
        item { switchRow(R.drawable.ic_baseline_fingerprint_24, R.string.amneziawg_random_trailers, "randomTrailers") }
        item { switchRow(R.drawable.ic_settings_password, R.string.amneziawg_disable_cookies, "disableCookies") }
        item { SharedDialOptions() }
    }
}
