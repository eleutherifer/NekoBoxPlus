package io.nekohasekai.sagernet.ui.compose

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.CustomTheme

internal data class CustomThemeColorRow(
    val spec: CustomTheme.ColorSpec,
    val color: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomThemeScreen(
    dynamicColors: Boolean,
    headerPrimary: Boolean,
    statsBarPrimary: Boolean,
    colors: List<CustomThemeColorRow>,
    onClose: () -> Unit,
    onApply: () -> Unit,
    onCopyTheme: () -> Unit,
    onShareTheme: () -> Unit,
    onImportTheme: () -> Unit,
    onDynamicColorsChanged: (Boolean) -> Unit,
    onHeaderPrimaryChanged: (Boolean) -> Unit,
    onStatsBarPrimaryChanged: (Boolean) -> Unit,
    onColorChanged: (CustomTheme.ColorSpec, Int) -> Unit,
    showDiscardDialog: Boolean,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
) {
    var editedColor by remember { mutableStateOf<CustomThemeColorRow?>(null) }
    BackHandler(onBack = onClose)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.configure_custom_theme)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painterResource(R.drawable.ic_navigation_close),
                            contentDescription = stringResource(R.string.mal_close),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onApply) {
                        Icon(
                            painterResource(R.drawable.ic_baseline_save_24),
                            contentDescription = stringResource(R.string.apply),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "base-header") {
                ThemeSectionHeader(R.string.custom_theme_base_settings)
            }
            item(key = "copy") {
                ThemeActionRow(R.string.copy_from_another_theme, onCopyTheme)
            }
            item(key = "share") {
                ThemeActionRow(R.string.share_custom_theme, onShareTheme)
            }
            item(key = "import") {
                ThemeActionRow(R.string.import_custom_theme, onImportTheme)
            }
            item(key = "dynamic") {
                ThemeSwitchRow(R.string.use_dynamic_colors, dynamicColors, onDynamicColorsChanged)
            }
            item(key = "header") {
                ThemeSwitchRow(
                    R.string.apply_primary_color_to_app_header,
                    headerPrimary,
                    onHeaderPrimaryChanged,
                )
            }
            item(key = "stats") {
                ThemeSwitchRow(
                    R.string.apply_primary_color_to_stats_bar,
                    statsBarPrimary,
                    onStatsBarPrimaryChanged,
                )
            }
            item(key = "colors-header") {
                ThemeSectionHeader(R.string.custom_theme_colors)
            }
            items(colors, key = { it.spec.key }) { row ->
                ThemeColorRow(row) { editedColor = row }
            }
        }
    }
    editedColor?.let { row ->
        CustomThemeColorPickerDialog(
            spec = row.spec,
            originalColor = row.color,
            onDismiss = { editedColor = null },
            onConfirm = { color ->
                editedColor = null
                onColorChanged(row.spec, color)
            },
        )
    }
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = onDismissDiscard,
            title = { Text(stringResource(R.string.discard)) },
            text = { Text(stringResource(R.string.discard_custom_theme_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmDiscard) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissDiscard) { Text(stringResource(R.string.no)) }
            },
        )
    }
}

@Composable
private fun ThemeSectionHeader(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun ThemeActionRow(@StringRes title: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .tvFocusTarget()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(title), fontSize = 16.sp)
    }
}

@Composable
private fun ThemeSwitchRow(
    @StringRes title: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .tvFocusTarget()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(title),
            fontSize = 16.sp,
            modifier = Modifier.weight(1F),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ThemeColorRow(row: CustomThemeColorRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .tvFocusTarget()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1F),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(row.spec.titleRes),
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
            )
            Text(
                text = stringResource(row.spec.descriptionRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 16.sp,
            )
        }
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = Color(row.color),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Box(Modifier.fillMaxSize())
        }
    }
}
