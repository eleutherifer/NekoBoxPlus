package io.nekohasekai.sagernet.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ProfileCardMarqueeLayoutTest {

    @Test
    fun normalCardStatusUsesFixedGapMarquee() {
        assertStatusUsesFixedGapMarquee("layout_profile.xml")
    }

    @Test
    fun doubleAndAlternateCardStatusUsesFixedGapMarquee() {
        assertStatusUsesFixedGapMarquee("layout_profile_double.xml")
    }

    @Test
    fun fixedGapMarqueeUsesCurrentMeasurementAndAggregatedVisibility() {
        val source = File(
            "src/main/java/io/nekohasekai/sagernet/widget/EndAlignedMarqueeTextView.kt"
        ).readText()

        assertTrue(source.contains("updateOverflowState(measuredWidth)"))
        assertTrue(source.contains("override fun onVisibilityAggregated(isVisible: Boolean)"))
        assertFalse(source.contains("val availableWidth = width - compoundPaddingStart"))
    }

    private fun assertStatusUsesFixedGapMarquee(layoutName: String) {
        val layout = File("src/main/res/layout/$layoutName")
        assertTrue("$layoutName not found at ${layout.absolutePath}", layout.isFile)

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout)
        val elements = document.getElementsByTagName("*")
        val profileStatus = (0 until elements.length)
            .map { elements.item(it) as Element }
            .single { it.getAttribute("android:id") == "@+id/profile_status" }

        assertEquals(EndAlignedMarqueeTextView::class.java.name, profileStatus.tagName)
    }
}
