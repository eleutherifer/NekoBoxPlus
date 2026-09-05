package io.nekohasekai.sagernet.ui.compose

import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.AppIconManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay

internal enum class GlobalSettingKind { SWITCH, TEXT, LIST, ACTION }

internal data class GlobalSettingItem(
    val key: String,
    val kind: GlobalSettingKind,
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val fixedSummary: Int = 0,
    @param:ArrayRes val entries: Int = 0,
    @param:ArrayRes val values: Int = 0,
    val dependency: String? = null,
)

internal fun globalSettingsFor(groupId: String): List<GlobalSettingItem> =
    GLOBAL_SETTINGS[groupId].orEmpty()

internal fun maskedSecretSummary(value: String, notSet: String): String =
    value.takeIf(String::isNotEmpty)?.let { "*".repeat(it.length) } ?: notSet

@Composable
internal fun GlobalSettingsGroupScreen(
    groupId: String,
    highlightKey: String? = null,
    revision: Int,
    isVisible: (String) -> Boolean,
    isEnabled: (String) -> Boolean,
    validateChange: (String, String) -> Boolean,
    onChanged: (String) -> Unit,
    onAction: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val store = DataStore.configurationStore
    val notSet = stringResource(R.string.not_set)
    val cancel = stringResource(android.R.string.cancel)
    val preferences = remember(groupId) { globalSettingsFor(groupId) }
    val switches = remember(preferences, revision) {
        mutableStateMapOf<String, Boolean>().apply {
            preferences.filter { it.kind == GlobalSettingKind.SWITCH }.forEach {
                put(it.key, store.getBoolean(it.key, globalBooleanDefault(context, it.key)))
            }
        }
    }
    val strings = remember(preferences, revision) {
        mutableStateMapOf<String, String>().apply {
            preferences.filter { it.kind in setOf(GlobalSettingKind.TEXT, GlobalSettingKind.LIST) }
                .forEach { put(it.key, store.getString(it.key) ?: globalStringDefault(context, it.key)) }
        }
    }
    fun enabled(item: GlobalSettingItem): Boolean = isEnabled(item.key) &&
        (item.dependency?.let { switches[it] == true } ?: true)
    val visiblePreferences = preferences.filter { isVisible(it.key) }
    val listState = rememberLazyListState()
    var activeHighlight by remember(highlightKey) { mutableStateOf(highlightKey) }
    LaunchedEffect(highlightKey, visiblePreferences) {
        val index = visiblePreferences.indexOfFirst { it.key == highlightKey }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            delay(1_500)
            activeHighlight = null
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        state = listState,
    ) {
        items(visiblePreferences, key = GlobalSettingItem::key) { item ->
            Box(
                Modifier.background(
                    if (item.key == activeHighlight) MaterialTheme.colorScheme.secondaryContainer
                    else Color.Transparent,
                ),
            ) {
                when (item.kind) {
                GlobalSettingKind.SWITCH -> {
                    val checked = switches[item.key] == true
                    ProfileSwitchRow(item.icon, item.title, checked,
                        item.fixedSummary.takeIf { it != 0 }?.let { stringResource(it) }, enabled(item),
                        dynamicSummary = item.fixedSummary == 0) { value ->
                        if (validateChange(item.key, value.toString())) {
                            switches[item.key] = value
                            store.putBoolean(item.key, value)
                            onChanged(item.key)
                        }
                    }
                }
                GlobalSettingKind.TEXT -> {
                    val value = strings[item.key].orEmpty()
                    val summary = when {
                        item.key == "globalCustomConfig" && value.isNotBlank() ->
                            stringResource(R.string.lines, value.lineSequence().count())
                        item.fixedSummary != 0 -> stringResource(item.fixedSummary)
                        item.key == "mixedPassword" -> maskedSecretSummary(value, notSet)
                        else -> value.ifBlank { notSet }
                    }
                    val title = stringResource(item.title)
                    ProfileActionRow(item.icon, item.title, summary, enabled(item),
                        dynamicSummary = item.fixedSummary == 0) {
                        if (item.key in GLOBAL_TEXT_ACTION_KEYS) {
                            onAction(item.key)
                        } else context.showComposeTextInputDialog(
                            title = title,
                            initialValue = value,
                            keyboardType = if (item.key in GLOBAL_NUMBER_KEYS) KeyboardType.Number else KeyboardType.Text,
                            password = item.key == "mixedPassword",
                            onPositive = { newValue ->
                                if (validateChange(item.key, newValue)) {
                                    strings[item.key] = newValue
                                    store.putString(item.key, newValue)
                                    onChanged(item.key)
                                }
                            },
                        )
                    }
                }
                GlobalSettingKind.LIST -> {
                    val value = strings[item.key].orEmpty()
                    val entries = if (item.entries == 0) emptyList() else stringArrayResource(item.entries).toList()
                    val values = if (item.values == 0) emptyList() else stringArrayResource(item.values).toList()
                    val selected = values.indexOf(value).coerceAtLeast(0)
                    val summary = if (entries.isEmpty()) value.ifBlank { notSet }
                    else entries.getOrElse(selected) { value.ifBlank { notSet } }
                    val title = stringResource(item.title)
                    ProfileActionRow(item.icon, item.title,
                        item.fixedSummary.takeIf { it != 0 }?.let { stringResource(it) } ?: summary,
                        enabled(item), dynamicSummary = item.fixedSummary == 0) {
                        if (entries.isEmpty()) {
                            onAction(item.key)
                        } else context.showComposeSingleChoiceDialog(
                            title = title,
                            items = entries,
                            selectedIndex = selected,
                            negativeButton = cancel,
                            onItemSelected = { index ->
                                val newValue = values[index]
                                if (validateChange(item.key, newValue)) {
                                    strings[item.key] = newValue
                                    store.putString(item.key, newValue)
                                    onChanged(item.key)
                                }
                            },
                        )
                    }
                }
                    GlobalSettingKind.ACTION -> when (item.key) {
                        "appTheme" -> ProfileCustomActionRow(
                            title = item.title,
                            enabled = enabled(item),
                            leading = {
                                Box(
                                    Modifier
                                        .size(24.dp)
                                        .background(
                                            Color(context.getColorAttr(R.attr.colorPrimary)),
                                            CircleShape,
                                        ),
                                )
                            },
                        ) { onAction(item.key) }
                        "changeIcon" -> {
                            val currentIcon = AppIconManager.current(context)
                            val previewSize = (56 * resources.displayMetrics.density).toInt()
                            val preview = remember(currentIcon, revision, previewSize) {
                                AppIconManager.loadIcon(context, currentIcon)
                                    ?.toBitmap(previewSize, previewSize)
                                    ?.asImageBitmap()
                            }
                            ProfileCustomActionRow(
                                title = item.title,
                                summary = item.fixedSummary.takeIf { it != 0 }
                                    ?.let { stringResource(it) },
                                enabled = enabled(item),
                                dynamicSummary = false,
                                leading = {
                                    if (preview != null) {
                                        Image(
                                            painter = BitmapPainter(preview),
                                            contentDescription = null,
                                            modifier = Modifier.size(40.dp),
                                        )
                                    }
                                },
                            ) { onAction(item.key) }
                        }
                        else -> ProfileActionRow(
                            item.icon, item.title,
                            item.fixedSummary.takeIf { it != 0 }?.let { stringResource(it) },
                            enabled(item),
                            dynamicSummary = item.fixedSummary == 0,
                        ) { onAction(item.key) }
                    }
                }
            }
        }
    }
}

