package io.nekohasekai.sagernet.ui

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
}
