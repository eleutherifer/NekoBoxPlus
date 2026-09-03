package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupTabSelectionPolicyTest {

    @Test
    fun selectedGroupNearStartOfLongListResolvesToVisibleTargetIndex() {
        val groupIds = (1L..14L).toList()

        assertEquals(1, GroupTabSelectionPolicy.selectedIndex(groupIds, 2L))
    }

    @Test
    fun selectedGroupNearEndOfLongListResolvesToTargetIndex() {
        val groupIds = (1L..20L).toList()

        assertEquals(17, GroupTabSelectionPolicy.selectedIndex(groupIds, 18L))
    }

    @Test
    fun missingSelectedGroupDoesNotRequestInvalidScrollTarget() {
        val groupIds = (1L..10L).toList()

        assertEquals(-1, GroupTabSelectionPolicy.selectedIndex(groupIds, 42L))
        assertEquals(-1, GroupTabSelectionPolicy.selectedIndex(groupIds, 0L))
        assertEquals(-1, GroupTabSelectionPolicy.selectedIndex(groupIds, -1L))
    }

    @Test
    fun navigatorGroupsFollowVisibleTabOrderAndIncludeCurrentGroup() {
        val groups = listOf(
            ProxyGroup(id = 1L, name = "First"),
            ProxyGroup(id = 2L, name = "Current"),
            ProxyGroup(id = 3L, name = "Last"),
        )

        assertEquals(
            listOf(3L, 2L, 1L),
            GroupTabSelectionPolicy.navigatorGroups(groups, longArrayOf(3L, 2L, 1L))
                .map { it.id },
        )
    }

    @Test
    fun navigatorGroupsIgnoreTabsThatNoLongerExist() {
        val groups = listOf(
            ProxyGroup(id = 1L),
            ProxyGroup(id = 3L),
        )

        assertEquals(
            listOf(1L, 3L),
            GroupTabSelectionPolicy.navigatorGroups(groups, longArrayOf(1L, 2L, 3L))
                .map { it.id },
        )
    }
}
