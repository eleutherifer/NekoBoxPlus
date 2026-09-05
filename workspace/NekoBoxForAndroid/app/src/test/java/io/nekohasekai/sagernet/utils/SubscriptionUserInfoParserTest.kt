package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionUserInfoParserTest {

    @Test
    fun zeroExpiryIsTreatedAsMissing() {
        val info = SubscriptionUserInfoParser.parse(
            "upload=10; download=20; total=100; expire=0",
        )

        assertEquals(30L, info.usedBytes)
        assertEquals(100L, info.totalBytes)
        assertNull(info.expiresAtEpochSeconds)
    }

    @Test
    fun parsesCaseInsensitiveValuesSeparatedByWhitespace() {
        val info = SubscriptionUserInfoParser.parse(
            "UPLOAD=4 DOWNLOAD=5 TOTAL=20 EXPIRE=2000000000",
        )

        assertEquals(9L, info.usedBytes)
        assertEquals(20L, info.totalBytes)
        assertEquals(2_000_000_000L, info.expiresAtEpochSeconds)
    }

    @Test
    fun overflowingUsageSaturates() {
        val info = SubscriptionUserInfoParser.parse(
            "upload=9223372036854775807; download=1",
        )

        assertEquals(Long.MAX_VALUE, info.usedBytes)
    }
}
