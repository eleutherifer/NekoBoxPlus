package io.nekohasekai.sagernet.fmt.tailscale

import io.nekohasekai.sagernet.fmt.applyConfiguredDialOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleFmtTest {

    @Test
    fun endpointBuilderMapsSupportedAndroidOptions() {
        val bean = TailscaleBean().apply {
            initializeDefaultValues()
            authKey = "tskey-auth"
            controlURL = "https://control.example.com"
            ephemeral = true
            hostname = "phone"
            acceptRoutes = true
            exitNode = "100.64.0.2"
            exitNodeAllowLANAccess = true
            advertiseRoutes = "10.0.0.0/8,192.168.0.0/16"
            advertiseExitNode = true
            advertiseTags = "tag:android\ntag:proxy"
            relayServerPort = 3478
            relayServerStaticEndpoints = "203.0.113.1:3478"
            udpTimeout = "5m"
            tcpKeepAlive = "30s"
        }

        val endpoint = buildSingBoxEndpointTailscaleBean(bean, 42).apply {
            applyConfiguredDialOptions(bean, false, false, "")
        }

        assertEquals("tailscale", endpoint.type)
        assertEquals("tailscale/42", endpoint.state_directory)
        assertEquals("tskey-auth", endpoint.auth_key)
        assertEquals("https://control.example.com", endpoint.control_url)
        assertTrue(endpoint.ephemeral == true)
        assertEquals("phone", endpoint.hostname)
        assertTrue(endpoint.accept_routes == true)
        assertEquals("100.64.0.2", endpoint.exit_node)
        assertTrue(endpoint.exit_node_allow_lan_access == true)
        assertEquals(listOf("10.0.0.0/8", "192.168.0.0/16"), endpoint.advertise_routes)
        assertTrue(endpoint.advertise_exit_node == true)
        assertEquals(listOf("tag:android", "tag:proxy"), endpoint.advertise_tags)
        assertEquals(3478, endpoint.relay_server_port)
        assertEquals(listOf("203.0.113.1:3478"), endpoint.relay_server_static_endpoints)
        assertEquals("5m", endpoint.udp_timeout)
        assertEquals("30s", endpoint._hack_config_map["tcp_keep_alive"])
        assertNull(endpoint.tag)
    }
}
