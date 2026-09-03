package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.ui.compose.SSHProfileSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import libcore.Libcore

class SSHSettingsActivity : ProfileSettingsActivity<SSHBean>() {

    override val usesComposePreferences = true

    private val hostKeyFetchViewModel: SSHHostKeyFetchViewModel by viewModels()

    override fun createEntity() = SSHBean()

    override fun SSHBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = username
        DataStore.serverAuthType = authType
        DataStore.serverPassword = password
        DataStore.serverPrivateKey = privateKey
        DataStore.serverPassword1 = privateKeyPassphrase
        DataStore.serverCertificates = publicKey
        DataStore.profileCacheStore.putString("sshHostKeyAlgorithms", hostKeyAlgorithms)
        DataStore.profileCacheStore.putString("sshClientVersion", clientVersion)
        DataStore.profileCacheStore.putString("sshCipher", cipher)
        DataStore.profileCacheStore.putString("sshMac", mac)
        DataStore.profileCacheStore.putString("sshKexAlgorithm", kexAlgorithm)
    }

    override fun SSHBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        username = DataStore.serverUsername
        authType = DataStore.serverAuthType
        when (authType) {
            SSHBean.AUTH_TYPE_NONE -> {
            }
            SSHBean.AUTH_TYPE_PASSWORD -> {
                password = DataStore.serverPassword
            }
            SSHBean.AUTH_TYPE_PRIVATE_KEY -> {
                privateKey = DataStore.serverPrivateKey
                privateKeyPassphrase = DataStore.serverPassword1
            }
        }
        publicKey = DataStore.serverCertificates
        hostKeyAlgorithms = DataStore.profileCacheStore.getString("sshHostKeyAlgorithms").orEmpty()
        clientVersion = DataStore.profileCacheStore.getString("sshClientVersion").orEmpty()
        cipher = DataStore.profileCacheStore.getString("sshCipher").orEmpty()
        mac = DataStore.profileCacheStore.getString("sshMac").orEmpty()
        kexAlgorithm = DataStore.profileCacheStore.getString("sshKexAlgorithm").orEmpty()
    }

    @Composable
    override fun ComposePreferences() = SSHProfileSettingsScreen(hostKeyFetchViewModel)
}

internal sealed interface SSHHostKeyFetchState {
    data object Idle : SSHHostKeyFetchState
    data object Loading : SSHHostKeyFetchState
    data class Success(val hostKey: String) : SSHHostKeyFetchState
    data class Failure(val error: Throwable) : SSHHostKeyFetchState
}

internal class SSHHostKeyFetchViewModel : ViewModel() {
    private val _state = MutableStateFlow<SSHHostKeyFetchState>(SSHHostKeyFetchState.Idle)
    val state: StateFlow<SSHHostKeyFetchState> = _state.asStateFlow()

    fun fetch(host: String, port: String) {
        if (_state.value == SSHHostKeyFetchState.Loading) return
        _state.value = SSHHostKeyFetchState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = runCatching {
                Libcore.fetchSSHHostKey(host, port)
            }.fold(
                onSuccess = SSHHostKeyFetchState::Success,
                onFailure = SSHHostKeyFetchState::Failure,
            )
        }
    }

    fun consume(state: SSHHostKeyFetchState) {
        _state.compareAndSet(state, SSHHostKeyFetchState.Idle)
    }
}