private fun sw(key: String, icon: Int, title: Int, summary: Int = 0, dependency: String? = null) =
    GlobalSettingItem(key, GlobalSettingKind.SWITCH, icon, title, summary, dependency = dependency)
private fun text(key: String, icon: Int, title: Int, summary: Int = 0, dependency: String? = null) =
    GlobalSettingItem(key, GlobalSettingKind.TEXT, icon, title, summary, dependency = dependency)
private fun list(key: String, icon: Int, title: Int, entries: Int, values: Int, summary: Int = 0) =
    GlobalSettingItem(key, GlobalSettingKind.LIST, icon, title, summary, entries, values)
private fun action(key: String, icon: Int, title: Int, summary: Int = 0) =
    GlobalSettingItem(key, GlobalSettingKind.ACTION, icon, title, summary)

private val GLOBAL_NUMBER_KEYS = setOf(
    "mtu", "udpNatMax", "memoryLimit", "mixedPort", "dnsCacheCapacity",
    "connectionTestConcurrent", "connectionTestTimeout", "connectionTestPause",
    "connectionGroupTestTimeout",
)
private val GLOBAL_TEXT_ACTION_KEYS = setOf("globalCustomConfig", "dnsDomainOverrides")

private val GLOBAL_TRUE_DEFAULT_KEYS = setOf(
    "profileCountryIndicator",
    "notificationCountryIndicator",
    "tabDoubleTapToNavigate",
    "automaticConnectionCheck",
    "enableGroupUpdateDialog",
    "openGroupSettingsOnLongPress",
    "confirmProfileDelete",
    "networkChangeResetConnections",
    "strictRoute",
    "enableDnsRouting",
    "enableFakeDns",
    "dnsStoreCache",
    "enableClashAPI",
    "hideClashAPI",
)

