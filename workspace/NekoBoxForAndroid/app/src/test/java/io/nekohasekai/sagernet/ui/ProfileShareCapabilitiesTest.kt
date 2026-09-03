package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileShareCapabilitiesTest {
    @Test
    fun standardProfileSupportsEveryShareGroup() {
        val capabilities = ProfileShareCapabilities.from(ProxyEntity().putBean(VMessBean()))

        assertEquals(ProfileShareCapabilities(true, true, true), capabilities)
    }

    @Test
    fun wireGuardSupportsStandardLinks() {
        val capabilities = ProfileShareCapabilities.from(ProxyEntity().putBean(WireGuardBean()))

        assertEquals(ProfileShareCapabilities(true, true, true), capabilities)
    }

    @Test
    fun chainSupportsConfigurationOnly() {
        val capabilities = ProfileShareCapabilities.from(ProxyEntity().putBean(ChainBean()))

        assertEquals(ProfileShareCapabilities(false, false, true), capabilities)
    }

    @Test
    fun pluginProfileOmitsConfiguration() {
        val capabilities = ProfileShareCapabilities.from(ProxyEntity().putBean(NekoBean()))

        assertEquals(ProfileShareCapabilities(true, false, false), capabilities)
    }
}
