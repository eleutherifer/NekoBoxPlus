package io.nekohasekai.sagernet.ui.compose

import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import io.nekohasekai.sagernet.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    showNoActiveConnections: Boolean,
    showSetUrl: Boolean,
    showSetUrlDialog: Boolean,
    panelUrlDraft: String,
    showCleanupConfirmation: Boolean,
    onOpenDrawer: () -> Unit,
    onSetUrl: () -> Unit,
    onPanelUrlChanged: (String) -> Unit,
    onDismissSetUrl: () -> Unit,
    onConfirmSetUrl: () -> Unit,
    onCleanup: () -> Unit,
    onDismissCleanup: () -> Unit,
    onConfirmCleanup: () -> Unit,
    onClose: () -> Unit,
    onContainerReady: (FrameLayout) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_dashboard)) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            painter = painterResource(R.drawable.ic_navigation_menu),
                            contentDescription = stringResource(R.string.menu_dashboard),
                        )
                    }
                },
                actions = {
                    DashboardMenu(showSetUrl, onSetUrl, onCleanup, onClose)
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(
                factory = { context -> FrameLayout(context).also(onContainerReady) },
                update = onContainerReady,
                modifier = Modifier.fillMaxSize(),
            )
            if (showNoActiveConnections) {
                Text(
                    text = stringResource(R.string.no_active_connections),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }
        }
    }

    if (showSetUrlDialog) {
        DashboardUrlDialog(
            value = panelUrlDraft,
            onValueChange = onPanelUrlChanged,
            onDismiss = onDismissSetUrl,
            onConfirm = onConfirmSetUrl,
        )
    }

    if (showCleanupConfirmation) {
        DashboardDialogSurface(onDismiss = onDismissCleanup) {
            Column(modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) {
                Text(
                    stringResource(R.string.webview_cleanup_confirmation),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 16.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismissCleanup) { Text(stringResource(R.string.no)) }
                    TextButton(onClick = onConfirmCleanup) { Text(stringResource(R.string.yes)) }
                }
            }
        }
    }
}

@Composable
private fun DashboardUrlDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    DashboardDialogSurface(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) {
            Text(
                stringResource(R.string.set_panel_url),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .padding(vertical = 10.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DashboardDialogSurface(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            content = content,
        )
    }
}

@Composable
private fun DashboardMenu(
    showSetUrl: Boolean,
    onSetUrl: () -> Unit,
    onCleanup: () -> Unit,
    onClose: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_more_vert_24),
                contentDescription = stringResource(R.string.toolbar_more_actions),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (showSetUrl) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.set_panel_url)) },
                    onClick = { expanded = false; onSetUrl() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.webview_cleanup)) },
                onClick = { expanded = false; onCleanup() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mal_close)) },
                onClick = { expanded = false; onClose() },
            )
        }
    }
}
