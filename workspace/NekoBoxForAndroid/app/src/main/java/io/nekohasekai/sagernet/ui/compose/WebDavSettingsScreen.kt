package io.nekohasekai.sagernet.ui.compose

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R

internal enum class WebDavField(
    @param:StringRes val titleRes: Int,
    @param:DrawableRes val iconRes: Int,
    val secret: Boolean = false,
) {
    Server(R.string.webdav_server, R.drawable.ic_file_cloud_queue),
    Username(R.string.webdav_username, R.drawable.ic_baseline_person_24),
    Password(R.string.webdav_password, R.drawable.ic_settings_password, secret = true),
    Path(R.string.webdav_path, R.drawable.ic_baseline_folder_open_24),
}

internal data class WebDavNotice(val id: Long, val message: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebDavSettingsScreen(
    server: String,
    username: String,
    password: String,
    path: String,
    editingField: WebDavField?,
    testing: Boolean,
    notice: WebDavNotice?,
    onClose: () -> Unit,
    onEdit: (WebDavField) -> Unit,
    onDismissEdit: () -> Unit,
    onSave: (WebDavField, String) -> Unit,
    onTest: () -> Unit,
    onNoticeShown: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(notice?.id) {
        if (notice != null) {
            snackbarHostState.showSnackbar(notice.message)
            onNoticeShown()
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.webdav_settings)) },
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
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .navigationBarsPadding(),
        ) {
            item {
                WebDavPreferenceRow(
                    field = WebDavField.Server,
                    summary = server.ifBlank { stringResource(R.string.not_set) },
                    onClick = onEdit,
                )
            }
            item { HorizontalDivider(Modifier.padding(start = 72.dp)) }
            item {
                WebDavPreferenceRow(
                    field = WebDavField.Username,
                    summary = username.ifBlank { stringResource(R.string.not_set) },
                    onClick = onEdit,
                )
            }
            item { HorizontalDivider(Modifier.padding(start = 72.dp)) }
            item {
                WebDavPreferenceRow(
                    field = WebDavField.Password,
                    summary = if (password.isBlank()) {
                        stringResource(R.string.not_set)
                    } else {
                        "\u2022".repeat(password.length)
                    },
                    onClick = onEdit,
                )
            }
            item { HorizontalDivider(Modifier.padding(start = 72.dp)) }
            item {
                WebDavPreferenceRow(
                    field = WebDavField.Path,
                    summary = path.ifBlank { stringResource(R.string.not_set) },
                    onClick = onEdit,
                )
            }
            item { HorizontalDivider(Modifier.padding(start = 72.dp)) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !testing, onClick = onTest)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_baseline_cast_connected_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(24.dp))
                    Text(
                        text = stringResource(R.string.webdav_test),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
    if (editingField != null) {
        val currentValue = when (editingField) {
            WebDavField.Server -> server
            WebDavField.Username -> username
            WebDavField.Password -> password
            WebDavField.Path -> path
        }
        WebDavEditDialog(
            field = editingField,
            initialValue = currentValue,
            onDismiss = onDismissEdit,
            onSave = { onSave(editingField, it) },
        )
    }
}

@Composable
private fun WebDavPreferenceRow(
    field: WebDavField,
    summary: String,
    onClick: (WebDavField) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(field) }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(field.iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(field.titleRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WebDavEditDialog(
    field: WebDavField,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(field) {
        mutableStateOf(TextFieldValue(initialValue, TextRange(initialValue.length)))
    }
    var passwordVisible by remember(field) { mutableStateOf(false) }
    fun save() {
        onSave(value.text)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(field.titleRes)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().imePadding(),
                singleLine = true,
                visualTransformation = if (field.secret && !passwordVisible) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        field.secret -> KeyboardType.Password
                        field == WebDavField.Server -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    },
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
                trailingIcon = if (field.secret) {
                    {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                painterResource(
                                    if (passwordVisible) {
                                        R.drawable.ic_baseline_visibility_off_24
                                    } else {
                                        R.drawable.ic_baseline_visibility_24
                                    },
                                ),
                                contentDescription = stringResource(
                                    if (passwordVisible) {
                                        R.string.hide_password
                                    } else {
                                        R.string.show_password
                                    },
                                ),
                            )
                        }
                    }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(onClick = ::save) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
