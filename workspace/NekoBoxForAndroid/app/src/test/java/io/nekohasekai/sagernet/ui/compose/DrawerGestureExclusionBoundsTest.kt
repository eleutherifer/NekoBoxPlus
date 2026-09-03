package io.nekohasekai.sagernet.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrawerGestureExclusionBoundsTest {
    @Test
    fun `exclusion is left aligned and vertically centered`() {
        assertEquals(
            DrawerGestureExclusionBounds(left = 0, top = 400, right = 24, bottom = 600),
            drawerGestureExclusionBounds(
                width = 400,
                height = 1_000,
                edgeWidthPx = 24,
                maxHeightPx = 200,
            ),
        )
    }

    @Test
    fun `exclusion is constrained to the available view`() {
        assertEquals(
            DrawerGestureExclusionBounds(left = 0, top = 0, right = 12, bottom = 100),
            drawerGestureExclusionBounds(
                width = 12,
                height = 100,
                edgeWidthPx = 24,
                maxHeightPx = 200,
            ),
        )
    }

    @Test
    fun `empty view has no exclusion`() {
        assertNull(drawerGestureExclusionBounds(0, 1_000, 24, 200))
    }
}
