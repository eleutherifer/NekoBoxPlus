package io.nekohasekai.sagernet.ui.compose

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R

internal data class AdblockCustomFilterEditorState(
    val url: String = "",
    val trust: Boolean = false,
    val enabled: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdblockCustomFilterEditorScreen(
    @StringRes titleRes: Int,
    initialState: AdblockCustomFilterEditorState,
    onClose: () -> Unit,
    onSave: (AdblockCustomFilterEditorState) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialState.url) }
    var trust by rememberSaveable { mutableStateOf(initialState.trust) }
    var enabled by rememberSaveable { mutableStateOf(initialState.enabled) }

    BackHandler(onBack = onClose)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AdblockEditorTopBar(
                titleRes = titleRes,
                onClose = onClose,
                onSave = {
                    onSave(AdblockCustomFilterEditorState(url, trust, enabled))
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
        ) {
            UnderlinedUrlField(
                value = url,
                onValueChange = { url = it },
            )
            AdblockCheckboxRow(
                text = stringResource(R.string.adblock_enable_insecure_rules),
                checked = trust,
                onCheckedChange = { trust = it },
            )
            AdblockCheckboxRow(
                text = stringResource(R.string.adblock_enable_this_filter_on_add),
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdblockCustomRulesEditorScreen(
    initialRules: String,
    onClose: () -> Unit,
    onSave: (String) -> Unit,
) {
    var rules by rememberSaveable { mutableStateOf(initialRules) }

    BackHandler(onBack = onClose)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AdblockEditorTopBar(
                titleRes = R.string.adblock_custom_rules,
                onClose = onClose,
                onSave = { onSave(rules) },
            )
        },
    ) { contentPadding ->
        BasicTextField(
            value = rules,
            onValueChange = { rules = it },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily(Font(R.font.jetbrains_mono)),
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
        )
    }
}

@Composable
private fun UnderlinedUrlField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(45.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            interactionSource = interactionSource,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            decorationBox = { innerTextField ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.adblock_filter_url),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        HorizontalDivider(
            thickness = if (focused) 2.dp else 1.dp,
            color = if (focused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun AdblockCheckboxRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .toggleable(checked, onValueChange = onCheckedChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdblockEditorTopBar(
    @StringRes titleRes: Int,
    onClose: () -> Unit,
    onSave: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(titleRes)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_navigation_close),
                    contentDescription = stringResource(R.string.mal_close),
                )
            }
        },
        actions = {
            IconButton(onClick = onSave) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_save_24),
                    contentDescription = stringResource(R.string.save),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}
