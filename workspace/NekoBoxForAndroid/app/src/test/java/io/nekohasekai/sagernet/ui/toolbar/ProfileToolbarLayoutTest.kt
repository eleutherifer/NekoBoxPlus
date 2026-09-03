package io.nekohasekai.sagernet.ui.toolbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileToolbarLayoutTest {
    @Test
    fun blankUsesDefaultWithoutTcpPing() {
        val layout = ProfileToolbarLayout.decode("")
        assertEquals(ProfileToolbarLayout.DEFAULT, layout)
        assertFalse(ProfileToolbarActionId.TCP_PING in layout.active)
    }

    @Test
    fun decodeDropsUnknownDuplicatesAndItemsOverLimit() {
        val values = ProfileToolbarActionId.entries.take(11).joinToString(",") { it.value }
        val layout = ProfileToolbarLayout.decode(
            "v1:unknown,${ProfileToolbarActionId.ICMP_PING.value}," +
                "${ProfileToolbarActionId.ICMP_PING.value},$values"
        )
        assertEquals(ProfileToolbarLayout.MAX_ACTIVE_ACTIONS, layout.active.size)
        assertEquals(layout.active.distinct(), layout.active)
    }

    @Test
    fun activationAppendsAndHonorsLimit() {
        val initial = ProfileToolbarLayout(ProfileToolbarActionId.entries.take(9))
        val tenth = initial.activate(ProfileToolbarActionId.entries[9])
        assertEquals(ProfileToolbarActionId.entries[9], tenth.active.last())
        assertSame(tenth, tenth.activate(ProfileToolbarActionId.entries[10]))
    }

    @Test
    fun deactivateMovesActionToFirstInactive() {
        val action = ProfileToolbarActionId.UPDATE_SUBSCRIPTION
        val layout = ProfileToolbarLayout.DEFAULT.deactivate(action)
        assertFalse(action in layout.active)
        assertEquals(action, layout.inactive.first())
    }

    @Test
    fun activeActionsCanBeReordered() {
        val layout = ProfileToolbarLayout.DEFAULT.move(0, 2)
        assertEquals(ProfileToolbarActionId.ICMP_PING, layout.active.first())
        assertEquals(ProfileToolbarActionId.UPDATE_SUBSCRIPTION, layout.active[2])
        assertTrue(ProfileToolbarActionId.TCP_PING in layout.inactive)
    }

    @Test
    fun encodedLayoutRoundTrips() {
        val layout = ProfileToolbarLayout.DEFAULT.move(0, 3)
        val decoded = ProfileToolbarLayout.decode(ProfileToolbarLayout.encode(layout))
        assertEquals(layout.active, decoded.active)
        assertEquals(layout.inactive, decoded.inactive)
    }
}
