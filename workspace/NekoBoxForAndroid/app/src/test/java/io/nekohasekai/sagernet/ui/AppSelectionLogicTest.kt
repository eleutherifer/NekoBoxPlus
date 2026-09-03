package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSelectionLogicTest {
    private val apps = listOf(
        SelectableApp("one.app", "One", uid = 1001, isSystem = false),
        SelectableApp("shared.first", "Shared A", uid = 1002, isSystem = false),
        SelectableApp("shared.second", "Shared B", uid = 1002, isSystem = false),
        SelectableApp("system.app", "System", uid = 1003, isSystem = true),
    )

    @Test
    fun packageSelectionExpandsToEveryPackageWithTheSameUid() {
        val selected = AppSelectionLogic.selectedUids(apps, "shared.first")

        assertEquals(setOf(1002), selected)
        assertTrue(apps.filter { it.uid in selected }.map { it.packageName }.contains("shared.second"))
    }

    @Test
    fun invertProcessesSharedUidOnlyOnce() {
        val inverted = AppSelectionLogic.invertUids(apps, setOf(1002))

        assertEquals(setOf(1001, 1003), inverted)
    }

    @Test
    fun filteringMatchesLabelPackageAndUidAndHidesSystemAppsByDefault() {
        assertEquals(listOf("one.app"), AppSelectionLogic.filter(apps, "One", false).map { it.packageName })
        assertEquals(listOf("shared.second"), AppSelectionLogic.filter(apps, "second", false).map { it.packageName })
        assertEquals(2, AppSelectionLogic.filter(apps, "1002", false).size)
        assertFalse(AppSelectionLogic.filter(apps, "System", false).any())
        assertEquals(listOf("system.app"), AppSelectionLogic.filter(apps, "System", true).map { it.packageName })
    }

    @Test
    fun selectedAppsSortBeforeUnselectedAppsThenByLabel() {
        val sorted = AppSelectionLogic.sort(apps, setOf(1003))

        assertEquals(listOf("system.app", "one.app", "shared.first", "shared.second"), sorted.map { it.packageName })
    }
}
