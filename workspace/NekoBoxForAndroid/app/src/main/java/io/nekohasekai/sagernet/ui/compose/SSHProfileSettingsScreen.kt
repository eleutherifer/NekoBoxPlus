package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ui.profile.SSHHostKeyFetchState
import io.nekohasekai.sagernet.ui.profile.SSHHostKeyFetchViewModel

@Composable
internal fun SSHProfileSettingsScreen(viewModel: SSHHostKeyFetchViewModel) {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val fetchState by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    val authLabels = stringArrayResource(R.array.ssh_auth_type).toList()
    val authValues = stringArrayResource(R.array.int_array_3).toList()
    val nameTitle = stringResource(R.string.profile_name)
    val addressTitle = stringResource(R.string.server_address)
    val portTitle = stringResource(R.string.server_port)
    val usernameTitle = stringResource(R.string.username)
    val authTitle = stringResource(R.string.hysteria_auth_type)
    val passwordTitle = stringResource(R.string.password)
    val privateKeyTitle = stringResource(R.string.ssh_private_key)
    val passphraseTitle = stringResource(R.string.ssh_private_key_passphrase)
    val publicKeyTitle = stringResource(R.string.ssh_public_key)
    val hostKeyAlgorithmsTitle = stringResource(R.string.ssh_host_key_algorithms)
    val clientVersionTitle = stringResource(R.string.ssh_client_version)
    val cipherTitle = stringResource(R.string.ssh_ciphers)
    val macTitle = stringResource(R.string.ssh_mac_algorithms)
    val kexTitle = stringResource(R.string.ssh_kex_algorithms)
    val connecting = stringResource(R.string.connecting)
    val fetchFailureTemplate = stringResource(R.string.ssh_fetch_host_key_failed, "__error__")

    var name by remember { mutableStateOf(DataStore.profileName) }
    var address by remember { mutableStateOf(DataStore.serverAddress) }
    var port by remember { mutableStateOf(DataStore.serverPort.toString()) }
    var username by remember { mutableStateOf(DataStore.serverUsername) }
    var authType by remember { mutableIntStateOf(DataStore.serverAuthType) }
    var password by remember { mutableStateOf(DataStore.serverPassword) }
    var privateKey by remember { mutableStateOf(DataStore.serverPrivateKey) }
    var passphrase by remember { mutableStateOf(DataStore.serverPassword1) }
    var publicKey by remember { mutableStateOf(DataStore.serverCertificates) }
    var hostKeyAlgorithms by remember { mutableStateOf(store.getString("sshHostKeyAlgorithms").orEmpty()) }
    var clientVersion by remember { mutableStateOf(store.getString("sshClientVersion").orEmpty()) }
    var cipher by remember { mutableStateOf(store.getString("sshCipher").orEmpty()) }
    var mac by remember { mutableStateOf(store.getString("sshMac").orEmpty()) }
    var kex by remember { mutableStateOf(store.getString("sshKexAlgorithm").orEmpty()) }

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
    fun secretSummary(value: String) = value.takeIf(String::isNotBlank)
        ?.let { "\u2022".repeat(it.length) } ?: notSet

    LaunchedEffect(fetchState) {
        when (val state = fetchState) {
            is SSHHostKeyFetchState.Success -> {
                publicKey = state.hostKey
                DataStore.serverCertificates = state.hostKey
                viewModel.consume(state)
            }
            is SSHHostKeyFetchState.Failure -> {
                snackbarHostState.showSnackbar(
                    fetchFailureTemplate.replace("__error__", state.error.readableMessage),
                )
                viewModel.consume(state)
            }
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item {
                ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.profile_name,
                    summary(name)) {
                    edit(nameTitle, name) { name = it; DataStore.profileName = it }
                }
            }
            item { ProfileCategory(R.string.proxy_cat) }
            item {
                ProfileActionRow(R.drawable.ic_hardware_router, R.string.server_address,
                    summary(address)) {
                    edit(addressTitle, address) { address = it; DataStore.serverAddress = it }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.server_port,
                    summary(port)) {
                    edit(portTitle, port, KeyboardType.Number, 5) {
                        port = it
                        DataStore.serverPort = it.toIntOrNull() ?: 0
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_person_24, R.string.username,
                    summary(username)) {
                    edit(usernameTitle, username) { username = it; DataStore.serverUsername = it }
                }
            }
            item {
                val selected = authValues.indexOf(authType.toString()).coerceAtLeast(0)
                ProfileActionRow(R.drawable.ic_baseline_compare_arrows_24,
                    R.string.hysteria_auth_type, authLabels.getOrElse(selected) { authType.toString() }) {
                    context.showComposeSingleChoiceDialog(
                        title = authTitle,
                        items = authLabels,
                        selectedIndex = selected,
                        negativeButton = cancel,
                        onItemSelected = {
                            authType = authValues[it].toIntOrNull() ?: SSHBean.AUTH_TYPE_NONE
                            DataStore.serverAuthType = authType
                        },
                    )
                }
            }
            if (authType == SSHBean.AUTH_TYPE_PASSWORD) {
                item {
                    ProfileActionRow(R.drawable.ic_settings_password, R.string.password,
                        secretSummary(password)) {
                        edit(passwordTitle, password, secret = true) {
                            password = it; DataStore.serverPassword = it
                        }
                    }
                }
            }
            if (authType == SSHBean.AUTH_TYPE_PRIVATE_KEY) {
                item {
                    ProfileActionRow(R.drawable.ic_baseline_vpn_key_24, R.string.ssh_private_key,
                        summary(privateKey)) {
                        edit(privateKeyTitle, privateKey) {
                            privateKey = it; DataStore.serverPrivateKey = it
                        }
                    }
                }
                item {
                    ProfileActionRow(R.drawable.ic_settings_password,
                        R.string.ssh_private_key_passphrase, secretSummary(passphrase)) {
                        edit(passphraseTitle, passphrase, secret = true) {
                            passphrase = it; DataStore.serverPassword1 = it
                        }
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_action_copyright, R.string.ssh_public_key,
                    summary(publicKey)) {
                    edit(publicKeyTitle, publicKey) {
                        publicKey = it; DataStore.serverCertificates = it
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_download_24, R.string.ssh_fetch_host_key,
                    stringResource(R.string.ssh_fetch_host_key_summary), dynamicSummary = false) {
                    viewModel.fetch(address, port)
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_multiple_stop_24,
                    R.string.ssh_host_key_algorithms, summary(hostKeyAlgorithms)) {
                    edit(hostKeyAlgorithmsTitle, hostKeyAlgorithms) {
                        hostKeyAlgorithms = it; store.putString("sshHostKeyAlgorithms", it)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_legend_toggle_24, R.string.ssh_client_version,
                    summary(clientVersion)) {
                    edit(clientVersionTitle, clientVersion) {
                        clientVersion = it; store.putString("sshClientVersion", it)
                    }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_action_lock, R.string.ssh_ciphers, summary(cipher)) {
                    edit(cipherTitle, cipher) { cipher = it; store.putString("sshCipher", it) }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_fingerprint_24, R.string.ssh_mac_algorithms,
                    summary(mac)) {
                    edit(macTitle, mac) { mac = it; store.putString("sshMac", it) }
                }
            }
            item {
                ProfileActionRow(R.drawable.ic_baseline_multiple_stop_24, R.string.ssh_kex_algorithms,
                    summary(kex)) {
                    edit(kexTitle, kex) { kex = it; store.putString("sshKexAlgorithm", it) }
                }
            }
            item { SharedDialOptions() }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    if (fetchState == SSHHostKeyFetchState.Loading) {
        Dialog(onDismissRequest = {}) {
            BlockingProgressContent(title = null, message = connecting)
        }
    }
}
