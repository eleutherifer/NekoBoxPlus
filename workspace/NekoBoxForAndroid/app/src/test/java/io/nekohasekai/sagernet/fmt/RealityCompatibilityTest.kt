package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class RealityCompatibilityTest {
    private fun tls(fp: String = "ios", policy: String = "preserve") = linkedMapOf<String, Any?>(
        "enabled" to true,
        "utls" to linkedMapOf<String, Any?>("enabled" to true, "fingerprint" to fp),
        "reality" to linkedMapOf<String, Any?>("enabled" to true, "key_share_policy" to policy),
    )

    @Test fun modesCoverNestedDownloadsAndLeaveInboundsAlone() {
        for (mode in listOf("preserve", "ensure_hybrid", "chrome")) {
            val tls = tls()
            val inbound = tls()
            val config = linkedMapOf<String, Any?>(
                "outbounds" to listOf(linkedMapOf("transport" to linkedMapOf("download" to linkedMapOf("tls" to tls)))),
                "inbounds" to listOf(linkedMapOf("tls" to inbound)),
            )
            applyRealityCompatibility(config, mode)
            val once = config.toString()
            applyRealityCompatibility(config, mode)
            assertEquals(once, config.toString())
            assertEquals(if (mode == "chrome") "chrome" else "ios", (tls["utls"] as Map<*, *>)["fingerprint"])
            assertEquals(if (mode == "ensure_hybrid") "ensure_hybrid" else "preserve", (tls["reality"] as Map<*, *>)["key_share_policy"])
            assertEquals("ios", (inbound["utls"] as Map<*, *>)["fingerprint"])
        }
    }

    @Test fun noOpRetainsExplicitProfilePolicy() {
        val tls = tls(policy = "ensure_hybrid")
        val config = linkedMapOf<String, Any?>("outbounds" to listOf(linkedMapOf("tls" to tls)))
        val before = config.toString()
        applyRealityCompatibility(config, "preserve")
        assertEquals(before, config.toString())
    }

    @Test fun compatibilityDistinguishesMlkemFromKyberAndRandom() {
        for (fp in listOf("chrome", "chrome_131", "firefox_148", "safari_26_3", "HelloChrome_133", "edge")) assertTrue(fp, supportsRealityHybrid(fp))
        for (fp in listOf("ios", "chrome_120_pq", "chrome_115_pq", "random", "randomized", "android")) assertFalse(fp, supportsRealityHybrid(fp))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidVerificationKeyFailsRatherThanDisablingVerification() {
        val tls = tls()
        @Suppress("UNCHECKED_CAST")
        (tls["reality"] as MutableMap<String, Any?>)["mldsa65_verify"] = "bad"
        applyRealityCompatibility(linkedMapOf("outbounds" to listOf(linkedMapOf("tls" to tls))), "preserve")
    }
}
