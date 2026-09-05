package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.AndroidTunPayload.Cidr
import io.nekohasekai.sagernet.AndroidTunPayload.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidTunPayloadTest {
    private fun payload(
        version: Int = AndroidTunPayload.VERSION,
        mtu: Long = 9000,
        autoRoute: Boolean = true,
        v4: String? = "172.19.0.1/30",
        v6: String? = "fdfe:dcba:9876::1/126",
        dnsMode: String = "hijack",
        dnsServers: List<String> =
            listOfNotNull(
                if (v4 != null) "172.19.0.2" else null,
                if (v6 != null) "fdfe:dcba:9876::2" else null,
            ),
        v4Routes: List<String> = if (v4 != null) listOf("0.0.0.0/0") else emptyList(),
        v6Routes: List<String> = if (v6 != null) listOf("::/0") else emptyList(),
    ): AndroidTunPayload =
        AndroidTunPayload(version, mtu, autoRoute, v4, v6, dnsMode, dnsServers, v4Routes, v6Routes)

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun dualStackDefaultRoutes() {
        val plan = payload().validate()
        assertEquals(9000, plan.mtu)
        assertTrue(plan.autoRoute)
        assertEquals(Cidr("172.19.0.1", 30), plan.inet4Address)
        assertEquals(Cidr("fdfe:dcba:9876::1", 126), plan.inet6Address)
        assertEquals("hijack", plan.dnsMode)
        assertEquals(listOf("172.19.0.2", "fdfe:dcba:9876::2"), plan.dnsServers)
        assertEquals(listOf(Cidr("0.0.0.0", 0)), plan.inet4Routes)
        assertEquals(listOf(Cidr("::", 0)), plan.inet6Routes)
    }

    @Test
    fun ipv4OnlyOmitsInet6() {
        val plan = payload(v6 = null).validate()
        assertEquals(Cidr("172.19.0.1", 30), plan.inet4Address)
        assertNull(plan.inet6Address)
        assertEquals(listOf("172.19.0.2"), plan.dnsServers)
        assertEquals(listOf(Cidr("0.0.0.0", 0)), plan.inet4Routes)
        assertTrue(plan.inet6Routes.isEmpty())
    }

    @Test
    fun ipv6OnlyOmitsInet4() {
        val plan = payload(v4 = null).validate()
        assertEquals(Cidr("fdfe:dcba:9876::1", 126), plan.inet6Address)
        assertNull(plan.inet4Address)
        assertEquals(listOf("fdfe:dcba:9876::2"), plan.dnsServers)
        assertEquals(listOf(Cidr("::", 0)), plan.inet6Routes)
        assertTrue(plan.inet4Routes.isEmpty())
    }

    @Test
    fun bypassLanExplicitRoutes() {
        val plan =
            payload(
                v4Routes = listOf("172.19.0.2/32", "198.18.0.0/15"),
                v6Routes = listOf("2000::/3"),
            ).validate()
        assertEquals(listOf(Cidr("172.19.0.2", 32), Cidr("198.18.0.0", 15)), plan.inet4Routes)
        assertEquals(listOf(Cidr("2000::", 3)), plan.inet6Routes)
    }

    @Test
    fun emptyRouteListsAreAllowed() {
        val plan = payload(v4Routes = emptyList(), v6Routes = emptyList()).validate()
        assertTrue(plan.inet4Routes.isEmpty())
        assertTrue(plan.inet6Routes.isEmpty())
    }

    @Test
    fun malformedCidrsAreRejected() {
        assertInvalid { payload(v4 = "172.19.0.1").validate() } // no slash
        assertInvalid { payload(v4 = "172.19.0.1/").validate() } // empty prefix
        assertInvalid { payload(v4 = "/30").validate() } // empty address
        assertInvalid { payload(v4 = "172.19.0.1/33").validate() } // prefix out of range
        assertInvalid { payload(v4 = "999.0.0.1/30").validate() } // bad octet
        assertInvalid { payload(v4 = "172.19.0.1/abc").validate() } // non-numeric prefix
        assertInvalid { payload(v6 = "fdfe:dcba:9876::1/129").validate() } // v6 prefix out of range
        assertInvalid { payload(v6 = "gggg::1/126").validate() } // bad v6 group
    }

    @Test
    fun familyMismatchIsRejected() {
        assertInvalid { payload(v4 = "fdfe:dcba:9876::1/126").validate() }
        assertInvalid { payload(v6 = "172.19.0.1/30").validate() }
        assertInvalid { payload(v4Routes = listOf("::/0")).validate() }
        assertInvalid { payload(v6Routes = listOf("0.0.0.0/0")).validate() }
    }

    @Test
    fun emptyAddressesAreRejected() {
        assertInvalid { payload(v4 = null, v6 = null).validate() }
    }

    @Test
    fun routesRequireMatchingAddressFamily() {
        // IPv4-only plan must not carry IPv6 routes, and vice versa.
        assertInvalid { payload(v6 = null, v6Routes = listOf("::/0")).validate() }
        assertInvalid { payload(v4 = null, v4Routes = listOf("0.0.0.0/0")).validate() }
    }

    @Test
    fun invalidMtuIsRejected() {
        assertInvalid { payload(mtu = 0).validate() }
        assertInvalid { payload(mtu = 100).validate() } // below RFC 791 minimum
        assertInvalid { payload(mtu = 65536).validate() }
    }

    @Test
    fun dnsModeAndServersMustAgree() {
        assertInvalid { payload(dnsServers = emptyList()).validate() }
        assertInvalid { payload(dnsMode = "disabled").validate() }
        assertInvalid { payload(dnsMode = "invalid").validate() }
        assertInvalid { payload(v4 = null, dnsServers = listOf("172.19.0.2")).validate() }
        assertInvalid { payload(v6 = null, dnsServers = listOf("fdfe:dcba:9876::2")).validate() }
        assertInvalid { payload(dnsServers = listOf("not-an-ip")).validate() }
    }

    @Test
    fun disabledDnsAllowsNoServers() {
        val plan = payload(dnsMode = "disabled", dnsServers = emptyList()).validate()
        assertEquals("disabled", plan.dnsMode)
        assertTrue(plan.dnsServers.isEmpty())
    }

    @Test
    fun multipleDnsServersArePreserved() {
        val servers = listOf("172.19.0.2", "172.19.0.3", "fdfe:dcba:9876::2")
        assertEquals(servers, payload(dnsServers = servers).validate().dnsServers)
    }

    @Test
    fun unsupportedVersionIsRejected() {
        assertInvalid { payload(version = 1).validate() }
        assertInvalid { payload(version = 0).validate() }
    }

    @Test
    fun parseFromJsonRoundTrip() {
        // Minimal dual-stack JSON, exactly what libcore emits.
        val json =
            """
            {
              "version": 2,
              "mtu": 9000,
              "auto_route": true,
              "inet4_address": "172.19.0.1/30",
              "inet6_address": "fdfe:dcba:9876::1/126",
              "dns_mode": "hijack",
              "dns_servers": ["172.19.0.2", "fdfe:dcba:9876::2"],
              "inet4_routes": ["0.0.0.0/0"],
              "inet6_routes": ["::/0"]
            }
            """.trimIndent()
        val plan: Plan = AndroidTunPayload.parse(json)
        assertEquals(Cidr("172.19.0.1", 30), plan.inet4Address)
        assertEquals(listOf("172.19.0.2", "fdfe:dcba:9876::2"), plan.dnsServers)
    }

    @Test
    fun parseRejectsMalformedJson() {
        assertInvalid { AndroidTunPayload.parse("{not json") }
    }
}
