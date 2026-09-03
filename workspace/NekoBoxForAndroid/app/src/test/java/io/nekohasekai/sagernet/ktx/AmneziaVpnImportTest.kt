package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.DeflaterOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneziaVpnImportTest {
    @Test
    fun `AWG last config JSON imports 30 and 31 parameters`() {
        val nativeConfig =
            """
            [Interface]
            PrivateKey = private
            Address = 10.0.0.2/32
            HeaderProtectionKey = embedded-key
            RandomTrailers = off

            [Peer]
            PublicKey = stale-public
            Endpoint = stale.example.com:1234
            """.trimIndent()
        val lastConfig =
            JSONObject()
                .put("config", nativeConfig)
                .put("hostName", "awg.example.com")
                .put("port", 51820)
                .put("server_pub_key", "public")
                .put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0"))
                .put("persistent_keep_alive", "25-35")
                .put("HeaderProtectionKey", "json-key")
                .put("ContentPaddingAddition", "10-100")
                .put("RekeyAfterTime", "100-120")
                .put("RekeyTimeout", "3-7")
                .put("RejectAfterTime", "150-180")
                .put("KeepaliveTimeout", "5-15")
                .put("MaxHandshakeAttempts", "15-20")
                .put("RandomTrailers", "on")
                .put("DisableCookies", "ON")
        val root =
            JSONObject()
                .put("description", "Amnezia AWG")
                .put(
                    "containers",
                    JSONArray().put(
                        JSONObject()
                            .put("container", "amnezia-awg2")
                            .put("awg", JSONObject().put("last_config", lastConfig.toString())),
                    ),
                )

        val bean = parseAmneziaVpnPayload(qCompress(root.toString()))
            .single() as AmneziaWGBean

        assertEquals("Amnezia AWG", bean.name)
        assertEquals("awg.example.com", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals("public", bean.peerPublicKey)
        assertEquals("json-key", bean.headerProtectionKey)
        assertEquals("10-100", bean.contentPaddingAddition)
        assertEquals("100-120", bean.rekeyAfterTime)
        assertEquals("3-7", bean.rekeyTimeout)
        assertEquals("150-180", bean.rejectAfterTime)
        assertEquals("5-15", bean.keepaliveTimeout)
        assertEquals("15-20", bean.maxHandshakeAttempts)
        assertEquals("25-35", bean.peerPersistentKeepalive)
        assertTrue(bean.randomTrailers)
        assertTrue(bean.disableCookies)
    }

    private fun qCompress(value: String): ByteArray {
        val input = value.toByteArray()
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(input) }
        }.toByteArray()
        return ByteBuffer.allocate(Int.SIZE_BYTES + compressed.size)
            .putInt(input.size)
            .put(compressed)
            .array()
    }
}
