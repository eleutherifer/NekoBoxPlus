package io.nekohasekai.sagernet.fmt

import okio.ByteString.Companion.decodeBase64
import java.util.Locale

/** Matches the presets in the pinned sing-box/uTLS sources, including core aliases. */
internal fun supportsRealityHybrid(fingerprint: String): Boolean {
    val name = fingerprint.lowercase(Locale.ROOT).removePrefix("hello")
    return name in setOf(
        "", "chrome", "chrome_auto", "chrome_131", "chrome_133", "chrome_141_ta", "chrome_144_ta_pqs",
        "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk",
        "firefox", "firefox_auto", "firefox_148", "edge", "edge_auto",
        "safari", "safari_auto", "safari_26_3",
    )
}

/** Operates only on effective config maps; never changes the saved profile. */
internal fun applyRealityCompatibility(config: MutableMap<String, Any?>, behavior: String) {
    fun visit(value: Any?, outbound: Boolean) {
        when (value) {
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as MutableMap<String, Any?>
                if (outbound) {
                    @Suppress("UNCHECKED_CAST")
                    val tls = map["tls"] as? MutableMap<String, Any?>
                    @Suppress("UNCHECKED_CAST")
                    val reality = tls?.get("reality") as? MutableMap<String, Any?>
                    if (tls?.get("enabled") == true && reality?.get("enabled") == true) {
                        val verify = reality["mldsa65_verify"] as? String
                        if (!verify.isNullOrEmpty()) {
                            require(verify.matches(Regex("[A-Za-z0-9_-]+")) &&
                                verify.decodeBase64()?.size == 1952) {
                                "Invalid REALITY mldsa65_verify: expected an unpadded base64url ML-DSA-65 public key (1952 bytes)"
                            }
                        }
                        if (behavior == "ensure_hybrid") reality["key_share_policy"] = "ensure_hybrid"
                        if (behavior == "chrome") {
                            @Suppress("UNCHECKED_CAST")
                            val utls = tls["utls"] as? MutableMap<String, Any?>
                            if (utls?.get("enabled") == true) {
                                if (!supportsRealityHybrid(utls["fingerprint"] as? String ?: "")) utls["fingerprint"] = "chrome"
                            }
                            reality["key_share_policy"] = "preserve"
                        }
                    }
                }
                map.forEach { (key, child) -> if (key != "inbounds") visit(child, outbound || key == "outbounds" || key == "endpoints") }
            }
            is List<*> -> value.forEach { visit(it, outbound) }
        }
    }
    visit(config, false)
}
