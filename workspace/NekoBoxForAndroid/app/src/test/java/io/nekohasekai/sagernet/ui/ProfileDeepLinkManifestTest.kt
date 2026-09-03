package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ProfileDeepLinkManifestTest {

    @Test
    fun profileImportFilterRegistersSupportedCustomSchemes() {
        val schemes = profileImportSchemes()

        assertEquals(
            setOf(
                "sn",
                "ss",
                "ssr",
                "socks",
                "socks4",
                "socks4a",
                "socks5",
                "vmess",
                "vless",
                "trojan",
                "trojan-go",
                "snell",
                "naive+https",
                "naive+quic",
                "hysteria",
                "hysteria2",
                "hy2",
                "tuic",
                "juicity",
                "mierus",
                "tt",
                "anytls",
                "stormdns",
                "vpn",
                "openvpn",
            ),
            schemes,
        )
    }

    @Test
    fun profileImportFilterDoesNotClaimOrdinaryWebLinks() {
        val schemes = profileImportSchemes()

        assertFalse("http should remain available for browsers", "http" in schemes)
        assertFalse("https should remain available for browsers", "https" in schemes)
    }

    @Test
    fun happCryptFilterIsRegisteredSeparatelyFromSupportedProfiles() {
        val manifest = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)
        val filters = document.getElementsByTagName("intent-filter")

        for (index in 0 until filters.length) {
            val filter = filters.item(index) as Element
            if (filter.getAttribute("android:label") != "@string/happ_crypt_unsupported_title") {
                continue
            }
            val data = filter.getElementsByTagName("data").item(0) as Element
            assertEquals("happ", data.getAttribute("android:scheme"))
            assertEquals("crypt", data.getAttribute("android:host"))
            return
        }

        error("Happ crypt intent-filter was not found")
    }

    private fun profileImportSchemes(): Set<String> {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml not found at ${manifest.absolutePath}", manifest.isFile)

        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)
        val filters = document.getElementsByTagName("intent-filter")
        for (index in 0 until filters.length) {
            val filter = filters.item(index) as Element
            if (filter.getAttribute("android:label") != "@string/profile_import") continue

            val data = filter.getElementsByTagName("data")
            return buildSet {
                for (dataIndex in 0 until data.length) {
                    val item = data.item(dataIndex) as Element
                    add(item.getAttribute("android:scheme"))
                }
            }
        }

        error("profile_import intent-filter was not found")
    }
}