private fun globalBooleanDefault(context: android.content.Context, key: String): Boolean =
    if (key == "legacyMainView") context.resources.getBoolean(R.bool.default_legacy_main_view)
    else key in GLOBAL_TRUE_DEFAULT_KEYS

private val GLOBAL_STRING_DEFAULTS = mapOf(
    "nightTheme" to "0",
    "speedInterval" to "0",
    "profileTrafficUpdateInterval" to "0",
    "subscriptionTrafficUnit" to "0",
    "serviceMode" to "vpn",
    "tunImplementation" to "0",
    "mtu" to "9000",
    "trafficFragmentation" to "none",
    "fragmentLength" to "100-200",
    "fragmentInterval" to "10-20",
    "exclaveFragmentMethod" to "0",
    "memoryLimit" to "0",
    "logLevel" to "0",
    "logBufSize" to "250kb",
    "certProvider" to "1",
    "mixedUsername" to "User",
    "tunUnrecognizedTraffic" to "block",
    "tunSystemDnsTraffic" to "normal-proxy-bypass-direct",
    "trafficSniffing" to "1",
    "ipv6Mode" to "0",
    "rulesProvider" to "1",
    "rulesGeositeUrl" to "https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db",
    "rulesGeoipUrl" to "https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db",
    "rulesUpdateInterval" to "0",
    "dnsTimeout" to "10s",
    "dnsOptimisticTimeout" to "5s",
    "remoteDns" to "https://1.1.1.1/dns-query",
    "remoteDnsDeadline" to "5000ms",
    "domain_strategy_for_remote" to "auto",
    "directDns" to "local",
    "directDnsDeadline" to "5000ms",
    "domain_strategy_for_direct" to "auto",
    "domain_strategy_for_server" to "auto",
    "connectionTestURL" to "https://www.gstatic.com/generate_204",
    "connectionGroupTestURL" to "http://64.233.161.94/generate_204",
    "profileTestType" to "0",
    "connectionTestConcurrent" to "5",
    "connectionTestTimeout" to "10000",
    "connectionTestAttempts" to "1",
    "connectionTestPause" to "50",
    "connectionGroupTestTimeout" to "2000",
    "connectionIPResolveURL" to "https://api.ip2location.io",
    "coreProfilerMode" to "0",
    "appTLSVersion" to "1.2",
)

private fun globalStringDefault(context: android.content.Context, key: String): String = when (key) {
    "tunDnsWhitelist" -> context.getString(R.string.default_tun_dns_whitelist)
    "tunDotWhitelist" -> context.getString(R.string.default_tun_dot_whitelist)
    "tunDohWhitelist" -> context.getString(R.string.default_tun_doh_whitelist)
    else -> GLOBAL_STRING_DEFAULTS[key].orEmpty()
}

