package io.nekohasekai.sagernet.ui.compose

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.R
import java.text.Normalizer
import java.util.Locale

internal data class SettingsSearchResult(
    val key: String,
    val groupId: String,
    val title: String,
    val summary: String,
    val iconRes: Int,
    val enabled: Boolean,
)

@Composable
internal fun SettingsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1F), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.settings_search_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = remember { MutableInteractionSource() },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onClose()
                            true
                        } else if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
                        ) {
                            onClose()
                            true
                        } else false
                    },
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.tvFocusTarget().focusRequester(closeFocusRequester),
        ) {
            Icon(
                painterResource(R.drawable.ic_navigation_close),
                contentDescription = stringResource(android.R.string.cancel),
            )
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
internal fun SettingsSearchResultsScreen(
    query: String,
    results: List<SettingsSearchResult>,
    onResultClick: (SettingsSearchResult) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = SettingsSearchResult::key) { result ->
            val alpha = if (result.enabled) 1F else 0.38F
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onResultClick(result) }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) {
                    if (result.iconRes != 0) {
                        Icon(
                            painterResource(result.iconRes),
                            null,
                            Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        )
                    }
                }
                Column(Modifier.weight(1F)) {
                    Text(
                        highlighted(result.title, query),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 21.5.sp,
                            letterSpacing = 0.15.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    )
                    if (result.summary.isNotBlank()) {
                        Text(
                            highlighted(result.summary, query),
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun highlighted(text: String, query: String) = buildAnnotatedString {
    val index = text.normalized().indexOf(query.normalized())
    if (index < 0 || query.isBlank()) {
        append(text)
    } else {
        append(text.substring(0, index))
        withStyle(SpanStyle(background = MaterialTheme.colorScheme.secondaryContainer)) {
            append(text.substring(index, (index + query.length).coerceAtMost(text.length)))
        }
        append(text.substring((index + query.length).coerceAtMost(text.length)))
    }
}

private fun String.normalized(): String =
    Normalizer.normalize(lowercase(Locale.getDefault()), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
