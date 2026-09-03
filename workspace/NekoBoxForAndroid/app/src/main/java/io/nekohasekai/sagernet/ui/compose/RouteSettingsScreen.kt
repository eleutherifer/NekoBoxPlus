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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

internal enum class RouteEditorField { DOMAIN, IP, RULESET, WIFI_SSID, WIFI_BSSID }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteSettingsScreen(
    isDnsRule: Boolean,
    outboundName: String,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onEditConfig: () -> Unit,
    onSelectApps: () -> Unit,
    onSelectOutbound: () -> Unit,
    onSelectDnsServer: () -> Unit,
    onEditProtocol: () -> Unit,
    onSpecialEditor: (RouteEditorField, String, String) -> Unit,
) {
    val context = LocalContext.current
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    var revision by remember { mutableIntStateOf(0) }
    var networkDialog by remember { mutableStateOf(false) }
    fun changed(block: () -> Unit) { block(); revision++ }
    fun edit(title: String, value: String, keyboard: KeyboardType = KeyboardType.Text,
             update: (String) -> Unit) {
        context.showComposeTextInputDialog(title, value, keyboardType = keyboard,
            onPositive = { changed { update(it) } })
    }
    fun choose(title: String, entries: List<String>, values: List<String>, value: String,
               update: (String) -> Unit) {
        context.showComposeSingleChoiceDialog(title, entries, values.indexOf(value).coerceAtLeast(0),
            cancel, onItemSelected = { changed { update(values[it]) } })
    }
    fun label(entries: List<String>, values: List<String>, value: String) =
        entries.getOrNull(values.indexOf(value)) ?: value.ifBlank { notSet }

    val networkEntries = stringArrayResource(R.array.route_network_type_entry).toList()
    val networkValues = stringArrayResource(R.array.route_network_type_value).toList()
    val transportEntries = stringArrayResource(R.array.route_protocol_entry).toList()
    val transportValues = stringArrayResource(R.array.route_protocol_value).toList()
    val protocolEntries = stringArrayResource(R.array.route_sniff_protocol_entry).toList()
    val protocolValues = stringArrayResource(R.array.route_sniff_protocol_value).toList()
    val yesNoEntries = stringArrayResource(R.array.yes_no_entry).toList()
    val yesNoValues = stringArrayResource(R.array.yes_no_value).toList()
    val dnsActions = stringArrayResource(R.array.dns_rule_action_entry).toList()
    val dnsActionValues = stringArrayResource(R.array.dns_rule_action_value).toList()
    val dnsServers = stringArrayResource(R.array.dns_rule_server_entry).toList()
    val dnsServerValues = stringArrayResource(R.array.dns_rule_server_value).toList()
    val dnsRcodes = stringArrayResource(R.array.dns_rcode_entry).toList()
    val dnsRcodeValues = stringArrayResource(R.array.dns_rcode_value).toList()
    val rejectMethods = stringArrayResource(R.array.dns_reject_method_entry).toList()
    val rejectValues = stringArrayResource(R.array.dns_reject_method_value).toList()
    revision
    val wifiVisible = io.nekohasekai.sagernet.database.RuleEntity
        .isWifiIdentityVisible(DataStore.routeNetworkType)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isDnsRule) R.string.dns_rule else R.string.cag_route)) },
                navigationIcon = { IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_navigation_close), stringResource(android.R.string.cancel))
                } },
                actions = {
                    IconButton(onClick = onDelete) {
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
                val title = stringResource(R.string.route_name)
                ProfileActionRow(R.drawable.ic_social_emoji_symbols, R.string.route_name,
                    DataStore.routeName.ifBlank { notSet }) {
                    edit(title, DataStore.routeName) { DataStore.routeName = it }
                }
            }
            item { ProfileActionRow(R.drawable.ic_baseline_layers_24, R.string.custom_config,
                if (DataStore.serverConfig.isBlank()) notSet
                else stringResource(R.string.lines, DataStore.serverConfig.lineSequence().count()),
                onClick = onEditConfig) }
            item { ProfileCategory(R.string.cag_route) }
            item {
                val count = DataStore.routePackages.lineSequence().count { it.isNotBlank() }
                ProfileActionRow(R.drawable.ic_baseline_legend_toggle_24, R.string.apps,
                    if (count == 0) notSet else stringResource(R.string.apps_message, count), onClick = onSelectApps)
            }
            item {
                val title = stringResource(R.string.domain)
                ProfileActionRow(R.drawable.ic_baseline_domain_24, R.string.domain,
                    DataStore.routeDomain.ifBlank { notSet }) {
                    onSpecialEditor(RouteEditorField.DOMAIN, title, DataStore.routeDomain)
                }
            }
            item {
                val title = stringResource(R.string.destination_ip)
                ProfileActionRow(R.drawable.ic_baseline_add_road_24, R.string.destination_ip,
                    DataStore.routeIP.ifBlank { notSet }) {
                    onSpecialEditor(RouteEditorField.IP, title, DataStore.routeIP)
                }
            }
            item {
                val title = stringResource(R.string.destination_port)
                ProfileActionRow(R.drawable.ic_maps_directions_boat, R.string.destination_port,
                    DataStore.routePort.ifBlank { notSet }) { edit(title, DataStore.routePort) { DataStore.routePort = it } }
            }
            item {
                val title = stringResource(R.string.source_ip)
                ProfileActionRow(R.drawable.ic_baseline_local_bar_24, R.string.source_ip,
                    DataStore.routeSource.ifBlank { notSet }) { edit(title, DataStore.routeSource) { DataStore.routeSource = it } }
            }
            item {
                val title = stringResource(R.string.source_port)
                ProfileActionRow(R.drawable.ic_baseline_home_24, R.string.source_port,
                    DataStore.routeSourcePort.ifBlank { notSet }) { edit(title, DataStore.routeSourcePort) { DataStore.routeSourcePort = it } }
            }
            item {
                val title = "RuleSet(.srs)"
                ProfileActionRow(R.drawable.ic_baseline_rule_folder_24, R.string.ruleset, DataStore.routeRuleset.ifBlank { notSet }) {
                    onSpecialEditor(RouteEditorField.RULESET, title, DataStore.routeRuleset)
                }
            }
            item { ProfileActionRow(R.drawable.baseline_public_24, R.string.network_type,
                DataStore.routeNetworkType.mapNotNull { value -> networkEntries.getOrNull(networkValues.indexOf(value)) }
                    .joinToString().ifBlank { notSet }) { networkDialog = true } }
            if (wifiVisible) {
                item {
                    val title = stringResource(R.string.wifi_ssid)
                    ProfileActionRow(R.drawable.ic_baseline_home_24, R.string.wifi_ssid,
                        DataStore.routeWifiSsid.ifBlank { notSet }) {
                        onSpecialEditor(RouteEditorField.WIFI_SSID, title, DataStore.routeWifiSsid)
                    }
                }
                item {
                    val title = stringResource(R.string.wifi_bssid)
                    ProfileActionRow(R.drawable.ic_baseline_home_24, R.string.wifi_bssid,
                        DataStore.routeWifiBssid.ifBlank { notSet }) {
                        onSpecialEditor(RouteEditorField.WIFI_BSSID, title, DataStore.routeWifiBssid)
                    }
                }
            }
            item {
                val title = stringResource(R.string.network)
                ProfileActionRow(R.drawable.ic_baseline_compare_arrows_24, R.string.network,
                    label(transportEntries, transportValues, DataStore.routeNetwork)) {
                    choose(title, transportEntries, transportValues, DataStore.routeNetwork) { DataStore.routeNetwork = it }
                }
            }
            item { ProfileActionRow(R.drawable.ic_baseline_layers_24, R.string.protocol,
                label(protocolEntries, protocolValues, DataStore.routeProtocol), onClick = onEditProtocol) }
            item {
                val title = stringResource(R.string.clash_mode)
                ProfileActionRow(R.drawable.ic_baseline_tune_24, R.string.clash_mode,
                    DataStore.routeClashMode.ifBlank { notSet }) { edit(title, DataStore.routeClashMode) { DataStore.routeClashMode = it } }
            }
            if (!isDnsRule) {
                item {
                    val title = stringResource(R.string.create_dns_rule)
                    ProfileActionRow(R.drawable.ic_baseline_dns_24, R.string.create_dns_rule,
                        label(yesNoEntries, yesNoValues, DataStore.routeCreateDnsRule.toString())) {
                        choose(title, yesNoEntries, yesNoValues, DataStore.routeCreateDnsRule.toString()) {
                            DataStore.routeCreateDnsRule = it.toInt()
                        }
                    }
                }
                item { ProfileActionRow(R.drawable.ic_hardware_router, R.string.outbound,
                    outboundName, onClick = onSelectOutbound) }
            } else {
                item {
                    val title = stringResource(R.string.dns_rule_action)
                    val value = DataStore.routeDnsAction.ifBlank { "route" }
                    ProfileActionRow(R.drawable.ic_baseline_dns_24, R.string.dns_rule_action,
                        label(dnsActions, dnsActionValues, value)) {
                        choose(title, dnsActions, dnsActionValues, value) { DataStore.routeDnsAction = it }
                    }
                }
                val action = DataStore.routeDnsAction.ifBlank { "route" }
                if (action == "route") item {
                    val displayValue = when {
                        DataStore.routeDnsServer in dnsServerValues -> DataStore.routeDnsServer
                        else -> "__custom__"
                    }
                    ProfileActionRow(R.drawable.ic_action_dns, R.string.dns_rule_server,
                        label(dnsServers, dnsServerValues, displayValue), onClick = onSelectDnsServer)
                }
                if (action in setOf("route", "route-options")) {
                    item { ProfileSwitchRow(R.drawable.ic_action_lock, R.string.dns_disable_cache,
                        DataStore.routeDnsDisableCache) { changed { DataStore.routeDnsDisableCache = it } } }
                    item {
                        val title = stringResource(R.string.dns_rewrite_ttl)
                        ProfileActionRow(R.drawable.ic_baseline_shutter_speed_24, R.string.dns_rewrite_ttl,
                            DataStore.routeDnsRewriteTtl.toString()) {
                            edit(title, DataStore.routeDnsRewriteTtl.toString(), KeyboardType.Number) {
                                DataStore.routeDnsRewriteTtl = it.toIntOrNull() ?: 0
                            }
                        }
                    }
                    item {
                        val title = stringResource(R.string.dns_client_subnet)
                        ProfileActionRow(R.drawable.ic_baseline_add_road_24, R.string.dns_client_subnet,
                            DataStore.routeDnsClientSubnet.ifBlank { notSet }) {
                            edit(title, DataStore.routeDnsClientSubnet) { DataStore.routeDnsClientSubnet = it }
                        }
                    }
                }
                if (action == "predefined") {
                    item {
                        val title = stringResource(R.string.dns_rcode)
                        ProfileActionRow(R.drawable.ic_baseline_tune_24, R.string.dns_rcode,
                            label(dnsRcodes, dnsRcodeValues, DataStore.routeDnsRcode.ifBlank { "NOERROR" })) {
                            choose(title, dnsRcodes, dnsRcodeValues, DataStore.routeDnsRcode.ifBlank { "NOERROR" }) {
                                DataStore.routeDnsRcode = it
                            }
                        }
                    }
                    item { RouteText(R.drawable.ic_baseline_dns_24, R.string.dns_predefined_answer,
                        DataStore.routeDnsPredefinedAnswer, notSet,
                        { a, b, c, d -> edit(a, b, c, d) }) { DataStore.routeDnsPredefinedAnswer = it } }
                    item { RouteText(R.drawable.ic_baseline_dns_24, R.string.dns_predefined_ns,
                        DataStore.routeDnsPredefinedNs, notSet,
                        { a, b, c, d -> edit(a, b, c, d) }) { DataStore.routeDnsPredefinedNs = it } }
                    item { RouteText(R.drawable.ic_baseline_dns_24, R.string.dns_predefined_extra,
                        DataStore.routeDnsPredefinedExtra, notSet,
                        { a, b, c, d -> edit(a, b, c, d) }) { DataStore.routeDnsPredefinedExtra = it } }
                }
                if (action == "reject") item {
                    val title = stringResource(R.string.dns_reject_method)
                    ProfileActionRow(R.drawable.ic_baseline_tune_24, R.string.dns_reject_method,
                        label(rejectMethods, rejectValues, DataStore.routeDnsRejectMethod)) {
                        choose(title, rejectMethods, rejectValues, DataStore.routeDnsRejectMethod) {
                            DataStore.routeDnsRejectMethod = it
                        }
                    }
                }
            }
        }
    }

    if (networkDialog) {
        var selected by remember { mutableStateOf(DataStore.routeNetworkType) }
        AlertDialog(
            onDismissRequest = { networkDialog = false },
            title = { Text(stringResource(R.string.network_type)) },
            text = { LazyColumn { items(networkValues.size) { index ->
                val value = networkValues[index]
                Row(Modifier.fillMaxWidth().clickable {
                    selected = if (value in selected) selected - value else selected + value
                }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(value in selected, onCheckedChange = { checked ->
                        selected = if (checked) selected + value else selected - value
                    })
                    Text(networkEntries[index], Modifier.padding(start = 12.dp))
                }
            } } },
            confirmButton = { TextButton(onClick = {
                changed { DataStore.routeNetworkType = selected.toMutableSet() }
                networkDialog = false
            }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { networkDialog = false }) { Text(cancel) } },
        )
    }
}

@Composable
private fun RouteText(
    icon: Int,
    title: Int,
    value: String,
    notSet: String,
    edit: (String, String, KeyboardType, (String) -> Unit) -> Unit,
    update: (String) -> Unit,
) {
    val label = stringResource(title)
    ProfileActionRow(icon, title, value.ifBlank { notSet }) {
        edit(label, value, KeyboardType.Text, update)
    }
}
