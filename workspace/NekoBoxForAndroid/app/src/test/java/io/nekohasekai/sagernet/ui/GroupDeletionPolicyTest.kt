package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupDeletionPolicyTest {

    @Test
    fun ungroupedOnlyGroupCannotBeSwipeDeleted() {
        assertFalse(
            GroupDeletionPolicy.canSwipeDelete(
                isUngrouped = true,
                canDeleteGroup = false,
                isUpdating = false,
            )
        )
    }

    @Test
    fun ungroupedGroupCanBeSwipeDeletedWhenOtherGroupsExist() {
        assertTrue(
            GroupDeletionPolicy.canSwipeDelete(
                isUngrouped = true,
                canDeleteGroup = true,
                isUpdating = false,
            )
        )
    }

    @Test
    fun normalGroupCanBeSwipeDeletedWhenDeletionIsAllowed() {
        assertTrue(
            GroupDeletionPolicy.canSwipeDelete(
                isUngrouped = false,
                canDeleteGroup = true,
                isUpdating = false,
            )
        )
    }

    @Test
    fun normalOnlyGroupCannotBeSwipeDeleted() {
        assertFalse(
            GroupDeletionPolicy.canSwipeDelete(
                isUngrouped = false,
                canDeleteGroup = false,
                isUpdating = false,
            )
        )
    }

    @Test
    fun updatingGroupCannotBeSwipeDeleted() {
        assertFalse(
            GroupDeletionPolicy.canSwipeDelete(
                isUngrouped = false,
                canDeleteGroup = true,
                isUpdating = true,
            )
        )
    }
}
