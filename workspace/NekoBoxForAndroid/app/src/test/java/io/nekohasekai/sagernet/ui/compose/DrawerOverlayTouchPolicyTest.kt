package io.nekohasekai.sagernet.ui.compose

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerOverlayTouchPolicyTest {
    private val policy = DrawerOverlayTouchPolicy(edgeWidthPx = 24f)

    @Test
    fun `closed drawer passes through touches outside the left edge`() {
        assertFalse(policy.shouldDispatch(MotionEvent.ACTION_DOWN, 25f, drawerActive = false, y = 100f, edgeTopPx = 88f))
    }

    @Test
    fun `edge gesture owns its full touch sequence`() {
        assertTrue(policy.shouldDispatch(MotionEvent.ACTION_DOWN, 24f, drawerActive = false, y = 100f, edgeTopPx = 88f))
        assertTrue(policy.shouldDispatch(MotionEvent.ACTION_MOVE, 200f, drawerActive = false, y = 100f, edgeTopPx = 88f))
        assertTrue(policy.shouldDispatch(MotionEvent.ACTION_UP, 300f, drawerActive = false, y = 100f, edgeTopPx = 88f))
        assertFalse(policy.shouldDispatch(MotionEvent.ACTION_MOVE, 300f, drawerActive = false, y = 100f, edgeTopPx = 88f))
    }

    @Test
    fun `toolbar touch passes through even when it moves into the swipe region`() {
        assertFalse(policy.shouldDispatch(MotionEvent.ACTION_DOWN, 12f, false, 60f, 88f))
        assertFalse(policy.shouldDispatch(MotionEvent.ACTION_MOVE, 12f, false, 100f, 88f))
        assertFalse(policy.shouldDispatch(MotionEvent.ACTION_UP, 12f, false, 100f, 88f))
    }

    @Test
    fun `open drawer accepts touches across the screen`() {
        assertTrue(policy.shouldDispatch(MotionEvent.ACTION_DOWN, 500f, drawerActive = true, y = 20f, edgeTopPx = 88f))
        assertTrue(policy.shouldDispatch(MotionEvent.ACTION_UP, 500f, drawerActive = true, y = 20f, edgeTopPx = 88f))
    }
}
