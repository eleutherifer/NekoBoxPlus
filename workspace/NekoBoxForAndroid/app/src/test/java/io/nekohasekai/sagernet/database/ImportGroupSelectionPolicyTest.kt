package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportGroupSelectionPolicyTest {

    @Test
    fun currentBasicGroupIsUsedForImport() {
        val current = group(id = 2L, type = GroupType.BASIC)

        assertEquals(
            2L,
            ImportGroupSelectionPolicy.existingTargetGroupId(
                current = current,
                groups = listOf(current, group(id = 1L, ungrouped = true)),
            ),
        )
    }

    @Test
    fun subscriptionCurrentGroupUsesExistingUngrouped() {
        assertEquals(
            1L,
            ImportGroupSelectionPolicy.existingTargetGroupId(
                current = group(id = 3L, type = GroupType.SUBSCRIPTION),
                groups = listOf(
                    group(id = 3L, type = GroupType.SUBSCRIPTION),
                    group(id = 1L, type = GroupType.BASIC, ungrouped = true),
                ),
            ),
        )
    }

    @Test
    fun subscriptionCurrentGroupWithoutUngroupedRequiresNewGroup() {
        assertNull(
            ImportGroupSelectionPolicy.existingTargetGroupId(
                current = group(id = 3L, type = GroupType.SUBSCRIPTION),
                groups = listOf(group(id = 3L, type = GroupType.SUBSCRIPTION)),
            ),
        )
    }

    @Test
    fun noExistingGroupsRequiresNewGroup() {
        assertNull(
            ImportGroupSelectionPolicy.existingTargetGroupId(
                current = group(id = 3L, type = GroupType.SUBSCRIPTION),
                groups = emptyList(),
            ),
        )
    }

    private fun group(
        id: Long,
        type: Int = GroupType.BASIC,
        ungrouped: Boolean = false,
    ): ImportGroupSelectionPolicy.Group {
        return ImportGroupSelectionPolicy.Group(
            id = id,
            type = type,
            ungrouped = ungrouped,
        )
    }
}
