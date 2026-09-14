package io.nekohasekai.sagernet.ui.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.nekohasekai.sagernet.R

internal data class ProfileSettingsActions(
    val links: Boolean = false,
    val standardLinks: Boolean = false,
    val configuration: Boolean = false,
    val move: Boolean = false,
    val shortcut: Boolean = false,
)

private enum class ProfileSettingsSubmenu(@param:StringRes val title: Int) {
    Share(R.string.share_server),
    Qr(R.string.share_qr_nfc),
    Clipboard(R.string.action_export_clipboard),
    Configuration(R.string.menu_configuration),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileSettingsScaffold(
    ready: Boolean,
    actions: ProfileSettingsActions,
    onClose: () -> Unit,
    onAction: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    var submenu by remember { mutableStateOf<ProfileSettingsSubmenu?>(null) }
    fun dismissMenu() {
        overflowExpanded = false
        submenu = null
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_config)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painterResource(R.drawable.ic_navigation_close),
                            contentDescription = stringResource(android.R.string.cancel),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onAction(R.id.action_delete) }) {
                        Icon(
                            painterResource(R.drawable.ic_action_delete),
                            contentDescription = stringResource(R.string.delete),
                        )
                    }
                    IconButton(onClick = { onAction(R.id.action_apply) }, enabled = ready) {
                        Icon(
                            painterResource(R.drawable.ic_action_done),
                            contentDescription = stringResource(R.string.apply),
                        )
                    }
                    IconButton(onClick = {
                        submenu = null
                        overflowExpanded = true
                    }, enabled = ready) {
                        Icon(
                            painterResource(R.drawable.ic_baseline_more_vert_24),
                            contentDescription = stringResource(R.string.toolbar_more_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { dismissMenu() },
                    ) {
                        fun run(action: Int) {
                            dismissMenu()
                            onAction(action)
                        }
                        val currentSubmenu = submenu
                        if (currentSubmenu != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(currentSubmenu.title)) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.baseline_arrow_back_24),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    submenu = if (currentSubmenu == ProfileSettingsSubmenu.Share) {
                                        null
                                    } else {
                                        ProfileSettingsSubmenu.Share
                                    }
                                },
                            )
                        }
                        when (currentSubmenu) {
                            ProfileSettingsSubmenu.Share -> {
                                if (actions.links) {
                                    ProfileSettingsSubmenuItem(ProfileSettingsSubmenu.Qr) {
                                        submenu = ProfileSettingsSubmenu.Qr
                                    }
                                    ProfileSettingsSubmenuItem(ProfileSettingsSubmenu.Clipboard) {
                                        submenu = ProfileSettingsSubmenu.Clipboard
                                    }
                                }
                                if (actions.configuration) {
                                    ProfileSettingsSubmenuItem(ProfileSettingsSubmenu.Configuration) {
                                        submenu = ProfileSettingsSubmenu.Configuration
                                    }
                                }
                            }
                            ProfileSettingsSubmenu.Qr, ProfileSettingsSubmenu.Clipboard -> {
                                val qr = currentSubmenu == ProfileSettingsSubmenu.Qr
                                if (actions.standardLinks) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.standard)) },
                                        onClick = {
                                            run(if (qr) R.id.action_standard_qr else R.id.action_standard_clipboard)
                                        },
                                    )
                                }
                                if (actions.links) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sn_link)) },
                                        onClick = {
                                            run(if (qr) R.id.action_universal_qr else R.id.action_universal_clipboard)
                                        },
                                    )
                                }
                            }
                            ProfileSettingsSubmenu.Configuration -> {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_export_clipboard)) },
                                    onClick = { run(R.id.action_config_export_clipboard) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_export_file)) },
                                    onClick = { run(R.id.action_config_export_file) },
                                )
                            }
                            null -> {
                                if (actions.links || actions.configuration) {
                                    ProfileSettingsSubmenuItem(ProfileSettingsSubmenu.Share) {
                                        submenu = ProfileSettingsSubmenu.Share
                                    }
                                }
                                if (actions.shortcut) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.create_shortcut)) },
                                    onClick = { run(R.id.action_create_shortcut) },
                                )
                                if (actions.move) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.move)) },
                                    onClick = { run(R.id.action_move) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.custom_outbound_json)) },
                                    onClick = { run(R.id.action_custom_outbound_json) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.custom_config_json)) },
                                    onClick = { run(R.id.action_custom_config_json) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (ready) content() else CircularProgressIndicator()
        }
    }
}

@Composable
private fun ProfileSettingsSubmenuItem(
    submenu: ProfileSettingsSubmenu,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(submenu.title)) },
        trailingIcon = {
            Icon(
                painterResource(R.drawable.baseline_arrow_back_24),
                contentDescription = null,
                modifier = Modifier.rotate(180f),
            )
        },
        onClick = onClick,
    )
}
