package io.nekohasekai.sagernet.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReorderableLazyListTest {
    private val items = listOf(
        ReorderItemBounds("header", 0, 40),
        ReorderItemBounds(1L, 40, 60),
        ReorderItemBounds(2L, 100, 80),
        ReorderItemBounds(3L, 180, 50),
    )

    @Test
    fun downwardDragTargetsFirstCrossedCard() {
        assertEquals(2L, findReorderTarget(1L, 40, 60, 210f, 170f, items))
    }

    @Test
    fun upwardDragTargetsFirstCrossedCard() {
        assertEquals(2L, findReorderTarget(3L, 180, 50, 60f, -145f, items))
    }

    @Test
    fun headerIsIgnoredWhenItWasNotCrossed() {
        assertEquals(1L, findReorderTarget(2L, 100, 80, 65f, -75f, items))
    }

    @Test
    fun movementInsideCurrentCardDoesNotReorder() {
        assertNull(findReorderTarget(2L, 100, 80, 145f, 5f, items))
    }

    @Test
    fun autoScrollMovesUpForTopOverflow() {
        assertEquals(-24f, calculateAutoScrollDelta(-24f, 56f, 0, 300, 40f))
    }

    @Test
    fun autoScrollMovesDownForBottomOverflow() {
        assertEquals(18f, calculateAutoScrollDelta(238f, 318f, 0, 300, 40f))
    }

    @Test
    fun autoScrollIsLimitedToOneFrameStep() {
        assertEquals(-40f, calculateAutoScrollDelta(-120f, -40f, 0, 300, 40f))
        assertEquals(40f, calculateAutoScrollDelta(340f, 420f, 0, 300, 40f))
    }

    @Test
    fun autoScrollStopsInsideViewport() {
        assertEquals(0f, calculateAutoScrollDelta(40f, 120f, 0, 300, 40f))
    }

    @Test
    fun autoScrollCanTargetNewlyVisibleCardWithoutDraggedCardBeingVisible() {
        val newlyVisibleItems = listOf(
            ReorderItemBounds(1L, 20, 60),
            ReorderItemBounds(2L, 80, 60),
        )

        assertEquals(
            2L,
            findReorderTarget(3L, 160, 60, 70f, -20f, newlyVisibleItems),
        )
    }
}
