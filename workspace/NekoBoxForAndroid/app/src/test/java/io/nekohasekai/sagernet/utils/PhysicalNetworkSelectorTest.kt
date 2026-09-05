package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.utils.PhysicalNetworkSelector.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalNetworkSelectorTest {
    private fun physical(
        key: Int,
        isVpnTransport: Boolean = false,
        isTunInterface: Boolean = false,
    ) = Candidate<Int>(
        key = key,
        hasInternet = true,
        notRestricted = true,
        notVpn = true,
        isVpnTransport = isVpnTransport,
        isTunInterface = isTunInterface,
    )

    private fun missing(
        key: Int,
        hasInternet: Boolean = true,
        notRestricted: Boolean = true,
        notVpn: Boolean = true,
    ) = Candidate<Int>(
        key = key,
        hasInternet = hasInternet,
        notRestricted = notRestricted,
        notVpn = notVpn,
        isVpnTransport = false,
        isTunInterface = false,
    )

    @Test
    fun firstUsableCandidateIsSelected() {
        val selector = PhysicalNetworkSelector<Int>()
        assertTrue(selector.available(physical(1)))
        assertEquals(1, selector.selected?.key)
    }

    @Test
    fun selfVpnIsRejectedAndPhysicalRetained() {
        val selector = PhysicalNetworkSelector<Int>()
        selector.available(physical(1)) // wifi
        // our own VPN appears as a candidate
        assertFalse(selector.available(physical(2, isVpnTransport = true)))
        assertEquals(1, selector.selected?.key)
    }

    @Test
    fun tunInterfaceIsRejected() {
        val selector = PhysicalNetworkSelector<Int>()
        selector.available(physical(1))
        assertFalse(selector.available(physical(2, isTunInterface = true)))
        assertEquals(1, selector.selected?.key)
    }

    @Test
    fun missingNotVpnIsRejected() {
        val selector = PhysicalNetworkSelector<Int>()
        assertFalse(selector.available(missing(1, notVpn = false)))
        assertNull(selector.selected)
    }

    @Test
    fun missingInternetOrNotRestrictedRejected() {
        val selector = PhysicalNetworkSelector<Int>()
        assertFalse(selector.available(missing(1, hasInternet = false)))
        assertNull(selector.selected)
        assertFalse(selector.available(missing(1, notRestricted = false)))
        assertNull(selector.selected)
    }

    @Test
    fun wifiToMobileTransitionSelectsMobileBeforeWifiIsLost() {
        val selector = PhysicalNetworkSelector<Int>()
        selector.available(physical(1)) // wifi
        // Android announces the replacement before the previous network is lost.
        assertTrue(selector.available(physical(2))) // mobile
        assertEquals(2, selector.selected?.key)
    }

    @Test
    fun mobileToWifiTransitionSelectsWifiBeforeMobileIsLost() {
        val selector = PhysicalNetworkSelector<Int>()
        selector.available(physical(1)) // mobile
        assertTrue(selector.available(physical(2))) // wifi
        assertEquals(2, selector.selected?.key)
    }

    @Test
    fun selectedCapabilitiesRefreshDoesNotChangeSelection() {
        val selector = PhysicalNetworkSelector<Int>()
        selector.available(physical(1))
        assertFalse(selector.capabilitiesChanged(physical(1)))
        assertEquals(1, selector.selected?.key)
    }

    @Test
    fun nonSelectedCapabilitiesRefreshDoesNotReplaceDefault() {
        val selector = PhysicalNetworkSelector<Int>()
        selector.available(physical(1)) // cellular announced first
        selector.available(physical(2)) // Wi-Fi becomes the actual default

        assertFalse(selector.capabilitiesChanged(physical(1)))
        assertEquals(2, selector.selected?.key)
    }

    @Test
    fun capabilitiesCanSelectCandidateMissedOnAvailable() {
        val selector = PhysicalNetworkSelector<Int>()

        assertTrue(selector.capabilitiesChanged(physical(1)))
        assertEquals(1, selector.selected?.key)
    }

    @Test
    fun losingOneCandidateKeepsTheOther() {
        val selector = PhysicalNetworkSelector<Int>()
        selector.available(physical(1)) // mobile, fallback
        selector.available(physical(2)) // wifi, selected
        // Losing the non-selected mobile candidate must not change selection.
        assertFalse(selector.remove(1))
        assertEquals(2, selector.selected?.key)
    }

    @Test
    fun losingAllCandidatesPublishesNull() {
        val selector = PhysicalNetworkSelector<Int>()
        selector.available(physical(1))
        assertTrue(selector.remove(1))
        assertNull(selector.selected)
    }

    @Test
    fun losingSelectedFallsBackToRemainingUsable() {
        val selector = PhysicalNetworkSelector<Int>()
        selector.available(physical(1)) // mobile, fallback
        selector.available(physical(2)) // wifi, selected
        assertTrue(selector.remove(2)) // wifi lost -> mobile selected
        assertEquals(1, selector.selected?.key)
    }

    @Test
    fun selectedCandidateBecomingUnusableFallsBack() {
        val selector = PhysicalNetworkSelector<Int>()
        selector.available(physical(1)) // mobile, fallback
        selector.available(physical(2)) // wifi, selected

        assertTrue(selector.capabilitiesChanged(missing(2, hasInternet = false)))
        assertEquals(1, selector.selected?.key)
    }

    @Test
    fun removingUnknownCandidateIsNoop() {
        val selector = PhysicalNetworkSelector<Int>()
        selector.available(physical(1))
        assertFalse(selector.remove(99))
        assertEquals(1, selector.selected?.key)
    }

    @Test
    fun vpnBecomingUsableReplacementStillKeepsPhysical() {
        val selector = PhysicalNetworkSelector<Int>()
        selector.available(physical(1)) // wifi
        // wifi drops, only VPN remains -> nothing usable selected -> null
        assertTrue(selector.remove(1))
        selector.available(physical(2, isVpnTransport = true))
        assertNull(selector.selected)
    }
}
