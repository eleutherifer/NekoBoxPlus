package moe.matsuri.nb4a

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.byedpi.ByeDPIBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.direct.DirectBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolsDeduplicationTest {

    @Test
    fun allConcreteBeansExposeDistinctDefaultHashes() {
        val hashes = concreteBeans().map { it.hash }

        hashes.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(hashes.size, hashes.toSet().size)
    }

    @Test
    fun byedpiHashDependsOnCliStrategyNotName() {
        val first = ByeDPIBean().apply {
            name = "First"
            cliStrategy = "--disorder 1"
            initializeDefaultValues()
        }
        val second = ByeDPIBean().apply {
            name = "Second"
            cliStrategy = "--disorder 1"
            initializeDefaultValues()
        }
        val third = ByeDPIBean().apply {
            cliStrategy = "--split 2"
            initializeDefaultValues()
        }

        assertEquals(first.hash, second.hash)
        assertNotEquals(first.hash, third.hash)
    }

    @Test
    fun configHashDependsOnConfigTextNotName() {
        val first = ConfigBean().apply {
            name = "First"
            type = 1
            config = """{"type":"direct"}"""
            initializeDefaultValues()
        }
        val second = ConfigBean().apply {
            name = "Second"
            type = 1
            config = """{"type":"direct"}"""
            initializeDefaultValues()
        }
        val third = ConfigBean().apply {
            type = 1
            config = """{"type":"socks"}"""
            initializeDefaultValues()
        }

        assertEquals(first.hash, second.hash)
        assertNotEquals(first.hash, third.hash)
    }

    @Test
    fun jsonNormalizationIgnoresFormattingAndKeyOrder() {
        val first = SOCKSBean().apply {
            customOutboundJson = """{"key1":1,"key2":2}"""
            customConfigJson = """{"outer":{"b":2,"a":1}}"""
            initializeDefaultValues()
        }
        val second = SOCKSBean().apply {
            customOutboundJson = """
                {
                  "key2":  2,
                  "key1": 1
                }
            """.trimIndent()
            customConfigJson = """
                {
                  "outer": {
                    "a": 1,
                    "b": 2
                  }
                }
            """.trimIndent()
            initializeDefaultValues()
        }
        val configFirst = ConfigBean().apply {
            config = """{"key1":1,"key2":2}"""
            initializeDefaultValues()
        }
        val configSecond = ConfigBean().apply {
            config = """
                {
                  "key2": 2,
                  "key1": 1
                }
            """.trimIndent()
            initializeDefaultValues()
        }
        val xhttpFirst = VMessBean().apply {
            xhttpExtra = """{"xmux":{"max_concurrency":"8","max_connections":"4"}}"""
            initializeDefaultValues()
        }
        val xhttpSecond = VMessBean().apply {
            xhttpExtra = """
                {
                  "xmux": {
                    "max_connections": "4",
                    "max_concurrency": "8"
                  }
                }
            """.trimIndent()
            initializeDefaultValues()
        }
        val nekoFirst = NekoBean().apply {
            sharedStorage = JSONObject().put("b", 2).put("a", 1)
            initializeDefaultValues()
        }
        val nekoSecond = NekoBean().apply {
            sharedStorage = JSONObject("""{"a":1,"b":2}""")
            initializeDefaultValues()
        }

        assertEquals(first.hash, second.hash)
        assertEquals(configFirst.hash, configSecond.hash)
        assertEquals(xhttpFirst.hash, xhttpSecond.hash)
        assertEquals(nekoFirst.hash, nekoSecond.hash)
    }

    @Test
    fun invalidJsonFallsBackToRawText() {
        val first = socksWithInvalidJson("{ invalid")
        val second = socksWithInvalidJson("{  invalid")

        assertNotEquals(first.hash, second.hash)
        assertTrue(first.hash.isNotBlank())
        assertTrue(second.hash.isNotBlank())
    }

    @Test
    fun jsonArrayOrderIsPreserved() {
        val first = TuicBean().apply {
            customJSON = """{"items":[1,2,3]}"""
            initializeDefaultValues()
        }
        val second = TuicBean().apply {
            customJSON = """{"items":[3,2,1]}"""
            initializeDefaultValues()
        }

        assertNotEquals(first.hash, second.hash)
    }

    @Test
    fun sameEndpointDifferentProtocolTypesDoNotCollide() {
        val socks = SOCKSBean().apply {
            serverAddress = "example.com"
            serverPort = 443
            username = "user"
            password = "pass"
            initializeDefaultValues()
        }
        val http = HttpBean().apply {
            serverAddress = "example.com"
            serverPort = 443
            username = "user"
            password = "pass"
            initializeDefaultValues()
        }

        assertNotEquals(socks.hash, http.hash)
    }

    @Test
    fun representativeProfileFieldsChangeHash() {
        val vmessTcp = VMessBean().apply {
            uuid = "11111111-1111-1111-1111-111111111111"
            serverAddress = "example.com"
            serverPort = 443
            type = "tcp"
            initializeDefaultValues()
        }
        val vmessWs = VMessBean().apply {
            uuid = "11111111-1111-1111-1111-111111111111"
            serverAddress = "example.com"
            serverPort = 443
            type = "ws"
            host = "cdn.example.com"
            path = "/ws"
            initializeDefaultValues()
        }
        val shadowTls = ShadowTLSBean().apply {
            serverAddress = "example.com"
            serverPort = 443
            version = 3
            password = "secret"
            initializeDefaultValues()
        }

        assertNotEquals(vmessTcp.hash, vmessWs.hash)
        assertNotEquals(vmessTcp.hash, shadowTls.hash)
    }

    @Test
    fun internalProfilesIncludeTheirOwnMemberFields() {
        val chainA = ChainBean().apply {
            proxies = arrayListOf(1L, 2L, 3L)
            initializeDefaultValues()
        }
        val chainB = ChainBean().apply {
            proxies = arrayListOf(1L, 3L, 2L)
            initializeDefaultValues()
        }
        val proxySet = ProxySetBean().apply {
            type = ProxySetBean.TYPE_GROUP
            groupId = 42L
            groupFilterNotRegex = ".*"
            initializeDefaultValues()
        }

        assertNotEquals(chainA.hash, chainB.hash)
        assertNotNull(proxySet.hash)
        assertTrue(proxySet.hash.isNotBlank())
    }

    private fun concreteBeans(): List<AbstractBean> = listOf(
        SOCKSBean(),
        HttpBean(),
        ShadowsocksBean(),
        ShadowsocksRBean(),
        VMessBean(),
        TrojanBean(),
        TrojanGoBean(),
        MieruBean(),
        NaiveBean(),
        HysteriaBean(),
        SSHBean(),
        WireGuardBean(),
        AmneziaWGBean(),
        TuicBean(),
        JuicityBean(),
        SnellBean(),
        MasterDnsVPNBean(),
        TrustTunnelBean(),
        AnyTLSBean(),
        ByeDPIBean(),
        ConfigBean(),
        DirectBean(),
        NekoBean(),
        ChainBean(),
        ProxySetBean(),
    ).onEach { it.initializeDefaultValues() }

    private fun socksWithInvalidJson(json: String) = SOCKSBean().apply {
        customConfigJson = json
        initializeDefaultValues()
    }
}
