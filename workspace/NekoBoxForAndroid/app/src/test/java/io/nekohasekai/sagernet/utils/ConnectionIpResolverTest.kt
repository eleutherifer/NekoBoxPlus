package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.dto.IPAPIInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionIpResolverTest {
    @Test
    fun returnsNormalizedCountryCode() {
        val result = ConnectionIpResolver.format(
            IPAPIInfo(ip = "192.0.2.1", country_code = " nl ")
        )

        assertEquals("NL", result?.countryCode)
    }

    @Test
    fun ignoresInvalidCountryCode() {
        val result = ConnectionIpResolver.format(
            IPAPIInfo(ip = "192.0.2.1", country_code = "Netherlands")
        )

        assertNull(result?.countryCode)
    }

    @Test
    fun usesFirstValidCountryCode() {
        val result = ConnectionIpResolver.format(
            IPAPIInfo(
                ip = "192.0.2.1",
                country_code = "Netherlands",
                countryInfo = IPAPIInfo.CountryInfoBean(code = "de"),
            )
        )

        assertEquals("DE", result?.countryCode)
    }
}
