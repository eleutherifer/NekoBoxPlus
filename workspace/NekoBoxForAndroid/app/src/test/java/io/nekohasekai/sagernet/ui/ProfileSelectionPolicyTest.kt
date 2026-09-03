package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSelectionPolicyTest {
    @Test
    fun allPreservesOtherGroups() {
        assertEquals(
            setOf(1L, 2L, 3L),
            updateProfileSelection(setOf(1L), listOf(2L, 3L), ProfileSelectionOperation.All),
        )
    }

    @Test
    fun noneOnlyClearsCurrentGroup() {
        assertEquals(
            setOf(1L),
            updateProfileSelection(setOf(1L, 2L, 3L), listOf(2L, 3L), ProfileSelectionOperation.None),
        )
    }

    @Test
    fun invertOnlyTogglesCurrentGroup() {
        assertEquals(
            setOf(1L, 3L),
            updateProfileSelection(setOf(1L, 2L), listOf(2L, 3L), ProfileSelectionOperation.Invert),
        )
    }

    @Test
    fun filteredSelectionAddsMatchesAndPreservesExistingSelection() {
        assertEquals(
            setOf(1L, 2L, 3L, 4L),
            addMatchingProfileSelection(
                selectedIds = setOf(1L, 2L, 3L),
                matchingIds = listOf(3L, 4L),
            ),
        )
    }

    @Test
    fun filteredSelectionWithNoMatchesPreservesExistingSelection() {
        assertEquals(
            setOf(1L, 2L, 3L),
            addMatchingProfileSelection(
                selectedIds = setOf(1L, 2L, 3L),
                matchingIds = emptyList(),
            ),
        )
    }
}
