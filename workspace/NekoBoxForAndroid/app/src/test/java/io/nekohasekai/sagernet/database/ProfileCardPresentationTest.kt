package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCardPresentationTest {

    private fun <T : AbstractBean> profile(bean: T, configure: T.() -> Unit = {}): ProxyEntity {
        bean.initializeDefaultValues()
        bean.configure()
        return ProxyEntity().putBean(bean)
    }

    private fun vless(
        network: String,
        security: String,
        vlessEncryption: String = "",
        allowInsecure: Boolean = false,
    ) = profile(VMessBean()) {
        alterId = -1
        type = network
        this.security = security
        this.vlessEncryption = vlessEncryption
        this.allowInsecure = allowInsecure
    }

    private fun vmess(
        network: String,
        security: String,
        vlessEncryption: String = "",
    ) = profile(VMessBean()) {
        type = network
        this.security = security
        this.vlessEncryption = vlessEncryption
    }

    @Test
    fun vlessCardUsesShortNetworkAndTransportEncryption() {
        assertEquals("VLESS TCP TLS", vless("tcp", "tls").profileCardType())
        assertEquals("VLESS WS TLS", vless("ws", "tls").profileCardType())
        assertEquals("VLESS XHTTP Reality", vless("xhttp", "reality").profileCardType())
        assertEquals("VLESS HTTPUp TLS", vless("httpupgrade", "tls").profileCardType())
        assertEquals("VLESS gRPC None", vless("grpc", "none").profileCardType())
    }

    @Test
    fun vlessEncryptionOnlyAddsMlKemSuffix() {
        val encryption = "mlkem768x25519plus.native.0rtt.padding.public-key"

        assertEquals(
            "VLESS TCP None ML-KEM",
            vless("tcp", "none", encryption).profileCardType(),
        )
        assertEquals(
            "VLESS TCP TLS ML-KEM",
            vless("tcp", "tls", encryption).profileCardType(),
        )
    }

    @Test
    fun vlessSecurityRequiresTransportOrMlKem() {
        assertTrue(vless("tcp", "none").isInsecureProfile(false))
        assertFalse(vless("tcp", "reality").isInsecureProfile(true))
        assertFalse(vless("tcp", "none", "mlkem768x25519plus.native").isInsecureProfile(false))
        assertTrue(vless("tcp", "tls", allowInsecure = true).isInsecureProfile(false))
        assertTrue(vless("tcp", "tls").isInsecureProfile(true))
        assertFalse(
            vless(
                "tcp",
                "tls",
                vlessEncryption = "mlkem768x25519plus.native",
                allowInsecure = true,
            ).isInsecureProfile(true),
        )
    }

    @Test
    fun plaintextAndUnverifiedProxyProfilesAreInsecure() {
        assertTrue(profile(SOCKSBean()).isInsecureProfile(false))
        assertTrue(profile(HttpBean()) { security = "none" }.isInsecureProfile(false))
        assertTrue(profile(HttpBean()) { security = "tls" }.isInsecureProfile(true))
        assertFalse(profile(HttpBean()) { security = "tls" }.isInsecureProfile(false))
        assertTrue(profile(SSHBean()) { publicKey = "" }.isInsecureProfile(false))
        assertFalse(profile(SSHBean()) { publicKey = "ssh-ed25519 AAAA" }.isInsecureProfile(false))
    }

    @Test
    fun legacyCiphersAndExplicitRawModesAreInsecure() {
        assertTrue(profile(ShadowsocksBean()) { method = "none" }.isInsecureProfile(false))
        assertTrue(profile(ShadowsocksBean()) { method = "aes-256-cfb" }.isInsecureProfile(false))
        assertFalse(profile(ShadowsocksBean()) { method = "aes-256-gcm" }.isInsecureProfile(false))
        assertTrue(profile(ShadowsocksRBean()).isInsecureProfile(false))
        assertTrue(
            profile(SnellBean()) {
                version = 6
                mode = "unsafe-raw"
            }.isInsecureProfile(false),
        )
    }

    @Test
    fun masterDnsEncryptionIsNamedAndOnlyNoneIsMarked() {
        val none = profile(MasterDnsVPNBean()) { dataEncryptionMethod = 0 }
        val xor = profile(MasterDnsVPNBean()) { dataEncryptionMethod = 1 }
        val aes = profile(MasterDnsVPNBean()) { dataEncryptionMethod = 5 }

        assertEquals("MasterDnsVPN None", none.profileCardType())
        assertTrue(none.isInsecureProfile(false))
        assertEquals("MasterDnsVPN XOR", xor.profileCardType())
        assertFalse(xor.isInsecureProfile(false))
        assertEquals("MasterDnsVPN AES-256-GCM", aes.profileCardType())
        assertFalse(aes.isInsecureProfile(false))
    }

    @Test
    fun alternateServerAuthenticationAvoidsFalsePositive() {
        val reality = profile(AnyTLSBean()) {
            realityPubKey = "public-key"
            allowInsecure = true
        }

        assertEquals("AnyTLS Reality", reality.profileCardType())
        assertFalse(reality.isInsecureProfile(true))
    }

    @Test
    fun amneziaWgCardUsesVersionedProtocolPrefix() {
        assertEquals("AmneziaWG 2.0", profile(AmneziaWGBean()).profileCardType())

        val v3Fields = listOf<(AmneziaWGBean) -> Unit>(
            { it.headerProtectionKey = "key" },
            { it.contentPaddingAddition = "10-20" },
            { it.rekeyAfterTime = "100-120" },
            { it.rekeyTimeout = "5" },
            { it.rejectAfterTime = "180" },
            { it.keepaliveTimeout = "10-15" },
            { it.maxHandshakeAttempts = "20" },
        )
        v3Fields.forEach { configure ->
            assertEquals(
                "AmneziaWG 3.0",
                profile(AmneziaWGBean()) { configure(this) }.profileCardType(),
            )
        }
        assertEquals(
            "AmneziaWG 3.0",
            profile(AmneziaWGBean()) { contentPaddingAddition = "0" }.profileCardType(),
        )
        assertEquals(
            "AmneziaWG 3.0",
            profile(AmneziaWGBean()) { peerPersistentKeepalive = "22-30" }.profileCardType(),
        )
        assertEquals(
            "AmneziaWG 3.1",
            profile(AmneziaWGBean()) { randomTrailers = true }.profileCardType(),
        )
        assertEquals(
            "AmneziaWG 3.1",
            profile(AmneziaWGBean()) { disableCookies = true }.profileCardType(),
        )
    }

    @Test
    fun shortCardUsesCompactV2RayDetails() {
        assertEquals(
            "Sh.S",
            profile(ShadowsocksBean()) { method = "aes-256-gcm" }.profileCardType(true),
        )
        assertEquals("SSR", profile(ShadowsocksRBean()).profileCardType(true))
        assertEquals("VL.TCP", vless("tcp", "none").profileCardType(true))
        assertEquals("VL.WS.TLS", vless("ws", "TLS").profileCardType(true))
        assertEquals("VL.XHTTP.R", vless("xhttp", "Reality").profileCardType(true))
        assertEquals(
            "VL.HTTPUp.R*",
            vless("httpupgrade", "reality", "mlkem768x25519plus").profileCardType(true),
        )
        assertEquals("VM.gRPC.TLS", vmess("grpc", "tls").profileCardType(true))
        assertEquals(
            "AWG",
            profile(AmneziaWGBean()) { contentPaddingAddition = "10-20" }.profileCardType(true),
        )
        assertEquals(
            "vless",
            profile(ConfigBean()) {
                type = 1
                config = """{"type":"vless-outbound"}"""
            }.profileCardType(true),
        )
    }

    @Test
    fun shortCardMarksEncryptionOnlyForVless() {
        val encryption = "mlkem768x25519plus.native.0rtt.padding.public-key"

        assertEquals("VL.TCP*", vless("", "none", encryption).profileCardType(true))
        assertEquals("VM.TCP", vmess("", "none", encryption).profileCardType(true))
    }

    @Test
    fun everyBuiltInShortCardTypeFitsFiveCharacters() {
        val builtInTypes = listOf(
            ProxyEntity.TYPE_SOCKS,
            ProxyEntity.TYPE_HTTP,
            ProxyEntity.TYPE_SS,
            ProxyEntity.TYPE_SSR,
            ProxyEntity.TYPE_VMESS,
            ProxyEntity.TYPE_TROJAN,
            ProxyEntity.TYPE_TROJAN_GO,
            ProxyEntity.TYPE_MIERU,
            ProxyEntity.TYPE_NAIVE,
            ProxyEntity.TYPE_HYSTERIA,
            ProxyEntity.TYPE_SSH,
            ProxyEntity.TYPE_WG,
            ProxyEntity.TYPE_AWG,
            ProxyEntity.TYPE_TUIC,
            ProxyEntity.TYPE_JUICITY,
            ProxyEntity.TYPE_SNELL,
            ProxyEntity.TYPE_MASTERDNSVPN,
            ProxyEntity.TYPE_BYEDPI,
            ProxyEntity.TYPE_SHADOWTLS,
            ProxyEntity.TYPE_ANYTLS,
            ProxyEntity.TYPE_TRUST_TUNNEL,
            ProxyEntity.TYPE_MASQUE,
            ProxyEntity.TYPE_DIRECT,
            ProxyEntity.TYPE_TAILSCALE,
            ProxyEntity.TYPE_PROXY_SET,
            ProxyEntity.TYPE_CHAIN,
        )

        builtInTypes.forEach { type ->
            val label = ProxyEntity(type = type).profileCardType(true)
            assertTrue("$type produced $label", label.length <= 5)
        }
    }

    @Test
    fun compositeProfilesDoNotInheritSecurityState() {
        assertFalse(profile(ChainBean()).isInsecureProfile(true))
        assertFalse(profile(ProxySetBean()).isInsecureProfile(true))
    }

    @Test
    fun insecureHighlightCanBeDisabledWithoutChangingClassification() {
        val insecure = profile(SOCKSBean())

        assertTrue(insecure.isInsecureProfile(false))
        assertTrue(insecure.shouldHighlightAsInsecure(false, false))
        assertFalse(insecure.shouldHighlightAsInsecure(false, true))
    }
}
