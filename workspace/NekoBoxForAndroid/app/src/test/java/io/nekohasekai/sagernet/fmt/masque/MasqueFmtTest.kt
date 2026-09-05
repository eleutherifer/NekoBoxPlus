package io.nekohasekai.sagernet.fmt.masque

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MasqueFmtTest {

    @Test
    fun newProfilesPreferIPv4AndExplicitH3() {
        val bean = MasqueBean().apply { initializeDefaultValues() }

        assertFalse(bean.useIPv6)
        assertEquals("h3", masqueTransport(bean.useHTTP2))
    }

    @Test
    fun http2AndExplicitIPv6RemainSupported() {
        val bean = MasqueBean().apply {
            initializeDefaultValues()
            useHTTP2 = true
            useIPv6 = true
        }

        assertTrue(bean.useIPv6)
        assertEquals("h2", masqueTransport(bean.useHTTP2))
    }
}
