package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.backup.GitBackupConfig
import io.nekohasekai.sagernet.backup.GitBackupConfigStore
import io.nekohasekai.sagernet.backup.GitBackupConfigValidator
import io.nekohasekai.sagernet.backup.GitBackupError
import io.nekohasekai.sagernet.backup.GitBackupErrorClassifier
import io.nekohasekai.sagernet.backup.GitBackupRepository
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ui.compose.GitBackupSettingsScreen
import io.nekohasekai.sagernet.ui.compose.GitBackupSettingsUiState
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GitBackupSettingsActivity : ThemedActivity() {
    private lateinit var store: GitBackupConfigStore
    private var saved: GitBackupConfig? = null
    private var testedConnection: Pair<String, String>? = null
    private var checkJob: Job? = null
    private var state by mutableStateOf(GitBackupSettingsUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GitBackupConfigStore(this)
        saved = store.load()
        saved?.let {
            state = state.copy(repository = it.repositoryUrl, username = it.username, branch = it.branch)
        }
        setContent {
            NekoComposeTheme {
                GitBackupSettingsScreen(
                    state = state,
                    onClose = ::finish,
                    onRepositoryChange = { changeConnectionInput { copy(repository = it) } },
                    onUsernameChange = { changeConnectionInput { copy(username = it) } },
                    onCredentialChange = { changeConnectionInput { copy(credential = it) } },
                    onEncryptionPasswordChange = {
                        state = state.copy(encryptionPassword = it, encryptionError = "")
                        updateSaveEnabled()
                    },
                    onConfirmPasswordChange = {
                        state = state.copy(confirmPassword = it, confirmError = "")
                        updateSaveEnabled()
                    },
                    onTestConnection = ::testConnection,
                    onCancelCheck = { cancelCheck(showCancelled = true) },
                    onBranchSelected = ::selectBranch,
                    onSave = ::save,
                    onBranchNameChange = { state = state.copy(branchName = it, branchNameError = "") },
                    onConfirmCreateBranch = ::confirmCreateBranch,
                    onDismissCreateBranch = ::dismissCreateBranch,
                )
            }
        }
    }

    private fun changeConnectionInput(change: GitBackupSettingsUiState.() -> GitBackupSettingsUiState) {
        state = state.change()
        cancelCheck(showCancelled = false)
        testedConnection = null
        state = state.copy(
            branches = emptyList(),
            branchEnabled = false,
            repositoryError = "",
            checkError = "",
            saveEnabled = false,
        )
    }

    private fun candidate(branch: String = state.branch): GitBackupConfig {
        val previous = saved
        val repositoryUrl = GitBackupConfigValidator.validateHttpsUrl(state.repository)
        return GitBackupConfig(
            repositoryUrl,
            state.username.trim(),
            branch,
            state.credential.ifEmpty {
                previous?.credential.orEmpty().takeIf { previous?.repositoryUrl == repositoryUrl }.orEmpty()
            },
            state.encryptionPassword.ifEmpty { previous?.encryptionPassword.orEmpty() },
        )
    }

    private fun testConnection() {
        val config = runCatching { candidate("main") }.getOrElse {
            state = state.copy(repositoryError = getString(R.string.git_invalid_configuration))
            return
        }
        testedConnection = null
        state = state.copy(
            repositoryError = "",
            checkError = "",
            checking = true,
            checkingText = R.string.git_checking,
            checkCancellable = true,
            saveEnabled = false,
        )
        checkJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    GitBackupRepository(cacheDir.resolve("git-backup-test")).apply { clearCache() }
                        .listBranches(config)
                }
            }
            if (!isActive) return@launch
            checkJob = null
            state = state.copy(checking = false)
            result.onSuccess { branches ->
                val selected = saved?.branch?.takeIf(branches::contains) ?: branches.firstOrNull().orEmpty()
                testedConnection = config.repositoryUrl to config.username
                state = state.copy(
                    branches = branches,
                    branch = selected,
                    branchEnabled = true,
                    branchError = "",
                )
                updateSaveEnabled()
            }.onFailure {
                Logs.w(it)
                state = state.copy(
                    checkError = getString(
                        R.string.git_connection_failed,
                        getString(gitConnectionErrorMessage(GitBackupErrorClassifier.classify(it))),
                    ),
                )
            }
        }
    }

    private fun selectBranch(position: Int) {
        if (position == state.branches.size) {
            state = state.copy(showCreateBranch = true, branchName = "", branchNameError = "")
        } else {
            state = state.copy(branch = state.branches[position], branchError = "")
            updateSaveEnabled()
        }
    }

    private fun confirmCreateBranch() {
        val branch = runCatching { GitBackupConfigValidator.validateBranch(state.branchName) }
            .getOrElse {
                state = state.copy(branchNameError = getString(R.string.git_invalid_branch))
                return
            }
        state = state.copy(
            branch = branch,
            branchError = "",
            showCreateBranch = false,
            branchName = "",
        )
        updateSaveEnabled()
    }

    private fun dismissCreateBranch() {
        state = state.copy(
            branch = state.branches.firstOrNull().orEmpty(),
            showCreateBranch = false,
            branchName = "",
            branchNameError = "",
        )
        updateSaveEnabled()
    }

    private fun save() {
        val config = runCatching {
            candidate(GitBackupConfigValidator.validateBranch(state.branch))
        }.getOrElse {
            state = state.copy(branchError = getString(R.string.git_invalid_branch))
            return
        }
        if (testedConnection != config.repositoryUrl to config.username) return
        if (saved == null && state.encryptionPassword.isEmpty()) {
            state = state.copy(encryptionError = getString(R.string.git_encryption_password_required))
            return
        }
        if (state.encryptionPassword.isNotEmpty() && state.encryptionPassword != state.confirmPassword) {
            state = state.copy(confirmError = getString(R.string.git_passwords_do_not_match))
            return
        }
        state = state.copy(
            branchError = "",
            encryptionError = "",
            confirmError = "",
            checkError = "",
            saveEnabled = false,
            saving = true,
        )
        checkJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    GitBackupRepository(cacheDir.resolve("git-backup-repository")).ensureBranch(config)
                }
            }
            if (!isActive) return@launch
            checkJob = null
            state = state.copy(saving = false)
            result.onSuccess {
                store.save(config)
                setResult(RESULT_OK)
                finish()
            }.onFailure {
                Logs.w(it)
                state = state.copy(
                    checkError = getString(
                        R.string.git_branch_save_failed_detail,
                        getString(gitConnectionErrorMessage(GitBackupErrorClassifier.classify(it))),
                    ),
                )
                updateSaveEnabled()
            }
        }
    }

    private fun cancelCheck(showCancelled: Boolean) {
        checkJob?.cancel()
        checkJob = null
        state = state.copy(
            checking = false,
            checkError = if (showCancelled) getString(R.string.git_check_cancelled) else state.checkError,
        )
        updateSaveEnabled()
    }

    private fun updateSaveEnabled() {
        val branchValid = runCatching { GitBackupConfigValidator.validateBranch(state.branch) }.isSuccess
        val passwordAvailable = state.encryptionPassword.isNotEmpty() ||
            saved?.encryptionPassword?.isNotEmpty() == true
        val passwordConfirmed = state.encryptionPassword.isEmpty() ||
            state.encryptionPassword == state.confirmPassword
        state = state.copy(
            saveEnabled = testedConnection != null && branchValid && passwordAvailable &&
                passwordConfirmed && checkJob == null && !state.saving,
        )
    }

    private fun gitConnectionErrorMessage(error: GitBackupError): Int = when (error) {
        GitBackupError.AUTHENTICATION -> R.string.git_authentication_failed
        GitBackupError.CLIENT_REJECTED -> R.string.git_client_rejected
        GitBackupError.REPOSITORY_NOT_FOUND -> R.string.git_repository_not_found
        GitBackupError.DNS -> R.string.git_dns_failed
        GitBackupError.TLS -> R.string.git_tls_failed
        GitBackupError.TIMEOUT -> R.string.git_connection_timeout
        GitBackupError.INTERNAL -> R.string.git_internal_error
        GitBackupError.REMOTE -> R.string.git_remote_error
    }
}
