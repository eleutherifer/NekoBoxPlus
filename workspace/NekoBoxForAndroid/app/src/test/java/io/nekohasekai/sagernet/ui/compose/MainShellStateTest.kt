package io.nekohasekai.sagernet.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainShellStateTest {
    @Test
    fun `opening requests the drawer`() {
        val state = MainShellState()

        state.openDrawer()

        assertTrue(state.drawerRequestedOpen)
    }

    @Test
    fun `closing requests the drawer animation`() {
        val state = MainShellState().apply { openDrawer() }

        state.closeDrawer()

        assertFalse(state.drawerRequestedOpen)
    }
}
