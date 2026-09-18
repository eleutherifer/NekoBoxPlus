package io.nekohasekai.sagernet.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCardFooterLayoutTest {

    @Test
    fun footerStaysOnOneLineWhenItFitsExactly() {
        assertFalse(shouldWrapDoubleProfileFooter(100, 40, 52, 8))
    }

    @Test
    fun footerWrapsWhenCombinedContentExceedsWidth() {
        assertTrue(shouldWrapDoubleProfileFooter(99, 40, 52, 8))
    }

    @Test
    fun footerUsesSecondLineWhenCardHasVerticalRoom() {
        assertTrue(
            shouldWrapDoubleProfileFooter(
                availableWidth = 120,
                statusWidth = 40,
                trafficWidth = 52,
                spacing = 8,
                availableHeight = 32,
                statusHeight = 16,
                trafficHeight = 16,
            )
        )
    }

    @Test
    fun footerStaysInlineWithoutEnoughVerticalRoom() {
        assertFalse(
            shouldWrapDoubleProfileFooter(
                availableWidth = 120,
                statusWidth = 40,
                trafficWidth = 52,
                spacing = 8,
                availableHeight = 31,
                statusHeight = 16,
                trafficHeight = 16,
            )
        )
    }

    @Test
    fun minimumHeightCardUsesSecondFooterLine() {
        assertTrue(
            shouldWrapDoubleProfileFooter(
                availableWidth = 120,
                statusWidth = 40,
                trafficWidth = 52,
                spacing = 8,
                forceSecondLine = true,
            )
        )
    }

    @Test
    fun footerDoesNotWrapSingleVisibleValue() {
        assertFalse(shouldWrapDoubleProfileFooter(20, 40, 0, 8))
        assertFalse(shouldWrapDoubleProfileFooter(20, 0, 40, 8))
    }

    @Test
    fun doubleCardReservesHeightOnlyWhenAddressAndTrafficAreEnabled() {
        assertEquals(112, doubleProfileMinimumHeightDp(showAddress = true, showTraffic = true))
        assertEquals(0, doubleProfileMinimumHeightDp(showAddress = true, showTraffic = false))
        assertEquals(0, doubleProfileMinimumHeightDp(showAddress = false, showTraffic = true))
        assertEquals(0, doubleProfileMinimumHeightDp(showAddress = false, showTraffic = false))
    }
}
