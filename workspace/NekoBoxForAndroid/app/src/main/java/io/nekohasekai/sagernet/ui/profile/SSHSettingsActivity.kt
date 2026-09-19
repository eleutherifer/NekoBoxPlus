package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import libcore.Libcore
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class SSHSettingsActivity : ProfileSettingsActivity<SSHBean>() {

    private val hostKeyFetchViewModel: SSHHostKeyFetchViewModel by viewModels()
    private var hostKeyProgressDialog: AlertDialog? = null

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
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.ssh_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        val password = findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        val privateKey = findPreference<EditTextPreference>(Key.SERVER_PRIVATE_KEY)!!
        val privateKeyPassphrase = findPreference<EditTextPreference>(Key.SERVER_PASSWORD1)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        val authType = findPreference<SimpleMenuPreference>(Key.SERVER_AUTH_TYPE)!!
        findPreference<Preference>(Key.FETCH_SSH_HOST_KEY)!!.setOnPreferenceClickListener {
            hostKeyFetchViewModel.fetch(DataStore.serverAddress, DataStore.serverPort.toString())
            true
        }
        fun updateAuthType(type: Int = DataStore.serverAuthType) {
            password.isVisible = type == SSHBean.AUTH_TYPE_PASSWORD
            privateKey.isVisible = type == SSHBean.AUTH_TYPE_PRIVATE_KEY
            privateKeyPassphrase.isVisible = type == SSHBean.AUTH_TYPE_PRIVATE_KEY
        }
        updateAuthType()
        authType.setOnPreferenceChangeListener { _, newValue ->
            updateAuthType((newValue as String).toInt())
            true
        }
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                hostKeyFetchViewModel.state.collect { state ->
                    when (state) {
                        SSHHostKeyFetchState.Idle -> dismissHostKeyProgress()
                        SSHHostKeyFetchState.Loading -> showHostKeyProgress()
                        is SSHHostKeyFetchState.Success -> {
                            dismissHostKeyProgress()
                            findPreference<EditTextPreference>(Key.SERVER_CERTIFICATES)?.text =
                                state.hostKey
                            hostKeyFetchViewModel.consume(state)
                        }
                        is SSHHostKeyFetchState.Failure -> {
                            dismissHostKeyProgress()
                            Snackbar.make(
                                view,
                                getString(
                                    R.string.ssh_fetch_host_key_failed,
                                    state.error.readableMessage,
                                ),
                                Snackbar.LENGTH_LONG,
                            ).show()
                            hostKeyFetchViewModel.consume(state)
                        }
                    }
                }
            }
        }
    }

    private fun showHostKeyProgress() {
        if (hostKeyProgressDialog?.isShowing == true || isFinishing || isDestroyed) return
        val density = resources.displayMetrics.density
        val horizontalPadding = (24 * density).toInt()
        val verticalPadding = (16 * density).toInt()
        val spacing = (16 * density).toInt()
        val progressSize = (40 * density).toInt()
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val message = TextView(this).apply {
            setText(R.string.connecting)
        }
        val container = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            addView(
                progress,
                LinearLayout.LayoutParams(progressSize, progressSize),
            )
            addView(
                message,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = spacing
                },
            )
        }
        hostKeyProgressDialog = MaterialAlertDialogBuilder(this)
            .setView(container)
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                show()
            }
    }

    private fun dismissHostKeyProgress() {
        hostKeyProgressDialog?.dismiss()
        hostKeyProgressDialog = null
    }

    override fun onDestroy() {
        dismissHostKeyProgress()
        super.onDestroy()
    }
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
