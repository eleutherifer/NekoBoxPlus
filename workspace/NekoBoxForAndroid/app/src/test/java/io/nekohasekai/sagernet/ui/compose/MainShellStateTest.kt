package io.nekohasekai.sagernet.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainShellStateTest {
    @Test
    fun `opening mounts the Compose overlay before requesting the drawer`() {
        val state = MainShellState()

        state.openDrawer()

        assertTrue(state.overlayMounted)
        assertTrue(state.drawerRequestedOpen)
    }

    @Test
    fun `closing requests animation without prematurely unmounting the overlay`() {
        val state = MainShellState().apply { openDrawer() }

        state.closeDrawer()

        assertFalse(state.drawerRequestedOpen)
        assertTrue(state.overlayMounted)
    }
}
