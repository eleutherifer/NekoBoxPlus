package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.OpenConnectDNSServerOptions
import moe.matsuri.nb4a.SingBoxOptions.OpenVPNDNSServerOptions
import moe.matsuri.nb4a.checkEmpty
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushedEndpointDnsOptionsTest {
    private val gson = Gson()

    @Test
    fun serializesOpenVpnAndOpenConnectDnsOptions() {
        val openVPN = OpenVPNDNSServerOptions().apply {
            type = "openvpn"
            tag = "ovpn-dns"
            endpoint = "ovpn"
            accept_default_resolvers = true
            accept_search_domain = true
        }
        val openConnect = OpenConnectDNSServerOptions().apply {
            type = "openconnect"
            tag = "oc-dns"
            endpoint = "oc"
        }

        val openVPNJson = gson.toJson(openVPN)
        assertTrue(openVPNJson.contains("\"endpoint\":\"ovpn\""))
        assertTrue(openVPNJson.contains("\"accept_default_resolvers\":true"))
        assertTrue(openVPNJson.contains("\"accept_search_domain\":true"))
        assertTrue(gson.toJson(openConnect).contains("\"type\":\"openconnect\""))
    }

    @Test
    fun preferredByKeepsDnsRuleNonEmpty() {
        val rule = DNSRule_DefaultOptions().apply {
            preferred_by = listOf("ovpn-dns")
            server = "ovpn-dns"
        }
        assertFalse(rule.checkEmpty())
        assertTrue(gson.toJson(rule).contains("\"preferred_by\":[\"ovpn-dns\"]"))
    }
}
