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
internal fun ShadowTLSProfileSettingsScreen() {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    val versions = stringArrayResource(R.array.shadowtls_version_value).toList()
    val proxyLabels = stringArrayResource(R.array.proxy_protocol_entry).toList()
    val proxyValues = stringArrayResource(R.array.proxy_protocol_value).map(String::toInt)
    val fingerprintLabels = stringArrayResource(R.array.utls_fingerprint_entry).toList()
    val fingerprintValues = stringArrayResource(R.array.utls_fingerprint_value).toList()
    val nameTitle = stringResource(R.string.profile_name)
    val addressTitle = stringResource(R.string.server_address)
    val portTitle = stringResource(R.string.server_port)
    val versionTitle = stringResource(R.string.protocol_version)
    val passwordTitle = stringResource(R.string.password)
    val proxyTitle = stringResource(R.string.proxy_protocol)
    val sniTitle = stringResource(R.string.sni)
    val alpnTitle = stringResource(R.string.alpn)
    val certificatesTitle = stringResource(R.string.certificates)
    val fingerprintTitle = stringResource(R.string.utls_fingerprint)
    val insecureSummary = stringResource(R.string.allow_insecure_sum)
    var name by remember { mutableStateOf(store.getString("name").orEmpty()) }
    var address by remember { mutableStateOf(store.getString("serverAddress").orEmpty()) }
    var port by remember { mutableStateOf(store.getString("serverPort").orEmpty()) }
    var version by remember { mutableIntStateOf(store.getString("version")?.toIntOrNull() ?: 3) }
    var password by remember { mutableStateOf(store.getString("password").orEmpty()) }
    var proxy by remember { mutableIntStateOf(store.getString("proxyProtocol")?.toIntOrNull() ?: 0) }
    var sni by remember { mutableStateOf(store.getString("sni").orEmpty()) }
    var alpn by remember { mutableStateOf(store.getString("alpn").orEmpty()) }
    var certificates by remember { mutableStateOf(store.getString("certificates").orEmpty()) }
    var insecure by remember { mutableStateOf(store.getBoolean("allowInsecure", false)) }
    var fingerprint by remember { mutableStateOf(store.getString("utlsFingerprint").orEmpty()) }

    fun edit(
        title: String, value: String, keyboard: KeyboardType = KeyboardType.Text,
        maxLength: Int = Int.MAX_VALUE, secret: Boolean = false, update: (String) -> Unit,
    ) = context.showComposeTextInputDialog(
        title = title, initialValue = value, keyboardType = keyboard,
        maxLength = maxLength, password = secret, onPositive = update,
    )
    fun choose(
        title: String, labels: List<String>, selected: Int, update: (Int) -> Unit,
    ) = context.showComposeSingleChoiceDialog(
        title = title, items = labels, selectedIndex = selected.coerceIn(labels.indices),
        negativeButton = cancel, onItemSelected = update,
    )
    fun summary(value: String) = value.ifBlank { notSet }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item { ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name,
            summary(name)) { edit(nameTitle, name) { name = it; store.putString("name", it) } } }
        item { ProfileCategory(R.string.proxy_cat) }
        item { ProfileActionRow(R.drawable.ic_hardware_router, R.string.server_address,
            summary(address)) { edit(addressTitle, address) { address = it; store.putString("serverAddress", it) } } }
        item { ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.server_port,
            summary(port)) { edit(portTitle, port, KeyboardType.Number, 5) { port = it; store.putString("serverPort", it) } } }
        item {
            val selected = versions.indexOf(version.toString()).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_update_24, R.string.protocol_version,
                versions[selected]) { choose(versionTitle, versions, selected) {
                version = versions[it].toInt(); store.putString("version", version.toString())
            } }
        }
        item { ProfileActionRow(R.drawable.ic_notification_enhanced_encryption, R.string.password,
            password.takeIf(String::isNotBlank)?.let { "\u2022".repeat(it.length) } ?: notSet) {
            edit(passwordTitle, password, KeyboardType.Password, secret = true) {
                password = it; store.putString("password", it)
            }
        } }
        item {
            val selected = proxyValues.indexOf(proxy).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_compare_arrows_24, R.string.proxy_protocol,
                proxyLabels[selected]) { choose(proxyTitle, proxyLabels, selected) {
                proxy = proxyValues[it]; store.putString("proxyProtocol", proxy.toString())
            } }
        }
        item { ProfileCategory(R.string.security_settings) }
        item { ProfileActionRow(R.drawable.ic_action_copyright, R.string.sni, summary(sni)) {
            edit(sniTitle, sni) { sni = it; store.putString("sni", it) }
        } }
        item { ProfileActionRow(R.drawable.ic_baseline_legend_toggle_24, R.string.alpn, summary(alpn)) {
            edit(alpnTitle, alpn) { alpn = it; store.putString("alpn", it) }
        } }
        item { ProfileActionRow(R.drawable.ic_baseline_vpn_key_24, R.string.certificates,
            summary(certificates)) { edit(certificatesTitle, certificates) {
            certificates = it; store.putString("certificates", it)
        } } }
        item { ProfileSwitchRow(R.drawable.ic_notification_enhanced_encryption,
            R.string.allow_insecure, insecure, summary = insecureSummary, dynamicSummary = false) {
            insecure = it; store.putBoolean("allowInsecure", it)
        } }
        item {
            val selected = fingerprintValues.indexOf(fingerprint).coerceAtLeast(0)
            ProfileActionRow(R.drawable.ic_baseline_fingerprint_24, R.string.utls_fingerprint,
                fingerprintLabels[selected]) { choose(fingerprintTitle, fingerprintLabels, selected) {
                fingerprint = fingerprintValues[it]; store.putString("utlsFingerprint", fingerprint)
            } }
        }
        item { SharedDialOptions() }
        item { SharedTlsOptions() }
    }
}
