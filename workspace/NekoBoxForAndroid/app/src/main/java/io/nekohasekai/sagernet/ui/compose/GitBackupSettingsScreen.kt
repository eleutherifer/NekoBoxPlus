package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R

internal data class GitBackupSettingsUiState(
    val repository: String = "",
    val username: String = "",
    val credential: String = "",
    val branch: String = "",
    val encryptionPassword: String = "",
    val confirmPassword: String = "",
    val branches: List<String> = emptyList(),
    val branchEnabled: Boolean = false,
    val checking: Boolean = false,
    val checkCancellable: Boolean = true,
    val checkingText: Int = R.string.git_checking,
    val checkError: String = "",
    val repositoryError: String = "",
    val branchError: String = "",
    val encryptionError: String = "",
    val confirmError: String = "",
    val saveEnabled: Boolean = false,
    val saving: Boolean = false,
    val showCreateBranch: Boolean = false,
    val branchName: String = "",
    val branchNameError: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitBackupSettingsScreen(
    state: GitBackupSettingsUiState,
    onClose: () -> Unit,
    onRepositoryChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onCredentialChange: (String) -> Unit,
    onEncryptionPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onCancelCheck: () -> Unit,
    onBranchSelected: (Int) -> Unit,
    onSave: () -> Unit,
    onBranchNameChange: (String) -> Unit,
    onConfirmCreateBranch: () -> Unit,
    onDismissCreateBranch: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.git_backup_settings)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painterResource(R.drawable.ic_navigation_close),
                            contentDescription = stringResource(R.string.mal_close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 32.dp),
        ) {
            GitTextField(
                value = state.repository,
                onValueChange = onRepositoryChange,
                label = R.string.git_repository_url,
                icon = R.drawable.ic_file_cloud_queue,
                error = state.repositoryError,
                keyboardType = KeyboardType.Uri,
            )
            GitTextField(
                value = state.username,
                onValueChange = onUsernameChange,
                label = R.string.username_opt,
                icon = R.drawable.ic_baseline_person_24,
            )
            GitTextField(
                value = state.credential,
                onValueChange = onCredentialChange,
                label = R.string.git_password_or_token,
                icon = R.drawable.ic_baseline_vpn_key_24,
                password = true,
                helper = stringResource(R.string.git_secret_keep_hint),
            )
            Button(onClick = onTestConnection, enabled = !state.checking, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_baseline_cast_connected_24), null)
                Text(stringResource(R.string.git_test_connection), Modifier.padding(start = 8.dp))
            }
            if (state.checking) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(
                        stringResource(state.checkingText),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .weight(1f),
                    )
                    if (state.checkCancellable) {
                        TextButton(onClick = onCancelCheck) { Text(stringResource(android.R.string.cancel)) }
                    }
                }
            }
            if (state.checkError.isNotEmpty()) {
                Text(
                    state.checkError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            BranchField(state, onBranchSelected)
            GitTextField(
                value = state.encryptionPassword,
                onValueChange = onEncryptionPasswordChange,
                label = R.string.git_encryption_password,
                icon = R.drawable.ic_action_lock,
                password = true,
                helper = stringResource(R.string.git_secret_keep_hint),
                error = state.encryptionError,
            )
            GitTextField(
                value = state.confirmPassword,
                onValueChange = onConfirmPasswordChange,
                label = R.string.git_confirm_encryption_password,
                password = true,
                error = state.confirmError,
            )
            Button(onClick = onSave, enabled = state.saveEnabled, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_baseline_save_24), null)
                Text(stringResource(R.string.save), Modifier.padding(start = 8.dp))
            }
        }
    }
    if (state.showCreateBranch) {
        AlertDialog(
            onDismissRequest = onDismissCreateBranch,
            title = { Text(stringResource(R.string.git_create_new_branch)) },
            text = {
                TextField(
                    value = state.branchName,
                    onValueChange = onBranchNameChange,
                    label = { Text(stringResource(R.string.git_branch_name)) },
                    isError = state.branchNameError.isNotEmpty(),
                    supportingText = state.branchNameError.takeIf(String::isNotEmpty)?.let { error ->
                        { Text(error) }
                    },
                    singleLine = true,
                )
            },
            dismissButton = {
                TextButton(onClick = onDismissCreateBranch) { Text(stringResource(android.R.string.cancel)) }
            },
            confirmButton = {
                TextButton(onClick = onConfirmCreateBranch) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
    if (state.saving) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Text(stringResource(R.string.git_creating_branch), Modifier.padding(start = 16.dp))
                }
            },
        )
    }
}

@Composable
private fun GitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    icon: Int? = null,
    error: String = "",
    helper: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var visible by remember { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        leadingIcon = icon?.let { { Icon(painterResource(it), null) } },
        trailingIcon = if (password) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        painterResource(if (visible) R.drawable.ic_baseline_visibility_off_24 else R.drawable.ic_baseline_visibility_24),
                        contentDescription = stringResource(if (visible) R.string.hide_password else R.string.show_password),
                    )
                }
            }
        } else null,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        isError = error.isNotEmpty(),
        supportingText = (error.ifEmpty { helper }).takeIf(String::isNotEmpty)?.let { text -> { Text(text) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchField(state: GitBackupSettingsUiState, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = state.branches + stringResource(R.string.git_create_new_branch)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (state.branchEnabled) expanded = !expanded },
    ) {
        TextField(
            value = state.branch,
            onValueChange = {},
            readOnly = true,
            enabled = state.branchEnabled,
            label = { Text(stringResource(R.string.git_branch)) },
            isError = state.branchError.isNotEmpty(),
            supportingText = state.branchError.takeIf(String::isNotEmpty)?.let { error -> { Text(error) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, state.branchEnabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelected(index)
                    },
                )
            }
        }
    }
}
