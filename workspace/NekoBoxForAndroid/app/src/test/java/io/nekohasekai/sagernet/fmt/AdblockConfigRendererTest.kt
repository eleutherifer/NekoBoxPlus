package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdblockConfigRendererTest {
    @Test
    fun `requires both filtering content and target packages`() {
        assertNull(AdblockConfigRenderer.render(input(sources = emptyList())))
        assertNull(AdblockConfigRenderer.render(input(packages = emptyList())))
    }

    @Test
    fun `renders filters constraints and TLS from explicit input`() {
        val result = checkNotNull(
            AdblockConfigRenderer.render(
                input(
                    systemWide = false,
                    mixedLan = true,
                    tls = AdblockTlsConfig("cert", "key", skipEv = true, cronet = false, fingerprint = "chrome"),
                ),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val lists = ((result["filters"] as Map<String, Any>)["lists"] as List<Map<String, Any>>)
        assertEquals(mapOf("url" to "https://example.test/list", "format" to "adblock", "trust" to true), lists.single())
        @Suppress("UNCHECKED_CAST")
        val constraints = result["constraints"] as List<Map<String, Any>>
        assertEquals(listOf("app.test"), constraints.first()["package_name"])
        assertEquals(listOf(TAG_MIXED), constraints.last()["inbound"])
        @Suppress("UNCHECKED_CAST")
        val tls = result["tls"] as Map<String, Any>
        assertEquals("chrome", tls["utls"])
    }

    private fun input(
        sources: List<AdblockFilterSource> = listOf(AdblockFilterSource("https://example.test/list", "ADBLOCK", true)),
        packages: List<String> = listOf("app.test"),
        systemWide: Boolean = true,
        mixedLan: Boolean = false,
        tls: AdblockTlsConfig? = null,
    ) = AdblockConfigInput(
        sources = sources,
        rules = emptyList(),
        includedPackages = packages,
        dnsFiltering = true,
        httpFiltering = true,
        httpsFiltering = tls != null,
        cnameUncloaking = true,
        systemWide = systemWide,
        mixedLanFiltering = mixedLan,
        hasMixedInbound = true,
        databasePath = "db",
        resourcesPath = "resources",
        tls = tls,
    )
}
