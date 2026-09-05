package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.AndroidTunPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VpnTunPlanTest {
    private val corePlan = AndroidTunPayload.Plan(
        mtu = 9000,
        autoRoute = true,
        inet4Address = AndroidTunPayload.Cidr("172.19.0.1", 30),
        inet6Address = null,
        dnsMode = "native",
        dnsServers = listOf("172.19.0.2"),
        inet4Routes = listOf(AndroidTunPayload.Cidr("0.0.0.0", 0)),
        inet6Routes = emptyList(),
    )

    private fun plan(
        core: AndroidTunPayload.Plan = corePlan,
        metered: Boolean = false,
        applications: List<String> = listOf("com.example.app"),
        httpProxy: VpnHttpProxyPlan? = VpnHttpProxyPlan("127.0.0.1", 2080, emptyList()),
    ) = VpnTunPlan(
        core = core,
        metered = metered,
        bypassApplications = false,
        applications = applications,
        httpProxy = httpProxy,
    )

    @Test
    fun identicalEffectiveConfigurationMatches() {
        assertEquals(plan(), plan())
    }

    @Test
    fun builderAffectingChangesDoNotMatch() {
        assertNotEquals(plan(), plan(metered = true))
        assertNotEquals(plan(), plan(applications = listOf("com.example.other")))
        assertNotEquals(plan(), plan(httpProxy = null))
        assertNotEquals(
            plan(),
            plan(core = corePlan.copy(dnsServers = listOf("172.19.0.3"))),
        )
        assertNotEquals(
            plan(),
            plan(
                core = corePlan.copy(
                    inet4Routes = listOf(AndroidTunPayload.Cidr("10.0.0.0", 8)),
                )
            ),
        )
    }
}
