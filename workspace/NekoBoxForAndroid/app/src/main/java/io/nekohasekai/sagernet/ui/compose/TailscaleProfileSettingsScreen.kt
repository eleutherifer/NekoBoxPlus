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
internal fun TailscaleProfileSettingsScreen() {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val titles = mapOf(
        "name" to stringResource(R.string.profile_name),
        "authKey" to stringResource(R.string.tailscale_auth_key),
        "controlURL" to stringResource(R.string.tailscale_control_url),
        "hostname" to stringResource(R.string.tailscale_hostname),
        "exitNode" to stringResource(R.string.tailscale_exit_node),
        "advertiseRoutes" to stringResource(R.string.tailscale_advertise_routes),
        "advertiseTags" to stringResource(R.string.tailscale_advertise_tags),
        "relayServerPort" to stringResource(R.string.tailscale_relay_server_port),
        "relayServerStaticEndpoints" to stringResource(R.string.tailscale_relay_server_static_endpoints),
        "udpTimeout" to stringResource(R.string.tailscale_udp_timeout),
    )
    var name by remember { mutableStateOf(store.getString("name").orEmpty()) }
    var authKey by remember { mutableStateOf(store.getString("authKey").orEmpty()) }
    var controlUrl by remember { mutableStateOf(store.getString("controlURL").orEmpty()) }
    var hostname by remember { mutableStateOf(store.getString("hostname").orEmpty()) }
    var ephemeral by remember { mutableStateOf(store.getBoolean("ephemeral", false)) }
    var acceptRoutes by remember { mutableStateOf(store.getBoolean("acceptRoutes", false)) }
    var exitNode by remember { mutableStateOf(store.getString("exitNode").orEmpty()) }
    var exitNodeLan by remember {
        mutableStateOf(store.getBoolean("exitNodeAllowLANAccess", false))
    }
    var advertiseRoutes by remember { mutableStateOf(store.getString("advertiseRoutes").orEmpty()) }
    var advertiseExitNode by remember {
        mutableStateOf(store.getBoolean("advertiseExitNode", false))
    }
    var advertiseTags by remember { mutableStateOf(store.getString("advertiseTags").orEmpty()) }
    var relayPort by remember { mutableStateOf(store.getString("relayServerPort").orEmpty()) }
    var relayEndpoints by remember {
        mutableStateOf(store.getString("relayServerStaticEndpoints").orEmpty())
    }
    var udpTimeout by remember { mutableStateOf(store.getString("udpTimeout").orEmpty()) }
    var magicDns by remember { mutableStateOf(store.getBoolean("magicDNS", false)) }

    fun edit(
        key: String,
        value: String,
        keyboard: KeyboardType = KeyboardType.Text,
        maxLength: Int = Int.MAX_VALUE,
        secret: Boolean = false,
        update: (String) -> Unit,
    ) = context.showComposeTextInputDialog(
        title = titles.getValue(key),
        initialValue = value,
        keyboardType = keyboard,
        maxLength = maxLength,
        password = secret,
        onPositive = update,
    )
    fun summary(value: String) = value.ifBlank { notSet }
    @Composable
    fun textRow(icon: Int, title: Int, key: String, value: String, update: (String) -> Unit) =
        ProfileActionRow(icon, title, summary(value)) {
            edit(key, value) { update(it); store.putString(key, it) }
        }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item { textRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name, "name", name) {
            name = it
        } }
        item { ProfileCategory(R.string.tailscale_account_settings) }
        item {
            val secretSummary = authKey.takeIf(String::isNotBlank)
                ?.let { "\u2022".repeat(it.length) } ?: notSet
            ProfileActionRow(R.drawable.ic_settings_password, R.string.tailscale_auth_key,
                secretSummary) {
                edit("authKey", authKey, KeyboardType.Password, secret = true) {
                    authKey = it
                    store.putString("authKey", it)
                }
            }
        }
        item { textRow(R.drawable.ic_baseline_http_24, R.string.tailscale_control_url,
            "controlURL", controlUrl) { controlUrl = it } }
        item { textRow(R.drawable.ic_baseline_domain_24, R.string.tailscale_hostname,
            "hostname", hostname) { hostname = it } }
        item { ProfileSwitchRow(R.drawable.ic_baseline_delete_24, R.string.tailscale_ephemeral,
            ephemeral) { ephemeral = it; store.putBoolean("ephemeral", it) } }
        item { ProfileCategory(R.string.tailscale_route_settings) }
        item { ProfileSwitchRow(R.drawable.ic_baseline_call_split_24,
            R.string.tailscale_accept_routes, acceptRoutes) {
            acceptRoutes = it; store.putBoolean("acceptRoutes", it)
        } }
        item { textRow(R.drawable.baseline_public_24, R.string.tailscale_exit_node,
            "exitNode", exitNode) { exitNode = it } }
        item { ProfileSwitchRow(R.drawable.ic_baseline_layers_24,
            R.string.tailscale_exit_node_allow_lan_access, exitNodeLan) {
            exitNodeLan = it; store.putBoolean("exitNodeAllowLANAccess", it)
        } }
        item { textRow(R.drawable.ic_baseline_call_split_24, R.string.tailscale_advertise_routes,
            "advertiseRoutes", advertiseRoutes) { advertiseRoutes = it } }
        item { ProfileSwitchRow(R.drawable.baseline_public_24,
            R.string.tailscale_advertise_exit_node, advertiseExitNode) {
            advertiseExitNode = it; store.putBoolean("advertiseExitNode", it)
        } }
        item { textRow(R.drawable.ic_baseline_fingerprint_24, R.string.tailscale_advertise_tags,
            "advertiseTags", advertiseTags) { advertiseTags = it } }
        item { ProfileCategory(R.string.tailscale_advanced_settings) }
        item {
            ProfileActionRow(R.drawable.ic_action_settings, R.string.tailscale_relay_server_port,
                summary(relayPort)) {
                edit("relayServerPort", relayPort, KeyboardType.Number, 5) {
                    relayPort = it
                    store.putString("relayServerPort", it)
                }
            }
        }
        item { textRow(R.drawable.baseline_public_24,
            R.string.tailscale_relay_server_static_endpoints,
            "relayServerStaticEndpoints", relayEndpoints) { relayEndpoints = it } }
        item { textRow(R.drawable.ic_baseline_timer_24, R.string.tailscale_udp_timeout,
            "udpTimeout", udpTimeout) { udpTimeout = it } }
        item { ProfileSwitchRow(R.drawable.ic_baseline_dns_24, R.string.tailscale_magic_dns,
            magicDns, summary = stringResource(R.string.tailscale_magic_dns_summary), dynamicSummary = false) {
            magicDns = it; store.putBoolean("magicDNS", it)
        } }
        item { SharedDialOptions() }
    }
}
