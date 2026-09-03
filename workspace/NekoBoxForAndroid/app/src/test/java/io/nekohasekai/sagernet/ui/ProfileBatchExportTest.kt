package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class ProfileBatchExportTest {
    @Test
    fun sanitizesUnsafeAndPathFileNames() {
        assertEquals(
            "profile_name_.json",
            ProfileBatchExport.sanitizeFileName("../folder/profile:name?.json", "fallback.txt"),
        )
        assertEquals(
            "fallback.txt",
            ProfileBatchExport.sanitizeFileName("..", "fallback.txt"),
        )
    }

    @Test
    fun zipKeepsEveryConfigurationAndDisambiguatesNames() {
        val entries = listOf(
            ProfileBatchExportEntry("One", "profile.conf", "first"),
            ProfileBatchExportEntry("Two", "profile.conf", "second"),
            ProfileBatchExportEntry("Three", "../bad:name.json", "third"),
        )

        val contents = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(ProfileBatchExport.configurationZip(entries))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                contents[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }

        assertEquals(setOf("profile.conf", "profile (2).conf", "bad_name.json"), contents.keys)
        assertEquals(listOf("first", "second", "third"), contents.values.toList())
    }

    @Test
    fun configurationClipboardUsesReadableSeparators() {
        val text = ProfileBatchExport.configurationClipboardText(
            listOf(
                ProfileBatchExportEntry("One", "one.json", "{}"),
                ProfileBatchExportEntry("Two", "two.conf", "value"),
            )
        )

        assertTrue(text.contains("# One (one.json)\n{}"))
        assertTrue(text.contains("# Two (two.conf)\nvalue"))
    }

    @Test
    fun amneziaJsonExportSkipsOtherProfileTypes() {
        val awg = AmneziaWGBean().apply {
            initializeDefaultValues()
            name = "AWG"
            serverAddress = "example.com"
            serverPort = 51820
            localAddress = "10.0.0.2/32"
            privateKey = "private"
            peerPublicKey = "public"
        }
        val result = ProfileBatchExport.amneziaWGJson(
            listOf(ProxyEntity().putBean(awg), ProxyEntity().putBean(WireGuardBean())),
        )

        assertEquals(1, result.skipped)
        assertEquals("amneziawg.json", result.entries.single().fileName)
        assertEquals(1, JSONObject(result.text).getJSONArray("servers").length())
    }
}
