package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class RawCustomConfigRendererTest {
    @Test
    fun nonTestBuildPreservesRawConfigExactly() {
        val raw = """{"outbounds":[{"type":"direct","tag":"direct"}]}"""
        val proxy = customConfig(raw)

        val result = RawCustomConfigRenderer.render(proxy, false, UrlTestDnsDefaults(emptyList(), "5s"))

        assertEquals(raw, result?.config)
        assertSame(proxy, result?.trafficMap?.get("Custom")?.single())
    }

    @Test
    fun testBuildInjectsDnsOnlyWhenMissing() {
        val proxy = customConfig(
            """{"route":{"final":"proxy"},"outbounds":[{"type":"socks","tag":"proxy"}]}""",
        )

        val result = RawCustomConfigRenderer.render(
            proxy,
            true,
            UrlTestDnsDefaults(listOf("https://dns.example/dns-query"), "4s"),
        )
        @Suppress("UNCHECKED_CAST")
        val config = gson.fromJson(result!!.config, Map::class.java) as Map<String, Any?>

        assertNotNull(config["dns"])
        assertEquals(9L, result.mainEntId)
        assertEquals("Custom", result.profileTagMap[9L])
    }

    private fun customConfig(config: String) = ProxyEntity(id = 9L).putBean(
        ConfigBean().apply {
            initializeDefaultValues()
            name = "Custom"
            type = 0
            this.config = config
        },
    )
}
