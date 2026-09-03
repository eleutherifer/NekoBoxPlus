package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.database.DataStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupSettingsScreen(
    canDelete: Boolean,
    frontProxyName: String,
    landingProxyName: String,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onSelectFrontProxy: () -> Unit,
    onSelectLandingProxy: () -> Unit,
    onImportRouting: () -> Unit,
    onHwidChanged: (Boolean) -> Unit,
    onSpoofAppChanged: (Int) -> Unit,
    validateServerDns: (String) -> Boolean,
    validateAutoUpdateDelay: (String) -> Boolean,
) {
    val context = LocalContext.current
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    var revision by remember { mutableIntStateOf(0) }
    var bannerDialog by remember { mutableStateOf(false) }
    val store = DataStore.profileCacheStore

    fun changed(block: () -> Unit) {
        block()
        revision++
    }
    fun edit(
        title: String,
        value: String,
        keyboard: KeyboardType = KeyboardType.Text,
        accept: (String) -> Boolean = { true },
        update: (String) -> Unit,
    ) {
        context.showComposeTextInputDialog(
            title = title,
            initialValue = value,
            keyboardType = keyboard,
            onPositive = { if (accept(it)) changed { update(it) } },
        )
    }
    fun choose(
        title: String,
        entries: List<String>,
        values: List<String>,
        value: String,
        update: (String) -> Unit,
    ) {
        context.showComposeSingleChoiceDialog(
            title = title,
            items = entries,
            selectedIndex = values.indexOf(value).coerceAtLeast(0),
            negativeButton = cancel,
            onItemSelected = { changed { update(values[it]) } },
        )
    }
    fun summary(entries: List<String>, values: List<String>, value: String) =
        entries.getOrNull(values.indexOf(value)) ?: value.ifBlank { notSet }

    val groupTypes = stringArrayResource(R.array.group_types).toList()
    val twoValues = stringArrayResource(R.array.int_array_2).toList()
    val groupOrders = stringArrayResource(R.array.group_orders).toList()
    val fourValues = stringArrayResource(R.array.int_array_4).toList()
    val fingerprintEntries = stringArrayResource(R.array.utls_fingerprint_entry).toList()
    val fingerprintValues = stringArrayResource(R.array.utls_fingerprint_value).toList()
    val muxTypes = stringArrayResource(R.array.mux_type).toList()
    val muxModes = stringArrayResource(R.array.mux_mode).toList()
    val filterModes = stringArrayResource(R.array.filter_modes).toList()
    val threeValues = stringArrayResource(R.array.int_array_3).toList()
    val spoofModes = stringArrayResource(R.array.spoof_app_modes).toList()
    val routingIntervals = stringArrayResource(R.array.subscription_routing_interval_entries).toList()
    val routingIntervalValues = stringArrayResource(R.array.subscription_routing_interval_values).toList()
    val bannerEntries = stringArrayResource(R.array.subscription_banner_layout_entry).toList()
    val bannerValues = stringArrayResource(R.array.subscription_banner_layout_value).toList()

    // Read after revision so cache-backed values are refreshed after every edit.
    revision
    val subscription = DataStore.groupType == GroupType.SUBSCRIPTION
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_settings)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_navigation_close), stringResource(android.R.string.cancel))
                    }
                },
                actions = {
                    if (canDelete) IconButton(onClick = onDelete) {
                        Icon(painterResource(R.drawable.ic_action_delete), stringResource(R.string.delete))
                    }
                    IconButton(onClick = onSave) {
                        Icon(painterResource(R.drawable.ic_action_done), stringResource(R.string.apply))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
            item {
                val title = stringResource(R.string.group_name)
                ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.group_name,
                    DataStore.groupName.ifBlank { notSet }) {
                    edit(title, DataStore.groupName, update = { DataStore.groupName = it })
                }
            }
            item {
                val title = stringResource(R.string.group_type)
                ProfileActionRow(R.drawable.ic_baseline_layers_24, R.string.group_type,
                    summary(groupTypes, twoValues, DataStore.groupType.toString())) {
                    choose(title, groupTypes, twoValues, DataStore.groupType.toString()) {
                        DataStore.groupType = it.toInt()
                    }
                }
            }
            item {
                val title = stringResource(R.string.group_order)
                ProfileActionRow(R.drawable.ic_baseline_low_priority_24, R.string.group_order,
                    summary(groupOrders, fourValues, DataStore.groupOrder.toString())) {
                    choose(title, groupOrders, fourValues, DataStore.groupOrder.toString()) {
                        DataStore.groupOrder = it.toInt()
                    }
                }
            }
            item { ProfileSwitchRow(R.drawable.ic_baseline_manage_search_24, R.string.use_selector,
                DataStore.groupIsSelector) { changed { DataStore.groupIsSelector = it } } }
            item { ProfileActionRow(R.drawable.ic_hardware_router, R.string.front_proxy,
                frontProxyName, onClick = onSelectFrontProxy) }
            item { ProfileActionRow(R.drawable.baseline_public_24, R.string.landing_proxy,
                landingProxyName, onClick = onSelectLandingProxy) }

            item { ProfileCategory(R.string.overrides) }
            item {
                val title = stringResource(R.string.utls_fingerprint)
                ProfileActionRow(R.drawable.ic_baseline_fingerprint_24, R.string.utls_fingerprint,
                    summary(fingerprintEntries, fingerprintValues, DataStore.groupForceUTLS)) {
                    choose(title, fingerprintEntries, fingerprintValues, DataStore.groupForceUTLS) {
                        DataStore.groupForceUTLS = it
                    }
                }
            }
            item { ProfileSwitchRow(R.drawable.ic_baseline_compare_arrows_24, R.string.enable_mux,
                DataStore.groupEnableMux, stringResource(R.string.mux_sum), dynamicSummary = false) {
                changed { DataStore.groupEnableMux = it }
            } }
            if (DataStore.groupEnableMux) {
                item {
                    val title = stringResource(R.string.mux_type)
                    ProfileActionRow(R.drawable.ic_baseline_stream_24, R.string.mux_type,
                        summary(muxTypes, fourValues, DataStore.groupMuxType.toString())) {
                        choose(title, muxTypes, fourValues, DataStore.groupMuxType.toString()) {
                            DataStore.groupMuxType = it.toInt()
                        }
                    }
                }
                item {
                    val title = stringResource(R.string.mux_mode)
                    ProfileActionRow(R.drawable.ic_baseline_tune_24, R.string.mux_mode,
                        summary(muxModes, twoValues, DataStore.groupMuxMode.toString())) {
                        choose(title, muxModes, twoValues, DataStore.groupMuxMode.toString()) {
                            DataStore.groupMuxMode = it.toInt()
                        }
                    }
                }
                if (DataStore.groupMuxMode == 0) item {
                    val title = stringResource(R.string.mux_concurrency)
                    ProfileActionRow(R.drawable.ic_baseline_low_priority_24, R.string.mux_concurrency,
                        DataStore.groupMuxConcurrency.toString()) {
                        edit(title, DataStore.groupMuxConcurrency.toString(), KeyboardType.Number,
                            update = { DataStore.groupMuxConcurrency = it.toIntOrNull() ?: 0 })
                    }
                } else {
                    item {
                        val title = stringResource(R.string.mux_max_connections)
                        ProfileActionRow(R.drawable.ic_baseline_low_priority_24, R.string.mux_max_connections,
                            DataStore.groupMuxMaxConnections.toString()) {
                            edit(title, DataStore.groupMuxMaxConnections.toString(), KeyboardType.Number,
                                update = { DataStore.groupMuxMaxConnections = it.toIntOrNull() ?: 0 })
                        }
                    }
                    item {
                        val title = stringResource(R.string.mux_min_streams)
                        ProfileActionRow(R.drawable.ic_baseline_low_priority_24, R.string.mux_min_streams,
                            DataStore.groupMuxMinStreams.toString()) {
                            edit(title, DataStore.groupMuxMinStreams.toString(), KeyboardType.Number,
                                update = { DataStore.groupMuxMinStreams = it.toIntOrNull() ?: 0 })
                        }
                    }
                }
                item { ProfileSwitchRow(R.drawable.baseline_developer_board_24, R.string.padding,
                    DataStore.groupMuxPadding) { changed { DataStore.groupMuxPadding = it } } }
                item { ProfileSwitchRow(R.drawable.ic_baseline_speed_24, R.string.mux_brutal,
                    DataStore.groupMuxBrutal) { changed { DataStore.groupMuxBrutal = it } } }
                if (DataStore.groupMuxBrutal) {
                    item {
                        val title = stringResource(R.string.mux_brutal_up_mbps)
                        ProfileActionRow(R.drawable.ic_baseline_upload_24, R.string.mux_brutal_up_mbps,
                            DataStore.groupMuxBrutalUpMbps.toString()) {
                            edit(title, DataStore.groupMuxBrutalUpMbps.toString(), KeyboardType.Number,
                                update = { DataStore.groupMuxBrutalUpMbps = it.toIntOrNull() ?: 0 })
                        }
                    }
                    item {
                        val title = stringResource(R.string.mux_brutal_down_mbps)
                        ProfileActionRow(R.drawable.ic_baseline_download_24, R.string.mux_brutal_down_mbps,
                            DataStore.groupMuxBrutalDownMbps.toString()) {
                            edit(title, DataStore.groupMuxBrutalDownMbps.toString(), KeyboardType.Number,
                                update = { DataStore.groupMuxBrutalDownMbps = it.toIntOrNull() ?: 0 })
                        }
                    }
                }
            }

            if (subscription) {
                item { ProfileCategory(R.string.subscription_settings) }
                item {
                    val title = stringResource(R.string.group_subscription_link)
                    ProfileActionRow(R.drawable.ic_baseline_link_24, R.string.group_subscription_link,
                        DataStore.subscriptionLink.ifBlank { notSet }) {
                        edit(title, DataStore.subscriptionLink, update = { DataStore.subscriptionLink = it })
                    }
                }
                item {
                    val selected = DataStore.subscriptionBannerLayout
                    val labels = bannerValues.mapIndexedNotNull { index, value ->
                        bannerEntries.getOrNull(index)?.takeIf { value in selected }
                    }
                    ProfileActionRow(R.drawable.baseline_widgets_24, R.string.subscription_banner_layout,
                        labels.joinToString().ifBlank { notSet }) { bannerDialog = true }
                }
                item {
                    val title = stringResource(R.string.server_dns)
                    ProfileActionRow(R.drawable.ic_action_dns, R.string.server_dns,
                        DataStore.subscriptionServerDns.ifBlank { stringResource(R.string.server_dns_subscription_sum) },
                        dynamicSummary = DataStore.subscriptionServerDns.isNotBlank()) {
                        edit(title, DataStore.subscriptionServerDns, accept = validateServerDns,
                            update = { DataStore.subscriptionServerDns = it.trim() })
                    }
                }
                item { ProfileSwitchRow(R.drawable.ic_baseline_manage_search_24, R.string.force_resolve,
                    DataStore.subscriptionForceResolve, stringResource(R.string.force_resolve_sum), dynamicSummary = false) {
                    changed { DataStore.subscriptionForceResolve = it }
                } }
                item { ProfileSwitchRow(R.drawable.ic_baseline_import_contacts_24, R.string.deduplication,
                    DataStore.subscriptionDeduplication, stringResource(R.string.deduplication_sum), dynamicSummary = false) {
                    changed { DataStore.subscriptionDeduplication = it }
                } }
                item { ProfileSwitchRow(R.drawable.ic_hardware_router, R.string.subscription_routing_enabled,
                    DataStore.subscriptionRoutingEnabled, stringResource(R.string.subscription_routing_enabled_summary), dynamicSummary = false) {
                    changed { DataStore.subscriptionRoutingEnabled = it }
                } }
                item {
                    val title = stringResource(R.string.subscription_routing_interval)
                    ProfileActionRow(R.drawable.ic_baseline_timelapse_24, R.string.subscription_routing_interval,
                        summary(routingIntervals, routingIntervalValues, DataStore.subscriptionRoutingInterval.toString()),
                        enabled = DataStore.subscriptionRoutingEnabled) {
                        choose(title, routingIntervals, routingIntervalValues,
                            DataStore.subscriptionRoutingInterval.toString()) {
                            DataStore.subscriptionRoutingInterval = it.toInt()
                        }
                    }
                }
                item { ProfileActionRow(R.drawable.ic_baseline_download_24, R.string.subscription_import_routing,
                    stringResource(R.string.subscription_import_routing_summary), dynamicSummary = false, onClick = onImportRouting) }
                item {
                    val title = stringResource(R.string.filter)
                    ProfileActionRow(R.drawable.ic_baseline_filter_list_24, R.string.filter,
                        summary(filterModes, threeValues, DataStore.subscriptionFilterMode.toString())) {
                        choose(title, filterModes, threeValues, DataStore.subscriptionFilterMode.toString()) {
                            DataStore.subscriptionFilterMode = it.toInt()
                        }
                    }
                }
                if (DataStore.subscriptionFilterMode != SubscriptionFilterMode.DISABLED) item {
                    val title = stringResource(R.string.filter_regex)
                    ProfileActionRow(R.drawable.ic_baseline_filter_list_24, R.string.filter_regex,
                        DataStore.subscriptionFilterRegex.ifBlank { notSet }) {
                        edit(title, DataStore.subscriptionFilterRegex,
                            update = { DataStore.subscriptionFilterRegex = it })
                    }
                }

                item { ProfileCategory(R.string.update_settings) }
                item { ProfileSwitchRow(R.drawable.ic_baseline_security_24, R.string.update_when_connected_only,
                    DataStore.subscriptionUpdateWhenConnectedOnly, stringResource(R.string.update_when_connected_only_sum), dynamicSummary = false) {
                    changed { DataStore.subscriptionUpdateWhenConnectedOnly = it }
                } }
                item { ProfileSwitchRow(R.drawable.ic_baseline_fingerprint_24, R.string.hwid_support,
                    DataStore.subscriptionHwidEnabled, stringResource(R.string.hwid_support_sum), dynamicSummary = false) {
                    changed { DataStore.subscriptionHwidEnabled = it; onHwidChanged(it) }
                } }
                item {
                    val title = stringResource(R.string.spoof_app)
                    ProfileActionRow(R.drawable.ic_baseline_android_24, R.string.spoof_app,
                        summary(spoofModes, fourValues, DataStore.subscriptionSpoofApp.toString())) {
                        choose(title, spoofModes, fourValues, DataStore.subscriptionSpoofApp.toString()) {
                            val value = it.toInt()
                            DataStore.subscriptionSpoofApp = value
                            onSpoofAppChanged(value)
                        }
                    }
                }
                item {
                    val title = stringResource(R.string.subscription_user_agent)
                    ProfileActionRow(R.drawable.ic_baseline_grid_3x3_24, R.string.subscription_user_agent,
                        DataStore.subscriptionUserAgent.ifBlank { notSet }) {
                        edit(title, DataStore.subscriptionUserAgent,
                            update = { DataStore.subscriptionUserAgent = it })
                    }
                }
                item { ProfileSwitchRow(R.drawable.ic_baseline_flip_camera_android_24, R.string.auto_update,
                    DataStore.subscriptionAutoUpdate) { changed { DataStore.subscriptionAutoUpdate = it } } }
                item {
                    val title = stringResource(R.string.auto_update_delay)
                    ProfileActionRow(R.drawable.ic_baseline_timelapse_24, R.string.auto_update_delay,
                        DataStore.subscriptionAutoUpdateDelay.toString(), enabled = DataStore.subscriptionAutoUpdate) {
                        edit(title, DataStore.subscriptionAutoUpdateDelay.toString(), KeyboardType.Number,
                            accept = validateAutoUpdateDelay,
                            update = { DataStore.subscriptionAutoUpdateDelay = it.toInt() })
                    }
                }
            }
        }
    }

    if (bannerDialog) {
        var selected by remember { mutableStateOf(DataStore.subscriptionBannerLayout) }
        AlertDialog(
            onDismissRequest = { bannerDialog = false },
            title = { Text(stringResource(R.string.subscription_banner_layout)) },
            text = {
                LazyColumn {
                    items(bannerValues.size) { index ->
                        val value = bannerValues[index]
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selected = if (value in selected) selected - value else selected + value
                            }.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = value in selected, onCheckedChange = { checked ->
                                selected = if (checked) selected + value else selected - value
                            })
                            Text(bannerEntries[index], Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    changed { store.putStringSet(io.nekohasekai.sagernet.Key.SUBSCRIPTION_BANNER_LAYOUT, selected.toMutableSet()) }
                    bannerDialog = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { bannerDialog = false }) { Text(cancel) }
            },
        )
    }
}
