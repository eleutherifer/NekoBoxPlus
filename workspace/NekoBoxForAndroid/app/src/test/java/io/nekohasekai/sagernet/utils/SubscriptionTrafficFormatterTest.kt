package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SubscriptionTrafficFormatterTest {

    @Test
    fun `decimal units use powers of 1000`() {
        assertEquals("999 Bytes", format(999, SubscriptionTrafficUnit.DECIMAL))
        assertEquals("1.00 KB", format(1_000, SubscriptionTrafficUnit.DECIMAL))
        assertEquals("1.00 MB", format(1_000_000, SubscriptionTrafficUnit.DECIMAL))
        assertEquals("1.00 GB", format(1_000_000_000, SubscriptionTrafficUnit.DECIMAL))
    }

    @Test
    fun `binary units use powers of 1024`() {
        assertEquals("1023 Bytes", format(1023, SubscriptionTrafficUnit.BINARY))
        assertEquals("1.00 KiB", format(1_024, SubscriptionTrafficUnit.BINARY))
        assertEquals("1.00 MiB", format(1_048_576, SubscriptionTrafficUnit.BINARY))
        assertEquals("1.00 GiB", format(1_073_741_824, SubscriptionTrafficUnit.BINARY))
    }

    @Test
    fun `unknown stored value falls back to binary units`() {
        assertEquals("976.56 KiB", format(1_000_000, -1))
    }

    private fun format(bytes: Long, unit: Int): String {
        return SubscriptionTrafficFormatter.format(bytes, unit, Locale.US)
    }
}
