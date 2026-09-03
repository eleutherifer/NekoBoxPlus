package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.utils.ProfileCountryResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyCountryPersistenceTest {
    @Test
    fun replacingBeanRetainsCountryMetadata() {
        val entity = ProxyEntity(
            countryCode = "NL",
            countrySource = ProfileCountryResolver.SOURCE_ENDPOINT,
        ).putBean(SOCKSBean())

        entity.putBean(SOCKSBean())

        assertEquals("NL", entity.countryCode)
        assertEquals(ProfileCountryResolver.SOURCE_ENDPOINT, entity.countrySource)
    }
}
