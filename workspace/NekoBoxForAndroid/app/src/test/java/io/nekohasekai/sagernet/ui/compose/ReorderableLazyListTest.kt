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
}
