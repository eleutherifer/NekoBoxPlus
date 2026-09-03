package io.nekohasekai.sagernet.ui.toolbar

enum class ProfileToolbarActionId(val value: String) {
    UPDATE_SUBSCRIPTION("update_subscription"),
    STATISTICS_MENU("statistics_menu"),
    CLEAR_TRAFFIC("clear_traffic"),
    CLEAR_TEST_RESULTS("clear_test_results"),
    DELETE_MENU("delete_menu"),
    REMOVE_DUPLICATES("remove_duplicates"),
    DELETE_UNAVAILABLE("delete_unavailable"),
    REMOVE_INSECURE("remove_insecure"),
    ICMP_PING("icmp_ping"),
    TCP_PING("tcp_ping"),
    URL_TEST("url_test"),
    GLOBAL_MODE("global_mode"),
    CLASH_MODE("clash_mode"),
    ACTIVE_PROFILE("active_profile"),
    SORT_AND_LAYOUT("sort_and_layout"),

    STUN_TEST("stun_test"),
    SPEED_TEST("speed_test"),
    RULESET_MATCH("ruleset_match"),
    CELLULAR_NETWORK("cellular_network"),
    BACKUP_PANEL("backup_panel"),

    NAV_PROFILES("nav_profiles"),
    NAV_GROUPS("nav_groups"),
    NAV_ROUTING("nav_routing"),
    NAV_APPS("nav_apps"),
    NAV_ADBLOCK("nav_adblock"),
    NAV_SETTINGS("nav_settings"),
    NAV_LOGS("nav_logs"),
    NAV_DASHBOARD("nav_dashboard"),
    NAV_TOOLS("nav_tools"),
    NAV_ABOUT("nav_about"),

    MANAGE_ROUTE_ASSETS("manage_route_assets"),
    ADD_NORMAL_RULE("add_normal_rule"),
    ADD_DNS_RULE("add_dns_rule"),

    SETTINGS_INTERFACE("settings_interface"),
    SETTINGS_CONNECTION("settings_connection"),
    SETTINGS_CORE("settings_core"),
    SETTINGS_INBOUND("settings_inbound"),
    SETTINGS_ROUTING("settings_routing"),
    SETTINGS_DNS("settings_dns"),
    SETTINGS_CONNECTION_TESTING("settings_connection_testing"),
    SETTINGS_DEVELOPERS("settings_developers"),
    SETTINGS_OTHERS("settings_others"),

    ENABLE_CORE_PROFILING("enable_core_profiling"),
    RESTART_APP("restart_app"),
    KILL_BACKGROUND_PROCESS("kill_background_process");

    companion object {
        private val byValue = entries.associateBy(ProfileToolbarActionId::value)

        fun fromValue(value: String): ProfileToolbarActionId? = byValue[value]
    }
}

data class ProfileToolbarLayout(
    val active: List<ProfileToolbarActionId>,
    private val inactiveOrder: List<ProfileToolbarActionId> = emptyList(),
) {
    val inactive: List<ProfileToolbarActionId>
        get() {
            val activeSet = active.toSet()
            return (inactiveOrder + ProfileToolbarActionId.entries)
                .asSequence()
                .filterNot(activeSet::contains)
                .distinct()
                .toList()
        }

    fun activate(action: ProfileToolbarActionId): ProfileToolbarLayout {
        if (action in active || active.size >= MAX_ACTIVE_ACTIONS) return this
        return copy(active = active + action, inactiveOrder = inactive - action)
    }

    fun deactivate(action: ProfileToolbarActionId): ProfileToolbarLayout {
        if (action !in active) return this
        return copy(active = active - action, inactiveOrder = listOf(action) + inactive)
    }

    fun move(fromIndex: Int, toIndex: Int): ProfileToolbarLayout {
        if (fromIndex !in active.indices || toIndex !in active.indices || fromIndex == toIndex) {
            return this
        }
        val reordered = active.toMutableList()
        val action = reordered.removeAt(fromIndex)
        reordered.add(toIndex, action)
        return copy(active = reordered)
    }

    companion object {
        const val MAX_ACTIVE_ACTIONS = 10
        private const val VERSION_PREFIX = "v1:"

        val DEFAULT = ProfileToolbarLayout(
            listOf(
                ProfileToolbarActionId.UPDATE_SUBSCRIPTION,
                ProfileToolbarActionId.ICMP_PING,
                ProfileToolbarActionId.URL_TEST,
                ProfileToolbarActionId.DELETE_UNAVAILABLE,
            )
        )

        fun decode(raw: String?): ProfileToolbarLayout {
            if (raw.isNullOrBlank()) return DEFAULT
            val parts = raw.removePrefix(VERSION_PREFIX).split('|', limit = 2)
            val active = parts[0].split(',')
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapNotNull(ProfileToolbarActionId::fromValue)
                .distinct()
                .take(MAX_ACTIVE_ACTIONS)
                .toList()
            val inactive = parts.getOrNull(1).orEmpty().split(',')
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapNotNull(ProfileToolbarActionId::fromValue)
                .filterNot(active.toSet()::contains)
                .distinct()
                .toList()
            return ProfileToolbarLayout(active, inactive)
        }

        fun encode(layout: ProfileToolbarLayout): String {
            val normalized = layout.active.distinct().take(MAX_ACTIVE_ACTIONS)
            return VERSION_PREFIX +
                normalized.joinToString(",") { it.value } +
                "|" +
                layout.inactive.joinToString(",") { it.value }
        }
    }
}
