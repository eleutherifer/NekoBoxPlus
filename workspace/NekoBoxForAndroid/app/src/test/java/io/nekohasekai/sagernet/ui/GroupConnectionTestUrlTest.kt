package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupConnectionTestUrlTest {
    private val fallbackURL = "https://www.gstatic.com/generate_204"

    @Test
    fun validGroupUrlIsUsed() {
        assertEquals(
            "http://64.233.161.94/generate_204",
            resolveGroupConnectionTestURL(
                "http://64.233.161.94/generate_204",
                fallbackURL,
            ),
        )
    }

    @Test
    fun validGroupUrlIsTrimmed() {
        assertEquals(
            "https://example.com/test",
            resolveGroupConnectionTestURL("  https://example.com/test  ", fallbackURL),
        )
    }

    @Test
    fun invalidGroupUrlFallsBackToNormalUrl() {
        listOf("", "not a URL", "ftp://example.com/test", "http://").forEach { groupURL ->
            assertEquals(fallbackURL, resolveGroupConnectionTestURL(groupURL, fallbackURL))
        }
    }
}
