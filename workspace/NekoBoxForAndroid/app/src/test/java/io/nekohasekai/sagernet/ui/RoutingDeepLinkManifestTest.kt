package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class RoutingDeepLinkManifestTest {
    @Test
    fun routingPreviewRegistersExternalAndNekoBoxPlusFilters() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val filters = document.getElementsByTagName("intent-filter")
        val registered = buildSet {
            for (index in 0 until filters.length) {
                val filter = filters.item(index) as Element
                if (filter.getAttribute("android:label") != "@string/routing_import_preview") continue
                val data = filter.getElementsByTagName("data").item(0) as Element
                assertEquals("routing", data.getAttribute("android:host"))
                add(data.getAttribute("android:scheme") to data.getAttribute("android:pathPrefix"))
            }
        }
        assertEquals(
            setOf(
                "happ" to "/add/",
                "happ" to "/onadd/",
                "incy" to "/add/",
                "incy" to "/onadd/",
                "sn" to "/add/",
            ),
            registered,
        )
    }
}
