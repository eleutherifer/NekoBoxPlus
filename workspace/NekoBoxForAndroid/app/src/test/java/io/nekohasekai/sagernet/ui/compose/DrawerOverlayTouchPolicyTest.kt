package io.nekohasekai.sagernet.ui.compose

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerOverlayTouchPolicyTest {
    private val policy = DrawerOverlayTouchPolicy(edgeWidthPx = 24f)

    @Test
    fun `closed drawer passes through touches outside the left edge`() {
        assertFalse(policy.shouldDispatch(MotionEvent.ACTION_DOWN, 25f, drawerActive = false))
    }

    @Test
    fun `edge gesture owns its full touch sequence`() {
        assertTrue(policy.shouldDispatch(MotionEvent.ACTION_DOWN, 24f, drawerActive = false))
        assertTrue(policy.shouldDispatch(MotionEvent.ACTION_MOVE, 200f, drawerActive = false))
        assertTrue(policy.shouldDispatch(MotionEvent.ACTION_UP, 300f, drawerActive = false))
        assertFalse(policy.shouldDispatch(MotionEvent.ACTION_MOVE, 300f, drawerActive = false))
    }

    @Test
    fun `open drawer accepts touches across the screen`() {
        assertTrue(policy.shouldDispatch(MotionEvent.ACTION_DOWN, 500f, drawerActive = true))
        assertTrue(policy.shouldDispatch(MotionEvent.ACTION_UP, 500f, drawerActive = true))
    }
}
