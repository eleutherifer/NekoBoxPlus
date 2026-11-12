package io.nekohasekai.sagernet.group

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class ClashYamlSafetyTest {
    private val proxy = """
        proxies:
          - name: example
            type: socks5
            server: example.com
            port: 1080
    """.trimIndent()

    @Test fun ordinarySubscriptionStillImports() {
        assertEquals(1, ClashParser.parse(proxy)?.size)
    }

    @Test fun javaObjectTagsAreRejectedEvenInUnusedFields() {
        assertNull(ClashParser.parse("unexpected: !!java.util.TreeSet [a, b]\n$proxy"))
    }
}
