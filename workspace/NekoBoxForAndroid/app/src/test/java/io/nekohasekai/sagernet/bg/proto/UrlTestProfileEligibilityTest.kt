package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlTestProfileEligibilityTest {

    @Test
    fun masterDnsVPNIsExcludedFromConnectionTests() {
        val bean = MasterDnsVPNBean().apply { initializeDefaultValues() }
        val profile = ProxyEntity().putBean(bean)

        assertFalse(bean.canTCPing())
        assertTrue(UrlTest.isUnsupportedProfile(profile))
    }

    @Test
    fun regularProfileRemainsEligibleForConnectionTests() {
        val bean = SOCKSBean().apply { initializeDefaultValues() }
        val profile = ProxyEntity().putBean(bean)

        assertTrue(bean.canTCPing())
        assertFalse(UrlTest.isUnsupportedProfile(profile))
    }
}
