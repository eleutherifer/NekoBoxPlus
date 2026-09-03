package io.nekohasekai.sagernet.ui.compose

import android.util.Patterns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.R

data class AboutPluginItem(
    val packageName: String,
    val title: String,
    val version: String,
)

data class AboutScreenData(
    val appVersion: String,
    val singBoxVersion: String,
    val moduleVersions: String,
    val plugins: List<AboutPluginItem>,
    val license: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    data: AboutScreenData?,
    showBatteryOptimization: Boolean,
    onOpenDrawer: () -> Unit,
    onPluginClick: (String) -> Unit,
    onBatteryOptimizationClick: () -> Unit,
    onProjectClick: () -> Unit,
    onDocumentationClick: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_about)) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            painterResource(R.drawable.ic_navigation_menu),
                            contentDescription = stringResource(R.string.menu_about),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                AboutCard(Modifier.padding(horizontal = 8.dp)) {
                    Text(
                        text = stringResource(R.string.app_name_long),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.app_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            if (data == null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                item {
                    AboutCard(Modifier.padding(horizontal = 8.dp)) {
                        AboutAction(
                            iconRes = R.drawable.ic_baseline_update_24,
                            title = stringResource(R.string.app_version),
                            subtitle = data.appVersion,
                        )
                        AboutAction(
                            iconRes = R.drawable.ic_baseline_layers_24,
                            title = stringResource(R.string.version_x, "sing-box"),
                            subtitle = data.singBoxVersion,
                        )
                        AboutAction(
                            iconRes = R.drawable.ic_baseline_layers_24,
                            title = stringResource(R.string.version_other_modules),
                            subtitle = data.moduleVersions,
                        )
                        data.plugins.forEach { plugin ->
                            AboutAction(
                                iconRes = R.drawable.ic_baseline_nfc_24,
                                title = plugin.title,
                                subtitle = plugin.version,
                                onClick = { onPluginClick(plugin.packageName) },
                            )
                        }
                        if (showBatteryOptimization) {
                            AboutAction(
                                iconRes = R.drawable.ic_baseline_running_with_errors_24,
                                title = stringResource(R.string.ignore_battery_optimizations),
                                subtitle = stringResource(R.string.ignore_battery_optimizations_sum),
                                onClick = onBatteryOptimizationClick,
                            )
                        }
                    }
                }
                item {
                    AboutCard(Modifier.padding(horizontal = 8.dp)) {
                        Text(
                            text = stringResource(R.string.project),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                        )
                        AboutAction(
                            iconRes = R.drawable.ic_baseline_sanitizer_24,
                            title = stringResource(R.string.github),
                            onClick = onProjectClick,
                        )
                        AboutAction(
                            iconRes = R.drawable.ic_device_data_usage,
                            title = stringResource(R.string.document),
                            onClick = onDocumentationClick,
                        )
                    }
                }
                item {
                    AboutCard(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.license),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        val linkColor = MaterialTheme.colorScheme.primary
                        val linkedLicense = remember(data.license, linkColor) {
                            linkify(data.license, linkColor)
                        }
                        SelectionContainer {
                            Text(
                                text = linkedLicense,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun AboutAction(
    iconRes: Int,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.tvFocusTarget().clickable(onClick = onClick)
                else Modifier,
            )
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.padding(start = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private data class LinkMatch(val start: Int, val end: Int, val target: String)

private fun linkify(text: String, color: Color): AnnotatedString {
    val links = buildList {
        val webMatcher = Patterns.WEB_URL.matcher(text)
        while (webMatcher.find()) {
            val value = webMatcher.group()
            val target = if (value.contains("://")) value else "https://$value"
            add(LinkMatch(webMatcher.start(), webMatcher.end(), target))
        }
        val emailMatcher = Patterns.EMAIL_ADDRESS.matcher(text)
        while (emailMatcher.find()) {
            add(
                LinkMatch(
                    emailMatcher.start(),
                    emailMatcher.end(),
                    "mailto:${emailMatcher.group()}",
                ),
            )
        }
    }.sortedBy { it.start }

    return AnnotatedString.Builder(text).apply {
        var lastEnd = -1
        links.forEach { link ->
            if (link.start < lastEnd) return@forEach
            addLink(LinkAnnotation.Url(link.target), link.start, link.end)
            addStyle(
                SpanStyle(color = color, textDecoration = TextDecoration.Underline),
                link.start,
                link.end,
            )
            lastEnd = link.end
        }
    }.toAnnotatedString()
}
