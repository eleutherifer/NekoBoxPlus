package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.R

internal data class SettingsGroupUiModel(
    val id: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val iconRes: Int,
    val keys: List<String> = emptyList(),
)

internal val SETTINGS_GROUPS = listOf(
    SettingsGroupUiModel("interface", R.string.settings_group_interface,
        R.string.settings_group_interface_description, R.drawable.ic_baseline_view_list_24),
    SettingsGroupUiModel("connection", R.string.settings_group_connection,
        R.string.settings_group_connection_description, R.drawable.ic_baseline_compare_arrows_24),
    SettingsGroupUiModel("core", R.string.settings_group_core,
        R.string.settings_group_core_description, R.drawable.baseline_developer_board_24),
    SettingsGroupUiModel("inbound", R.string.settings_group_inbound,
        R.string.settings_group_inbound_description, R.drawable.ic_baseline_vpn_key_24),
    SettingsGroupUiModel("routing", R.string.settings_group_routing,
        R.string.settings_group_routing_description, R.drawable.ic_baseline_add_road_24),
    SettingsGroupUiModel("dns", R.string.settings_group_dns,
        R.string.settings_group_dns_description, R.drawable.ic_action_dns),
    SettingsGroupUiModel("connectionTesting", R.string.settings_group_connection_testing,
        R.string.settings_group_connection_testing_description, R.drawable.ic_baseline_speed_24),
    SettingsGroupUiModel("developers", R.string.settings_group_developers,
        R.string.settings_group_developers_description, R.drawable.ic_baseline_bug_report_24),
    SettingsGroupUiModel("others", R.string.settings_group_others,
        R.string.settings_group_others_description, R.drawable.ic_baseline_tune_24),
)

internal fun settingsGroupTitle(groupId: String): Int =
    SETTINGS_GROUPS.firstOrNull { it.id == groupId }?.titleRes ?: R.string.settings
