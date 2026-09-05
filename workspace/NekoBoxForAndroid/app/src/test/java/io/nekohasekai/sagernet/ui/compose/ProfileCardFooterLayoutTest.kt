package io.nekohasekai.sagernet.ui.compose

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
    fun footerDoesNotWrapSingleVisibleValue() {
        assertFalse(shouldWrapDoubleProfileFooter(20, 40, 0, 8))
        assertFalse(shouldWrapDoubleProfileFooter(20, 0, 40, 8))
    }
}
