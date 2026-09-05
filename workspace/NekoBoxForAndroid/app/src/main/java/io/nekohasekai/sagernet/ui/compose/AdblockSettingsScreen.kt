package io.nekohasekai.sagernet.ui.compose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R

internal data class AdblockSettingsState(
    val enabled: Boolean,
    val stats: String,
    val dnsFiltering: Boolean,
    val cnameUncloaking: Boolean,
    val httpFiltering: Boolean,
    val httpsFiltering: Boolean,
    val httpsFingerprint: String,
    val httpsFingerprintLabel: String,
    val httpsCronet: Boolean,
    val skipEvCerts: Boolean,
    val systemWideFilter: Boolean,
    val includedAppsSummary: String,
    val mixedLanFiltering: Boolean,
    val mixedLanFilteringAvailable: Boolean,
    val bundledFiltersSummary: String,
    val customFiltersSummary: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdblockSettingsScreen(
    state: AdblockSettingsState,
    reloadPrompt: Int,
    onOpenDrawer: () -> Unit,
    onToggle: (AdblockSetting, Boolean) -> Unit,
    onSaveCertificate: () -> Unit,
    onFingerprint: () -> Unit,
    onIncludedApps: () -> Unit,
    onBundledFilters: () -> Unit,
    onCustomFilters: () -> Unit,
    onCustomRules: () -> Unit,
    onApplyReload: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val reloadMessage = stringResource(R.string.need_reload)
    val applyLabel = stringResource(R.string.apply)
    LaunchedEffect(reloadPrompt) {
        if (reloadPrompt > 0 && snackbar.showSnackbar(
                reloadMessage,
                actionLabel = applyLabel,
                duration = SnackbarDuration.Long,
            ) == SnackbarResult.ActionPerformed
        ) onApplyReload()
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.adblock)) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            painterResource(R.drawable.ic_navigation_menu),
                            contentDescription = stringResource(R.string.abc_action_bar_up_description),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
        ) {
            item { Category(R.string.adblock) }
            item {
                SwitchRow(R.drawable.ic_baseline_filter_list_24, R.string.adblock_enable, state.stats,
                    state.enabled) { onToggle(AdblockSetting.ENABLED, it) }
            }
            if (state.enabled) {
                item { Category(R.string.adblock_filtering) }
                item { SwitchRow(R.drawable.ic_baseline_dns_24, R.string.adblock_dns_filtering, null,
                    state.dnsFiltering) { onToggle(AdblockSetting.DNS, it) } }
                item { SwitchRow(R.drawable.ic_baseline_link_24, R.string.adblock_cname_uncloaking, null,
                    state.cnameUncloaking, state.dnsFiltering) { onToggle(AdblockSetting.CNAME, it) } }
                item { SwitchRow(R.drawable.ic_baseline_http_24, R.string.adblock_http_filtering, null,
                    state.httpFiltering) { onToggle(AdblockSetting.HTTP, it) } }
                item { SwitchRow(R.drawable.ic_baseline_lock_24, R.string.adblock_https_filtering,
                    stringResource(R.string.adblock_https_filtering_summary), state.httpsFiltering) {
                    onToggle(AdblockSetting.HTTPS, it)
                } }
                item { ActionRow(R.drawable.ic_baseline_save_24, R.string.adblock_save_ca_certificate,
                    stringResource(R.string.adblock_save_ca_certificate_summary), state.httpsFiltering,
                    onSaveCertificate) }
                item { ActionRow(R.drawable.ic_baseline_fingerprint_24, R.string.adblock_https_fingerprint,
                    state.httpsFingerprintLabel, state.httpsFiltering && !state.httpsCronet, onFingerprint) }
                item { SwitchRow(R.drawable.ic_baseline_lock_24, R.string.adblock_https_cronet,
                    stringResource(R.string.adblock_https_cronet_summary), state.httpsCronet,
                    true) { onToggle(AdblockSetting.CRONET, it) } }
                item { SwitchRow(R.drawable.ic_baseline_security_24, R.string.adblock_skip_ev_certs, null,
                    state.skipEvCerts, state.httpsFiltering) { onToggle(AdblockSetting.SKIP_EV, it) } }

                item { Category(R.string.adblock_per_app_filtering) }
                item { SwitchRow(R.drawable.ic_baseline_vpn_key_24, R.string.adblock_system_wide_filter,
                    stringResource(R.string.adblock_system_wide_filter_summary), state.systemWideFilter) {
                    onToggle(AdblockSetting.SYSTEM_WIDE, it)
                } }
                item { ActionRow(R.drawable.ic_navigation_apps, R.string.adblock_included_apps,
                    state.includedAppsSummary, true, onIncludedApps) }
                item { SwitchRow(R.drawable.ic_baseline_lock_24, R.string.adblock_mixed_lan_filtering,
                    stringResource(R.string.adblock_mixed_lan_filtering_summary), state.mixedLanFiltering,
                    state.mixedLanFilteringAvailable) { onToggle(AdblockSetting.MIXED_LAN, it) } }

                item { Category(R.string.adblock_filter_lists) }
                item { ActionRow(R.drawable.ic_baseline_rule_folder_24, R.string.adblock_bundled_filters,
                    state.bundledFiltersSummary, true, onBundledFilters) }
                item { ActionRow(R.drawable.ic_baseline_download_24, R.string.adblock_custom_filters,
                    state.customFiltersSummary, true, onCustomFilters) }
                item { ActionRow(R.drawable.baseline_wrap_text_24, R.string.adblock_custom_rules,
                    null, true, onCustomRules) }
            }
        }
    }
}

internal enum class AdblockSetting { ENABLED, DNS, CNAME, HTTP, HTTPS, CRONET, SKIP_EV, SYSTEM_WIDE, MIXED_LAN }

@Composable
private fun Category(@StringRes title: Int) {
    Text(
        stringResource(title),
        modifier = Modifier.fillMaxWidth().padding(start = 72.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SwitchRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    PreferenceRow(icon, title, summary, enabled, { onChecked(!checked) }) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ActionRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    summary: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) = PreferenceRow(icon, title, summary, enabled, onClick, null)

@Composable
private fun PreferenceRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    summary: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)?,
) {
    val alpha = if (enabled) 1F else 0.38F
    Row(
        modifier = Modifier.fillMaxWidth().tvFocusTarget(enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (summary.isNullOrBlank()) 3.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.padding(end = 32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        Column(Modifier.weight(1F)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        content?.invoke()
    }
}
