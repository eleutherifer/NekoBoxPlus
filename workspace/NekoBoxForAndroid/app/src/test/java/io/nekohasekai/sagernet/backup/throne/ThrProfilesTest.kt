package io.nekohasekai.sagernet.backup.throne

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.internal.setEmbeddedProfiles
import moe.matsuri.nb4a.proxy.direct.DirectBean
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ThrProfilesTest {
    private fun direct(id: Long, order: Long = id) = ProxyEntity(id = id, groupId = 1, userOrder = order).putBean(
        DirectBean().apply { initializeDefaultValues(); name = "direct-$id" })
    private fun chain(id: Long, members: List<Long>) = ProxyEntity(id = id, groupId = 1, userOrder = id).putBean(
        ChainBean().apply { initializeDefaultValues(); name = "chain"; proxies = members })

    @Test fun chainsRetainStoredHopOrderAndVisibleGroupOrder() {
        val rows = ThrExport.convertProfiles(listOf(chain(1, listOf(3, 2)), direct(2), direct(3)),
            listOf(ProxyGroup(id = 1)), mutableListOf())
        assertEquals(listOf(1L, 2L, 3L), rows.map { it["id"] })
        val hops = JSONObject(rows[0]["outbound_json"] as String).getJSONArray("list")
        assertEquals(listOf(3L, 2L), (0 until hops.length()).map(hops::getLong))
    }

    @Test fun embeddedChildrenGetFreshIdsAndBrokenDependentsAreOmitted() {
        val wrapper = ProxyEntity(id = 10, groupId = 1, userOrder = 1).putBean(ProxySetBean().apply {
            initializeDefaultValues(); mode = ProxySetBean.MODE_URL_TEST
            setEmbeddedProfiles(listOf(direct(1).requireBean(), direct(2).requireBean()))
        })
        val omissions = mutableListOf<ThrOmission>()
        val rows = ThrExport.convertProfiles(listOf(wrapper, chain(20, listOf(10)), direct(30)),
            listOf(ProxyGroup(id = 1)), omissions)
        val ids = rows.map { it["id"] as Long }
        assertEquals(3, ids.size)
        assertEquals(30L, ids.last())
        assertTrue(ids.take(2).all { it > 30 })
        assertEquals(3, ids.toSet().size)
        assertTrue(omissions.any { it.id == 10L && it.reason == "urltest_wrapper" })
        assertTrue(omissions.any { it.id == 20L && it.reason == "missing_target" })
        assertEquals("chain", omissions.single { it.id == 20L }.entityName)
    }

    @Test fun rejectedEmbeddedProfilesKeepTheirNamesInWarnings() {
        val child = direct(1).requireBean().apply { customConfigJson = "{}" }
        val wrapper = ProxyEntity(id = 10, groupId = 1).putBean(ProxySetBean().apply {
            initializeDefaultValues(); name = "Automatic selection"; mode = ProxySetBean.MODE_URL_TEST
            setEmbeddedProfiles(listOf(child))
        })
        val omissions = mutableListOf<ThrOmission>()
        assertTrue(ThrExport.convertProfiles(listOf(wrapper), listOf(ProxyGroup(id = 1)), omissions).isEmpty())
        val childWarning = omissions.single { it.reason == "custom_json" }
        assertTrue(childWarning.id!! > 10L)
        assertEquals("direct-1", childWarning.entityName)
        assertEquals("Automatic selection", omissions.single { it.id == 10L }.entityName)
    }

    @Test fun cyclesAndMissingHopsAreSkippedWithoutChangingSource() {
        val source = listOf(chain(1, listOf(2)), chain(2, listOf(1)), chain(3, listOf(99)))
        val rows = ThrExport.convertProfiles(source, listOf(ProxyGroup(id = 1)), mutableListOf())
        assertTrue(rows.isEmpty())
        assertEquals(listOf(2L), source[0].chainBean!!.proxies)
    }

    @Test fun persistedSocksAndWireguardUseThroneNames() {
        val socks = ThrProfiles.normalize(JSONObject("""{"type":"socks","server":"example.com","server_port":1080,"udp_over_tcp":{"enabled":true}}"""))
        assertTrue(socks.getBoolean("uot"))
        assertFalse(socks.has("udp_over_tcp"))
        val wireguard = ThrProfiles.normalize(JSONObject("""{"type":"wireguard","workers":3,"private_key":"fixture","address":["10.0.0.2/32"],"peers":[]}"""))
        assertEquals(3, wireguard.getInt("worker_count"))
    }

    @Test fun xhttpBecomesThroneXrayVlessWithNativeSettingsShape() {
        val json = ThrProfiles.normalize(JSONObject("""{
            "type":"vless","server":"example.com","server_port":443,"uuid":"fixture-id",
            "packet_encoding":"xudp","flow":"","tls":{"enabled":true,"server_name":"example.com","insecure":false},
            "transport":{"type":"xhttp","mode":"auto","path":"/fixture","host":"example.com","no_grpc_header":true}
        }"""))
        assertEquals("vless", json.getString("protocol"))
        assertEquals("example.com", json.getJSONObject("settings").getString("address"))
        assertEquals("none", json.getJSONObject("settings").getString("encryption"))
        val stream = json.getJSONObject("streamSettings")
        assertEquals("tls", stream.getString("security"))
        assertEquals("example.com", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertTrue(stream.getJSONObject("xhttpSettings").getJSONObject("extra").getBoolean("noGRPCHeader"))
    }

    @Test fun amneziaGeneratedEndpointBecomesPersistedWireguard() {
        val bean = io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean().apply {
            initializeDefaultValues()
            name = "AWG fixture"; privateKey = "fixture-private"; peerPublicKey = "fixture-public"
            localAddress = "10.0.0.2/32"; serverAddress = "example.com"; serverPort = 51820
            jc = 4; s1 = 10; h1 = "1234-5678"; peerPreSharedKey = "fixture-preshared"
        }
        val result = ThrProfiles.convert(ProxyEntity().putBean(bean), null, ThrProfiles.DialOptions())
        assertEquals("wireguard", result.getString("type"))
        assertEquals(4, result.getJSONObject("amnezia_wg").getInt("jc"))
        assertEquals("1234-5678", result.getJSONObject("amnezia_wg").getString("h1"))
        val peer = result.getJSONArray("peers").getJSONObject(0)
        assertEquals("fixture-preshared", peer.getString("pre_shared_key"))
        assertFalse(peer.has("preshared_key"))
        assertFalse(peer.has("allowed_ips"))
    }

    @Test fun masqueRequiresPeerCredentialsAndOmitsProvisioningState() {
        val bean = io.nekohasekai.sagernet.fmt.masque.MasqueBean().apply { initializeDefaultValues() }
        assertThrows(ThrProfiles.Unsupported::class.java) { ThrProfiles.masque(bean, false) }
        val keys = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val publicKey = com.google.common.io.BaseEncoding.base64().encode(keys.public.encoded)
        bean.configPrivateKey = "fixture-private"
        bean.configEndpointPubKey = "-----BEGIN PUBLIC KEY-----\n$publicKey\n-----END PUBLIC KEY-----"
        bean.configIPv4 = "10.0.0.2"; bean.configEndpointV4 = "example.com"
        bean.profileAuthToken = "fixture-provisioning-secret"
        val result = ThrProfiles.masque(bean, false)
        assertEquals("masque", result.getString("type"))
        assertEquals(publicKey, result.getString("peer_public_key"))
        assertEquals(443, result.getInt("server_port"))
        assertEquals("10.0.0.2/32", result.getJSONArray("address").getString(0))
        assertTrue(result.getBoolean("disable_version_fallback"))
        assertEquals("example.com", result.getString("server"))
        assertFalse(result.toString().contains("fixture-provisioning-secret"))
        assertEquals("30s", result.getString("keep_alive_period"))
        bean.useHTTP2 = true
        val h2 = ThrProfiles.masque(bean, false)
        assertEquals(2, h2.getInt("http_version"))
        assertEquals("162.159.198.2", h2.getString("server"))
        bean.useIPv6 = true
        bean.configEndpointV6 = "2606:4700::1"
        assertEquals("2606:4700::1", ThrProfiles.masque(bean, false).getString("server"))
        bean.profileRecreate = true
        assertThrows(ThrProfiles.Unsupported::class.java) { ThrProfiles.masque(bean, false) }
    }

    @Test fun openVpnKeepsTlsAndUsesEndpointProfileType() {
        val bean = io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean().apply {
            initializeDefaultValues(); serverAddress = "example.com"; serverPort = 1194
            caCertificates = "fixture-ca"
        }
        val json = ThrProfiles.convert(ProxyEntity().putBean(bean), null, ThrProfiles.DialOptions())
        assertEquals("openvpn", json.getString("type"))
        assertEquals("fixture-ca", json.getJSONObject("tls").getJSONArray("certificate").getString(0))
        assertEquals("none", json.getString("tunnel_dns"))
    }

    @Test fun unsupportedNonemptyOptionsNeverDisappearSilently() {
        assertThrows(ThrProfiles.Unsupported::class.java) {
            ThrProfiles.normalize(JSONObject("""{"type":"vless","encryption":"mlkem768x25519plus"}"""))
        }
        assertThrows(ThrProfiles.Unsupported::class.java) {
            ThrProfiles.normalize(JSONObject("""{"type":"socks","unrepresentable":true}"""))
        }
    }
}
