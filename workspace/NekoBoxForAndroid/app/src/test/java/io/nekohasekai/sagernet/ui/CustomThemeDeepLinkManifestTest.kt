package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CustomThemeDeepLinkManifestTest {

    @Test
    fun customThemeImportFilterRegistersSnHost() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml not found at ${manifest.absolutePath}", manifest.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val filters = document.getElementsByTagName("intent-filter")

        for (index in 0 until filters.length) {
            val filter = filters.item(index) as Element
            if (filter.getAttribute("android:label") != "@string/import_custom_theme") continue
            val data = filter.getElementsByTagName("data")
            for (dataIndex in 0 until data.length) {
                val item = data.item(dataIndex) as Element
                if (item.getAttribute("android:scheme") == "sn" &&
                    item.getAttribute("android:host") == "customtheme"
                ) return
            }
        }

        error("sn://customtheme intent-filter was not found")
    }
}