private val GLOBAL_SETTINGS: Map<String, List<GlobalSettingItem>> = mapOf(
    "interface" to listOf(
        action("appTheme", R.drawable.ic_baseline_color_lens_24, R.string.theme),
        action("configureCustomTheme", R.drawable.ic_action_settings, R.string.configure_custom_theme),
        list("nightTheme", R.drawable.ic_baseline_wb_sunny_24, R.string.night_mode, R.array.night_mode, R.array.int_array_4),
        list("appLanguage", R.drawable.baseline_translate_24, R.string.language, R.array.language_entries, R.array.language_values),
        action(
            "changeIcon",
            R.drawable.ic_action_settings,
            R.string.change_icon,
            R.string.change_icon_android_tv_summary,
        ),
        sw("useToolbar", R.drawable.ic_baseline_view_list_24, R.string.use_toolbar, R.string.use_toolbar_summary),
        action("configureToolbarLayout", R.drawable.ic_baseline_tune_24, R.string.toolbar_layout),
        sw("showProfileCountOnTabs", R.drawable.baseline_keyboard_tab_24, R.string.show_profile_count_on_tabs, R.string.show_profile_count_on_tabs_summary),
        sw("profileCountryIndicator", R.drawable.baseline_public_24, R.string.profile_country_indicator, R.string.profile_country_indicator_summary),
        sw("notificationCountryIndicator", R.drawable.baseline_public_24, R.string.notification_country_indicator, R.string.notification_country_indicator_summary),
        sw("tabDoubleTapToNavigate", R.drawable.baseline_keyboard_tab_24, R.string.tab_double_tap_to_navigate, R.string.tab_double_tap_to_navigate_summary),
        sw("shortProfileProtocolInfo", R.drawable.ic_baseline_info_24, R.string.short_profile_protocol_info, R.string.short_profile_protocol_info_summary),
        sw("dontHighlightInsecureProfiles", R.drawable.ic_baseline_visibility_off_24, R.string.dont_highlight_insecure_profiles, R.string.dont_highlight_insecure_profiles_summary),
        sw("showBottomBarInSettings", R.drawable.ic_baseline_bottom_bar_24, R.string.show_bottom_bar_in_settings, R.string.show_bottom_bar_in_settings_summary),
        sw("compactStatsBar", R.drawable.ic_baseline_bottom_bar_24, R.string.compact_stats_bar, R.string.compact_stats_bar_summary),
        sw("legacyMainView", R.drawable.ic_baseline_view_list_24, R.string.legacy_main_view, R.string.legacy_main_view_summary),
        sw("automaticConnectionCheck", R.drawable.ic_baseline_speed_24, R.string.automatic_connection_check, R.string.automatic_connection_check_summary),
        sw("enableGroupUpdateDialog", R.drawable.ic_baseline_update_24, R.string.enable_group_update_dialog, R.string.enable_group_update_dialog_summary),
        sw("openGroupSettingsOnLongPress", R.drawable.baseline_keyboard_tab_24, R.string.open_group_settings_on_long_press, R.string.open_group_settings_on_long_press_summary),
        list("speedInterval", R.drawable.ic_baseline_shutter_speed_24, R.string.speed_interval, R.array.notification_entry, R.array.notification_value, R.string.notification_summary),
        list("profileTrafficUpdateInterval", R.drawable.ic_baseline_update_24, R.string.profile_traffic_update_interval, R.array.notification_entry, R.array.notification_value, R.string.traffic_update_summary),
        sw("profileTrafficStatistics", R.drawable.ic_baseline_multiline_chart_24, R.string.profile_traffic_statistics, R.string.profile_traffic_statistics_summary),
        list("subscriptionTrafficUnit", R.drawable.ic_device_data_usage, R.string.subscription_traffic_unit, R.array.subscription_traffic_unit_entries, R.array.int_array_2, R.string.subscription_traffic_unit_summary),
        sw("showDirectSpeed", R.drawable.ic_baseline_speed_24, R.string.show_direct_speed, R.string.show_direct_speed_sum),
        sw("showGroupInNotification", R.drawable.ic_baseline_notifications_24, R.string.show_group_in_notification),
        sw("persistentStatusNotification", R.drawable.ic_baseline_notifications_active_24, R.string.persistent_status_notification, R.string.persistent_status_notification_summary),
        sw("confirmProfileDelete", R.drawable.ic_action_delete, R.string.confirm_profile_delete),
        sw("alwaysShowAddress", R.drawable.ic_baseline_center_focus_weak_24, R.string.always_show_address, R.string.always_show_address_sum),
        sw("hideFromRecentApps", R.drawable.ic_baseline_visibility_off_24, R.string.hide_from_recent_apps, R.string.hide_from_recent_apps_summary),
    ),
    "connection" to listOf(
        sw("isAutoConnect", R.drawable.ic_communication_phonelink_ring, R.string.auto_connect, R.string.auto_connect_summary),
        list("serviceMode", R.drawable.ic_device_developer_mode, R.string.service_mode, R.array.service_modes, R.array.service_mode_values),
        list("tunImplementation", R.drawable.ic_baseline_flip_camera_android_24, R.string.tun_implementation, R.array.tun_implementation, R.array.int_array_3),
        text("mtu", R.drawable.baseline_public_24, R.string.mtu),
        list("udpNatMapping", R.drawable.ic_baseline_call_split_24, R.string.udp_nat_mapping, R.array.udp_nat_behavior_entries, R.array.udp_nat_behavior_values, R.string.udp_nat_mapping_summary),
        list("udpNatFiltering", R.drawable.ic_baseline_security_24, R.string.udp_nat_filtering, R.array.udp_nat_behavior_entries, R.array.udp_nat_behavior_values, R.string.udp_nat_filtering_summary),
        text("udpNatMax", R.drawable.ic_baseline_tune_24, R.string.udp_nat_max),
        sw("meteredNetwork", R.drawable.ic_device_data_usage, R.string.metered, R.string.metered_summary),
        sw("acquireWakeLock", R.drawable.baseline_developer_board_24, R.string.acquire_wake_lock, R.string.acquire_wake_lock_summary),
        list("trafficFragmentation", R.drawable.ic_baseline_call_split_24, R.string.traffic_fragmentation, R.array.traffic_fragmentation_entry, R.array.traffic_fragmentation_value),
        text("fragmentLength", R.drawable.ic_baseline_format_align_left_24, R.string.fragment_length),
        text("fragmentInterval", R.drawable.ic_baseline_timelapse_24, R.string.fragment_interval),
        list("exclaveFragmentMethod", R.drawable.ic_baseline_call_split_24, R.string.fragment_method, R.array.exclave_fragmentation_method_entry, R.array.exclave_fragmentation_method_value),
        sw("exclaveFragmentForDirect", R.drawable.ic_baseline_call_split_24, R.string.enable_fragment_for_direct),
        text("byedpiFragmentCli", R.drawable.ic_baseline_tune_24, R.string.byedpi_cli_strategy),
        sw("globalTcpFastOpen", R.drawable.ic_baseline_speed_24, R.string.tcp_fast_open),
        sw("globalTcpMultiPath", R.drawable.ic_baseline_multiple_stop_24, R.string.multipath_tcp),
        list("globalUdpFragment", R.drawable.ic_baseline_call_split_24, R.string.udp_fragmentation, R.array.connection_option_entries, R.array.connection_option_values),
        sw("networkChangeReconnect", R.drawable.ic_baseline_flip_camera_android_24, R.string.network_change_reconnect),
        sw("networkChangeResetConnections", R.drawable.ic_baseline_flip_camera_android_24, R.string.network_change_reset_connections),
        sw("wakeReconnect", R.drawable.ic_baseline_refresh_24, R.string.wake_reconnect),
        sw("wakeResetConnections", R.drawable.ic_baseline_flip_camera_android_24, R.string.wake_reset_connections),
    ),
    "core" to listOf(
        sw("connectionGuard", R.drawable.ic_baseline_security_24, R.string.connection_guard, R.string.connection_guard_summary),
        sw("overloadWatchdog", R.drawable.ic_baseline_bug_report_24, R.string.overload_watchdog, R.string.overload_watchdog_summary),
        text("memoryLimit", R.drawable.ic_baseline_compress_24, R.string.memory_limit, R.string.memory_limit_summary),
        list("logLevel", R.drawable.ic_baseline_bug_report_24, R.string.log_level, R.array.log_level_entry, R.array.log_level_value),
        text("logBufSize", R.drawable.ic_baseline_save_24, R.string.logcat_retention_size),
        list("certProvider", R.drawable.ic_baseline_push_pin_24, R.string.certificate_authority, R.array.certificate_authority, R.array.int_array_4),
        text("globalCustomConfig", R.drawable.ic_baseline_layers_24, R.string.custom_config),
        action("previewSingBoxConfig", R.drawable.ic_action_description, R.string.preview_sing_box_config, R.string.preview_sing_box_config_summary),
    ),
    "inbound" to listOf(
        sw("requireProxyInVPN", R.drawable.ic_baseline_vpn_key_24, R.string.require_proxy_in_vpn, R.string.require_proxy_in_vpn_sum),
        sw("disableUdpForLocalProxy", R.drawable.ic_baseline_security_24, R.string.disable_udp_for_local_proxy, R.string.disable_udp_for_local_proxy_sum),
        text("mixedListener", R.drawable.ic_baseline_link_24, R.string.listener_proxy),
        text("mixedPort", R.drawable.ic_maps_directions_boat, R.string.port_proxy),
        text("mixedUsername", R.drawable.ic_baseline_person_24, R.string.username_proxy),
        text("mixedPassword", R.drawable.ic_settings_password, R.string.password_proxy),
        sw("appendHttpProxy", R.drawable.ic_baseline_http_24, R.string.append_http_proxy, R.string.append_http_proxy_sum),
        text("httpProxyBypass", R.drawable.ic_baseline_domain_24, R.string.http_proxy_bypass, dependency = "appendHttpProxy"),
        sw("strictRoute", R.drawable.ic_baseline_add_road_24, R.string.strict_route),
        sw("allowAccess", R.drawable.ic_baseline_nat_24, R.string.allow_access, R.string.allow_access_sum),
    ),
    "routing" to listOf(
        sw("proxyApps", R.drawable.ic_navigation_apps, R.string.proxied_apps, R.string.proxied_apps_summary),
        list("tunUnrecognizedTraffic", R.drawable.ic_baseline_add_road_24, R.string.title_tun_unrecognized_traffic, R.array.tun_unrecognized_traffic_mode, R.array.tun_unrecognized_traffic_mode_values, R.string.summary_tun_unrecognized_traffic),
        list("tunSystemDnsTraffic", R.drawable.ic_baseline_add_road_24, R.string.title_tun_system_dns_traffic, R.array.tun_system_dns_traffic_mode, R.array.tun_system_dns_traffic_mode_values, R.string.summary_tun_system_dns_traffic),
        text("tunDnsWhitelist", R.drawable.ic_action_dns, R.string.title_tun_dns_whitelist, R.string.summary_tun_dns_whitelist),
        text("tunDotWhitelist", R.drawable.ic_baseline_lock_24, R.string.title_tun_dot_whitelist, R.string.summary_tun_dot_whitelist),
        text("tunDohWhitelist", R.drawable.ic_baseline_http_24, R.string.title_tun_doh_whitelist, R.string.summary_tun_doh_whitelist),
        sw("bypassLan", R.drawable.ic_baseline_legend_toggle_24, R.string.route_opt_bypass_lan),
        sw("bypassLanInCore", R.drawable.ic_baseline_legend_toggle_24, R.string.bypass_lan_in_core),
        list("trafficSniffing", R.drawable.ic_baseline_manage_search_24, R.string.traffic_sniffing, R.array.traffic_sniffing_values, R.array.int_array_2),
        sw("resolveDestination", R.drawable.baseline_wrap_text_24, R.string.resolve_destination, R.string.resolve_destination_summary),
        list("ipv6Mode", R.drawable.ic_image_looks_6, R.string.ipv6, R.array.ipv6_mode, R.array.int_array_4),
        list("rulesProvider", R.drawable.ic_baseline_rule_folder_24, R.string.route_rules_provider, R.array.rules_dat_provider, R.array.rules_provider_values),
        text("rulesGeositeUrl", R.drawable.ic_baseline_link_24, R.string.rules_geosite_url),
        text("rulesGeoipUrl", R.drawable.ic_baseline_location_on_24, R.string.rules_geoip_url),
        text("rulesUpdateInterval", R.drawable.ic_baseline_shutter_speed_24, R.string.ruleset_update_interval, R.string.ruleset_update_interval_summary),
    ),
    "dns" to listOf(
        sw("enableDnsRouting", R.drawable.ic_baseline_camera_24, R.string.enable_dns_routing, R.string.dns_routing_message),
        sw("enableFakeDns", R.drawable.ic_action_lock, R.string.enable_fakedns, R.string.fakedns_message),
        sw("dnsDisableCache", R.drawable.ic_action_lock, R.string.dns_disable_cache, R.string.dns_disable_cache_summary),
        sw("dnsDisableExpire", R.drawable.ic_baseline_timer_24, R.string.dns_disable_expire, R.string.dns_disable_expire_summary),
        text("dnsCacheCapacity", R.drawable.ic_baseline_dns_24, R.string.dns_cache_capacity, R.string.dns_cache_capacity_summary),
        text("dnsTimeout", R.drawable.ic_baseline_timer_24, R.string.dns_timeout, R.string.dns_timeout_summary),
        sw("dnsOptimisticCache", R.drawable.ic_baseline_dns_24, R.string.dns_optimistic_cache, R.string.dns_optimistic_cache_summary),
        text("dnsOptimisticTimeout", R.drawable.ic_baseline_timer_24, R.string.dns_optimistic_timeout, R.string.dns_optimistic_timeout_summary, "dnsOptimisticCache"),
        sw("dnsStoreCache", R.drawable.ic_baseline_save_24, R.string.dns_store_cache, R.string.dns_store_cache_summary),
        sw("dnsReverseMapping", R.drawable.ic_maps_360, R.string.dns_reverse_mapping, R.string.dns_reverse_mapping_summary),
        text("remoteDns", R.drawable.ic_action_dns, R.string.remote_dns),
        text("remoteDnsDeadline", R.drawable.ic_baseline_shutter_speed_24, R.string.remote_dns_deadline),
        list("domain_strategy_for_remote", R.drawable.ic_action_dns, R.string.domain_strategy_for_remote, R.array.dns_network_entry, R.array.dns_network_select),
        text("directDns", R.drawable.ic_action_dns, R.string.direct_dns),
        text("directDnsDeadline", R.drawable.ic_baseline_shutter_speed_24, R.string.direct_dns_deadline),
        action("customDnsServers", R.drawable.ic_baseline_dns_24, R.string.custom_dns_servers),
        list("domain_strategy_for_direct", R.drawable.ic_action_dns, R.string.domain_strategy_for_direct, R.array.dns_network_entry, R.array.dns_network_select),
        list("domain_strategy_for_server", R.drawable.ic_action_dns, R.string.domain_strategy_for_server, R.array.dns_network_entry, R.array.dns_network_select),
        text("dnsDomainOverrides", R.drawable.ic_baseline_domain_24, R.string.dns_domain_overrides),
    ),
    "connectionTesting" to listOf(
        text("connectionTestURL", R.drawable.ic_baseline_cast_connected_24, R.string.connection_test_url),
        text("connectionGroupTestURL", R.drawable.ic_baseline_cast_connected_24, R.string.connection_group_test_url),
        list("profileTestType", R.drawable.ic_baseline_speed_24, R.string.profile_test_type, R.array.urltest_standard_entry, R.array.int_array_3),
        text("connectionTestConcurrent", R.drawable.ic_baseline_stream_24, R.string.test_concurrency),
        text("connectionTestTimeout", R.drawable.ic_baseline_timer_24, R.string.connection_test_timeout),
        list("connectionTestAttempts", R.drawable.ic_baseline_refresh_24, R.string.connection_test_attempts, R.array.connection_test_attempt_entries, R.array.connection_test_attempt_entries),
        text("connectionTestPause", R.drawable.ic_baseline_pause_24, R.string.connection_test_pause),
        sw("connectionTestHardened", R.drawable.ic_baseline_security_24, R.string.connection_test_hardened, R.string.connection_test_hardened_summary),
        text("connectionGroupTestTimeout", R.drawable.ic_baseline_timelapse_24, R.string.connection_group_test_timeout),
        text("connectionIPResolveURL", R.drawable.ic_baseline_cast_connected_24, R.string.connection_ip_resolve_url),
    ),
    "developers" to listOf(
        sw("enableCoreProfiling", R.drawable.ic_baseline_bug_report_24, R.string.enable_core_profiling, R.string.enable_core_profiling_summary),
        list("coreProfilerMode", R.drawable.ic_baseline_bug_report_24, R.string.core_profiler_mode, R.array.core_profiler_mode_entries, R.array.int_array_2),
        action("saveCoreProfilerSnapshot", R.drawable.ic_baseline_save_24, R.string.save_core_profiler_snapshot),
        action("deleteCoreProfilerSnapshot", R.drawable.ic_baseline_delete_24, R.string.delete_core_profiler_snapshot),
        action("performLibcoreGcSweep", R.drawable.ic_baseline_delete_24, R.string.perform_libcore_gc_sweep, R.string.perform_libcore_gc_sweep_summary),
        action("performLibcoreManualCrash", R.drawable.ic_baseline_running_with_errors_24, R.string.perform_libcore_fake_crash, R.string.perform_libcore_fake_crash_summary),
        action("killBackgroundProcess", R.drawable.ic_baseline_running_with_errors_24, R.string.kill_background_process, R.string.kill_background_process_summary),
        sw("enableClashAPI", R.drawable.baseline_construction_24, R.string.enable_clash_api, R.string.enable_clash_api_summary),
        sw("hideClashAPI", R.drawable.baseline_construction_24, R.string.hide_clash_api, R.string.hide_clash_api_summary, "enableClashAPI"),
        action("resetClashApiSecret", R.drawable.ic_settings_password, R.string.reset_clash_api_secret, R.string.reset_clash_api_secret_summary),
    ),
    "others" to listOf(
        sw("hysteria2DisableChromeParrot", R.drawable.ic_baseline_visibility_off_24, R.string.hysteria2_disable_chrome_parrot, R.string.hysteria2_disable_chrome_parrot_summary),
        sw("globalAllowInsecure", R.drawable.ic_action_lock_open, R.string.global_allow_insecure),
        sw("allowInsecureOnRequest", R.drawable.ic_action_lock_open, R.string.allow_insecure_on_request_sum),
        list("appTLSVersion", R.drawable.ic_baseline_lock_24, R.string.app_tls_version, R.array.app_tls_version, R.array.app_tls_version),
        list("appUTLSFingerprint", R.drawable.ic_baseline_fingerprint_24, R.string.app_utls_fingerprint, R.array.utls_fingerprint_entry, R.array.utls_fingerprint_value),
        action("resetSettings", R.drawable.baseline_undo_24, R.string.reset_settings),
        action("clearCache", R.drawable.ic_baseline_delete_24, R.string.clear_cache, R.string.clear_cache_summary),
        action("runStorageMaintenance", R.drawable.ic_device_data_usage, R.string.run_storage_maintenance, R.string.run_storage_maintenance_summary),
    ),
)
