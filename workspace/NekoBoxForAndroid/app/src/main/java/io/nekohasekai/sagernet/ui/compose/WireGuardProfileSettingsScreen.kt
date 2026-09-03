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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

@Composable
internal fun WireGuardProfileSettingsScreen() {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val nameTitle = stringResource(R.string.profile_name)
    val addressTitle = stringResource(R.string.server_address)
    val portTitle = stringResource(R.string.server_port)
    val localTitle = stringResource(R.string.wireguard_local_address)
    val privateTitle = stringResource(R.string.ssh_private_key)
    val publicTitle = stringResource(R.string.wireguard_public_key)
    val pskTitle = stringResource(R.string.wireguard_psk)
    val keepaliveTitle = stringResource(R.string.wireguard_persistent_keepalive)
    val mtuTitle = stringResource(R.string.mtu)
    val reservedTitle = stringResource(R.string.wireguard_reserved)
    var name by remember { mutableStateOf(store.getString("name").orEmpty()) }
    var address by remember { mutableStateOf(store.getString("serverAddress").orEmpty()) }
    var port by remember { mutableStateOf(store.getString("serverPort").orEmpty()) }
    var local by remember { mutableStateOf(store.getString("localAddress").orEmpty()) }
    var privateKey by remember { mutableStateOf(store.getString("privateKey").orEmpty()) }
    var publicKey by remember { mutableStateOf(store.getString("peerPublicKey").orEmpty()) }
    var psk by remember { mutableStateOf(store.getString("peerPreSharedKey").orEmpty()) }
    var keepalive by remember { mutableStateOf(store.getString("peerPersistentKeepalive") ?: "0") }
    var mtu by remember { mutableStateOf(store.getString("mtu") ?: "1280") }
    var reserved by remember { mutableStateOf(store.getString("reserved").orEmpty()) }

    fun edit(
        title: String,
        value: String,
        keyboardType: KeyboardType = KeyboardType.Text,
        maxLength: Int = Int.MAX_VALUE,
        secret: Boolean = false,
        update: (String) -> Unit,
    ) = context.showComposeTextInputDialog(
        title = title, initialValue = value, keyboardType = keyboardType,
        maxLength = maxLength, password = secret,
        onPositive = update,
    )
    fun summary(value: String) = value.ifBlank { notSet }
    fun secretSummary(value: String) = value.takeIf(String::isNotBlank)
        ?.let { "\u2022".repeat(it.length) } ?: notSet

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item { ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name,
            summary(name)) { edit(nameTitle, name) { name = it; store.putString("name", it) } } }
        item { ProfileCategory(R.string.proxy_cat) }
        item { ProfileActionRow(R.drawable.ic_hardware_router, R.string.server_address,
            summary(address)) { edit(addressTitle, address) { address = it; store.putString("serverAddress", it) } } }
        item { ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.server_port,
            summary(port)) { edit(portTitle, port, KeyboardType.Number, 5) { port = it; store.putString("serverPort", it) } } }
        item { ProfileActionRow(R.drawable.ic_baseline_domain_24, R.string.wireguard_local_address,
            summary(local)) { edit(localTitle, local) {
                local = it; store.putString("localAddress", it)
            } } }
        item { ProfileActionRow(R.drawable.ic_baseline_vpn_key_24, R.string.ssh_private_key,
            secretSummary(privateKey)) { edit(privateTitle, privateKey, KeyboardType.Password, secret = true) {
            privateKey = it; store.putString("privateKey", it)
        } } }
        item { ProfileActionRow(R.drawable.ic_action_copyright, R.string.wireguard_public_key,
            summary(publicKey)) { edit(publicTitle, publicKey) { publicKey = it; store.putString("peerPublicKey", it) } } }
        item { ProfileActionRow(R.drawable.ic_settings_password, R.string.wireguard_psk,
            secretSummary(psk)) { edit(pskTitle, psk, KeyboardType.Password, secret = true) {
            psk = it; store.putString("peerPreSharedKey", it)
        } } }
        item { ProfileActionRow(R.drawable.ic_baseline_fingerprint_24,
            R.string.wireguard_persistent_keepalive, summary(keepalive)) {
            edit(keepaliveTitle, keepalive, KeyboardType.Number) {
                keepalive = it; store.putString("peerPersistentKeepalive", it)
            }
        } }
        item { ProfileActionRow(R.drawable.baseline_public_24, R.string.mtu, summary(mtu)) {
            edit(mtuTitle, mtu, KeyboardType.Number) { mtu = it; store.putString("mtu", it) }
        } }
        item { ProfileActionRow(R.drawable.ic_baseline_fingerprint_24, R.string.wireguard_reserved,
            summary(reserved)) { edit(reservedTitle, reserved) { reserved = it; store.putString("reserved", it) } } }
        item { SharedDialOptions() }
    }
}
