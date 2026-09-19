package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.backup.GitBackupConfig
import io.nekohasekai.sagernet.backup.GitBackupConfigStore
import io.nekohasekai.sagernet.backup.GitBackupConfigValidator
import io.nekohasekai.sagernet.backup.GitBackupError
import io.nekohasekai.sagernet.backup.GitBackupErrorClassifier
import io.nekohasekai.sagernet.backup.GitBackupRepository
import io.nekohasekai.sagernet.databinding.LayoutGitBackupSettingsBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GitBackupSettingsActivity : ThemedActivity() {
    private lateinit var binding: LayoutGitBackupSettingsBinding
    private lateinit var store: GitBackupConfigStore
    private var saved: GitBackupConfig? = null
    private var testedConnection: Pair<String, String>? = null
    private var remoteBranches = emptyList<String>()
    private var checkJob: Job? = null
    private var saveProgressDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutGitBackupSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val baseBottomPadding = binding.content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { view, insets ->
            val bottomInset = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            ).bottom
            view.updatePadding(bottom = baseBottomPadding + bottomInset)
            insets
        }
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.git_backup_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        store = GitBackupConfigStore(this)
        saved = store.load()
        saved?.let {
            binding.repository.setText(it.repositoryUrl)
            binding.username.setText(it.username)
            binding.branch.setText(it.branch, false)
        }
        setSaveEnabled(false)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                cancelCheck(showCancelled = false)
                testedConnection = null
                remoteBranches = emptyList()
                binding.branchLayout.isEnabled = false
                updateSaveEnabled()
                clearCheckError()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        binding.repository.addTextChangedListener(watcher)
        binding.username.addTextChangedListener(watcher)
        binding.credential.addTextChangedListener(watcher)
        val passwordWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSaveEnabled()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        binding.encryptionPassword.addTextChangedListener(passwordWatcher)
        binding.confirmPassword.addTextChangedListener(passwordWatcher)
        binding.testConnection.setOnClickListener { testConnection() }
        binding.cancelCheck.setOnClickListener { cancelCheck(showCancelled = true) }
        binding.branch.setOnItemClickListener { _, _, position, _ ->
            if (position == remoteBranches.size) {
                showCreateBranchDialog()
            } else {
                updateSaveEnabled()
            }
        }
        binding.save.setOnClickListener { save() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        saveProgressDialog?.dismiss()
        saveProgressDialog = null
        super.onDestroy()
    }

    private fun candidate(branch: String = binding.branch.text.toString()): GitBackupConfig {
        val previous = saved
        val repositoryUrl = GitBackupConfigValidator.validateHttpsUrl(binding.repository.text.toString())
        val enteredCredential = binding.credential.text?.toString().orEmpty()
        return GitBackupConfig(
            repositoryUrl,
            binding.username.text.toString().trim(),
            branch,
            enteredCredential.ifEmpty {
                previous?.credential.orEmpty().takeIf { previous?.repositoryUrl == repositoryUrl }.orEmpty()
            },
            binding.encryptionPassword.text?.toString().orEmpty()
                .ifEmpty { previous?.encryptionPassword.orEmpty() },
        )
    }

    private fun testConnection() {
        val config = runCatching { candidate("main") }.getOrElse {
            binding.repositoryLayout.error = getString(R.string.git_invalid_configuration)
            return
        }
        binding.repositoryLayout.error = null
        clearCheckError()
        testedConnection = null
        setChecking(true, R.string.git_checking, cancellable = true)
        checkJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    GitBackupRepository(cacheDir.resolve("git-backup-test")).apply { clearCache() }
                        .listBranches(config)
                }
            }
            if (!isActive) return@launch
            checkJob = null
            setChecking(false)
            result.onSuccess { branches ->
                remoteBranches = branches
                val choices = branches + getString(R.string.git_create_new_branch)
                binding.branch.setSimpleItems(choices.toTypedArray())
                val selected = saved?.branch?.takeIf(branches::contains) ?: branches.firstOrNull()
                binding.branch.setText(selected.orEmpty(), false)
                binding.branchLayout.isEnabled = true
                testedConnection = config.repositoryUrl to config.username
                updateSaveEnabled()
            }.onFailure {
                Logs.w(it)
                showCheckError(
                    getString(
                        R.string.git_connection_failed,
                        getString(gitConnectionErrorMessage(GitBackupErrorClassifier.classify(it))),
                    ),
                )
            }
        }
    }

    private fun save() {
        val config = runCatching { candidate(GitBackupConfigValidator.validateBranch(binding.branch.text.toString())) }
            .getOrElse {
                binding.branchLayout.error = getString(R.string.git_invalid_branch)
                return
            }
        if (testedConnection != config.repositoryUrl to config.username) return
        val newPassword = binding.encryptionPassword.text?.toString().orEmpty()
        val confirmation = binding.confirmPassword.text?.toString().orEmpty()
        if (saved == null && newPassword.isEmpty()) {
            binding.encryptionPasswordLayout.error = getString(R.string.git_encryption_password_required)
            return
        }
        if (newPassword.isNotEmpty() && newPassword != confirmation) {
            binding.confirmPasswordLayout.error = getString(R.string.git_passwords_do_not_match)
            return
        }
        binding.branchLayout.error = null
        binding.encryptionPasswordLayout.error = null
        binding.confirmPasswordLayout.error = null
        clearCheckError()
        setSaveEnabled(false)
        showSaveProgress()
        checkJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    GitBackupRepository(cacheDir.resolve("git-backup-repository")).ensureBranch(config)
                }
            }
            if (!isActive) return@launch
            checkJob = null
            dismissSaveProgress()
            result.onSuccess {
                store.save(config)
                setResult(RESULT_OK)
                finish()
            }.onFailure {
                Logs.w(it)
                updateSaveEnabled()
                showCheckError(
                    getString(
                        R.string.git_branch_save_failed_detail,
                        getString(gitConnectionErrorMessage(GitBackupErrorClassifier.classify(it))),
                    ),
                )
            }
        }
    }

    private fun showSaveProgress() {
        val progress = LayoutProgressBinding.inflate(layoutInflater)
        progress.content.setText(R.string.git_creating_branch)
        saveProgressDialog = MaterialAlertDialogBuilder(this)
            .setView(progress.root)
            .setCancelable(false)
            .create()
            .also {
                it.setCanceledOnTouchOutside(false)
                it.show()
            }
    }

    private fun dismissSaveProgress() {
        saveProgressDialog?.dismiss()
        saveProgressDialog = null
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

    private fun showCreateBranchDialog() {
        val view = layoutInflater.inflate(R.layout.layout_git_branch_name, null)
        val input = view.findViewById<EditText>(R.id.branch_name)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.git_create_new_branch)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                binding.branch.setText(remoteBranches.firstOrNull().orEmpty(), false)
                updateSaveEnabled()
            }
            .create()
        dialog.setOnCancelListener {
            binding.branch.setText(remoteBranches.firstOrNull().orEmpty(), false)
            updateSaveEnabled()
        }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val branch = runCatching {
                    GitBackupConfigValidator.validateBranch(input.text.toString())
                }.getOrElse {
                    input.error = getString(R.string.git_invalid_branch)
                    return@setOnClickListener
                }
                binding.branch.setText(branch, false)
                binding.branchLayout.error = null
                updateSaveEnabled()
                dialog.dismiss()
            }
            input.requestFocus()
        }
        dialog.show()
    }

    private fun cancelCheck(showCancelled: Boolean) {
        checkJob?.cancel()
        checkJob = null
        setChecking(false)
        if (showCancelled) showCheckError(getString(R.string.git_check_cancelled))
    }

    private fun setChecking(checking: Boolean, text: Int = R.string.git_checking, cancellable: Boolean = true) {
        binding.checking.isVisible = checking
        binding.testConnection.isEnabled = !checking
        binding.cancelCheck.isVisible = checking && cancellable
        if (checking) {
            binding.checkingText.setText(text)
            setSaveEnabled(false)
        }
    }

    private fun showCheckError(message: String) {
        binding.checkError.text = message
        binding.checkError.isVisible = true
    }

    private fun clearCheckError() {
        binding.checkError.text = null
        binding.checkError.isVisible = false
    }

    private fun updateSaveEnabled() {
        val branchValid = runCatching {
            GitBackupConfigValidator.validateBranch(binding.branch.text.toString())
        }.isSuccess
        val newPassword = binding.encryptionPassword.text?.toString().orEmpty()
        val passwordAvailable = newPassword.isNotEmpty() || saved?.encryptionPassword?.isNotEmpty() == true
        val passwordConfirmed = newPassword.isEmpty() ||
            newPassword == binding.confirmPassword.text?.toString().orEmpty()
        setSaveEnabled(
            testedConnection != null && branchValid && passwordAvailable && passwordConfirmed &&
                checkJob == null,
        )
    }

    private fun setSaveEnabled(enabled: Boolean) {
        binding.save.isEnabled = enabled
        binding.save.isClickable = enabled
        binding.save.alpha = if (enabled) 1f else 0.38f
    }
}
