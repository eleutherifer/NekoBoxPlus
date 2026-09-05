package io.nekohasekai.sagernet.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalSettingsSummaryTest {

    @Test
    fun emptySecretUsesNotSetSummary() {
        assertEquals("Not set", maskedSecretSummary("", "Not set"))
    }

    @Test
    fun secretSummaryUsesOneAsteriskPerCharacter() {
        assertEquals("*****", maskedSecretSummary("a B!1", "Not set"))
        assertEquals("***", maskedSecretSummary("   ", "Not set"))
    }
}
