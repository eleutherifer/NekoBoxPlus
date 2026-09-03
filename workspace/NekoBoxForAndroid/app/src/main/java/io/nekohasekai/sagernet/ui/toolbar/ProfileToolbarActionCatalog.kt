package io.nekohasekai.sagernet.ui.toolbar

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R

enum class ProfileToolbarActionKind {
    ACTION,
    TOGGLE,
    SUBMENU,
}

data class ProfileToolbarAction(
    val id: ProfileToolbarActionId,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    val kind: ProfileToolbarActionKind = ProfileToolbarActionKind.ACTION,
)

object ProfileToolbarActionCatalog {
    val actions = listOf(
        action(ProfileToolbarActionId.UPDATE_SUBSCRIPTION, R.string.update_current_subscription, R.drawable.ic_baseline_update_24),
        submenu(ProfileToolbarActionId.STATISTICS_MENU, R.string.statistics, R.drawable.ic_baseline_multiline_chart_24),
        action(ProfileToolbarActionId.CLEAR_TRAFFIC, R.string.clear_traffic_statistics, R.drawable.ic_device_data_usage),
        action(ProfileToolbarActionId.CLEAR_TEST_RESULTS, R.string.connection_test_clear_results, R.drawable.ic_baseline_refresh_24),
        submenu(ProfileToolbarActionId.DELETE_MENU, R.string.delete, R.drawable.baseline_delete_sweep_24),
        action(ProfileToolbarActionId.REMOVE_DUPLICATES, R.string.remove_duplicate, R.drawable.ic_baseline_content_copy_24),
        action(ProfileToolbarActionId.DELETE_UNAVAILABLE, R.string.selection_unavailable, R.drawable.baseline_delete_sweep_24),
        action(ProfileToolbarActionId.REMOVE_INSECURE, R.string.selection_insecure, R.drawable.ic_baseline_no_encryption_gmailerrorred_24),
        action(ProfileToolbarActionId.ICMP_PING, R.string.connection_test_icmp_ping, R.drawable.ic_baseline_network_ping_24),
        action(ProfileToolbarActionId.TCP_PING, R.string.connection_test_tcp_ping, R.drawable.ic_baseline_speed_24),
        action(ProfileToolbarActionId.URL_TEST, R.string.connection_test_url_test, R.drawable.ic_baseline_shutter_speed_24),
        toggle(ProfileToolbarActionId.GLOBAL_MODE, R.string.global_mode, R.drawable.baseline_public_24),
        action(ProfileToolbarActionId.CLASH_MODE, R.string.clash_mode, R.drawable.ic_baseline_multiple_stop_24),
        action(ProfileToolbarActionId.ACTIVE_PROFILE, R.string.toolbar_active_profile, R.drawable.ic_baseline_center_focus_weak_24),
        submenu(ProfileToolbarActionId.SORT_AND_LAYOUT, R.string.sort_and_layout, R.drawable.ic_baseline_tune_24),

        action(ProfileToolbarActionId.STUN_TEST, R.string.stun_test, R.drawable.ic_baseline_compare_arrows_24),
        action(ProfileToolbarActionId.SPEED_TEST, R.string.speed_test, R.drawable.ic_baseline_speed_24),
        action(ProfileToolbarActionId.RULESET_MATCH, R.string.ruleset_match_title, R.drawable.ic_baseline_rule_folder_24),
        action(ProfileToolbarActionId.CELLULAR_NETWORK, R.string.cellular_network_title, R.drawable.ic_communication_phonelink_ring),
        action(ProfileToolbarActionId.BACKUP_PANEL, R.string.backup, R.drawable.ic_baseline_save_24),

        action(ProfileToolbarActionId.NAV_PROFILES, R.string.menu_configuration, R.drawable.ic_action_description),
        action(ProfileToolbarActionId.NAV_GROUPS, R.string.menu_group, R.drawable.ic_baseline_view_list_24),
        action(ProfileToolbarActionId.NAV_ROUTING, R.string.menu_route, R.drawable.ic_maps_directions),
        action(ProfileToolbarActionId.NAV_APPS, R.string.apps, R.drawable.ic_navigation_apps),
        action(ProfileToolbarActionId.NAV_ADBLOCK, R.string.adblock, R.drawable.ic_baseline_filter_list_24),
        action(ProfileToolbarActionId.NAV_SETTINGS, R.string.settings, R.drawable.ic_action_settings),
        action(ProfileToolbarActionId.NAV_LOGS, R.string.menu_log, R.drawable.ic_baseline_bug_report_24),
        action(ProfileToolbarActionId.NAV_DASHBOARD, R.string.menu_dashboard, R.drawable.ic_baseline_transform_24),
        action(ProfileToolbarActionId.NAV_TOOLS, R.string.menu_tools, R.drawable.baseline_construction_24),
        action(ProfileToolbarActionId.NAV_ABOUT, R.string.menu_about, R.drawable.ic_baseline_info_24),

        action(ProfileToolbarActionId.MANAGE_ROUTE_ASSETS, R.string.route_manage_assets, R.drawable.ic_baseline_folder_open_24),
        action(ProfileToolbarActionId.ADD_NORMAL_RULE, R.string.route_normal, R.drawable.ic_baseline_add_road_24),
        action(ProfileToolbarActionId.ADD_DNS_RULE, R.string.dns_rule, R.drawable.ic_action_dns),

        action(ProfileToolbarActionId.SETTINGS_INTERFACE, R.string.settings_group_interface, R.drawable.ic_baseline_view_list_24),
        action(ProfileToolbarActionId.SETTINGS_CONNECTION, R.string.settings_group_connection, R.drawable.ic_baseline_compare_arrows_24),
        action(ProfileToolbarActionId.SETTINGS_CORE, R.string.settings_group_core, R.drawable.baseline_developer_board_24),
        action(ProfileToolbarActionId.SETTINGS_INBOUND, R.string.settings_group_inbound, R.drawable.ic_baseline_vpn_key_24),
        action(ProfileToolbarActionId.SETTINGS_ROUTING, R.string.settings_group_routing, R.drawable.ic_baseline_add_road_24),
        action(ProfileToolbarActionId.SETTINGS_DNS, R.string.settings_group_dns, R.drawable.ic_action_dns),
        action(ProfileToolbarActionId.SETTINGS_CONNECTION_TESTING, R.string.settings_group_connection_testing, R.drawable.ic_baseline_speed_24),
        action(ProfileToolbarActionId.SETTINGS_DEVELOPERS, R.string.settings_group_developers, R.drawable.ic_baseline_bug_report_24),
        action(ProfileToolbarActionId.SETTINGS_OTHERS, R.string.settings_group_others, R.drawable.ic_baseline_tune_24),

        toggle(ProfileToolbarActionId.ENABLE_CORE_PROFILING, R.string.enable_core_profiling, R.drawable.ic_baseline_bug_report_24),
        action(ProfileToolbarActionId.RESTART_APP, R.string.toolbar_restart_app, R.drawable.ic_baseline_refresh_24),
        action(ProfileToolbarActionId.KILL_BACKGROUND_PROCESS, R.string.kill_background_process, R.drawable.ic_baseline_running_with_errors_24),
    )

    private val byId = actions.associateBy(ProfileToolbarAction::id)

    init {
        check(byId.size == ProfileToolbarActionId.entries.size)
    }

    operator fun get(id: ProfileToolbarActionId): ProfileToolbarAction = requireNotNull(byId[id])

    private fun action(id: ProfileToolbarActionId, titleRes: Int, iconRes: Int) =
        ProfileToolbarAction(id, titleRes, iconRes)

    private fun toggle(id: ProfileToolbarActionId, titleRes: Int, iconRes: Int) =
        ProfileToolbarAction(id, titleRes, iconRes, ProfileToolbarActionKind.TOGGLE)

    private fun submenu(id: ProfileToolbarActionId, titleRes: Int, iconRes: Int) =
        ProfileToolbarAction(id, titleRes, iconRes, ProfileToolbarActionKind.SUBMENU)
}
