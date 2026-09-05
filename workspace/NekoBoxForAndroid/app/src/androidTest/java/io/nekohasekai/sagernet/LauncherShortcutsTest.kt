package io.nekohasekai.sagernet

import android.content.Context
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@SdkSuppress(minSdkVersion = 25)
class LauncherShortcutsTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manifestShortcutsTargetThisVariantAndUseDistinctVisibleIcons() {
        val shortcuts = context.getSystemService(ShortcutManager::class.java).manifestShortcuts
        assertEquals(EXPECTED_IDS, shortcuts.mapTo(linkedSetOf()) { it.id })
        assertEquals(EXPECTED_ICONS.size, EXPECTED_ICONS.values.distinct().size)

        shortcuts.forEach { shortcut ->
            val component = requireNotNull(shortcut.intents).last().component
            assertNotNull("${shortcut.id} must use an explicit component", component)
            requireNotNull(component)
            assertEquals(context.packageName, component.packageName)
            assertTrue(
                "${shortcut.id} must target an exported activity",
                context.packageManager.getActivityInfo(component, 0).exported,
            )

            val drawable = context.getDrawable(requireNotNull(EXPECTED_ICONS[shortcut.id]))
            assertNotNull("${shortcut.id} must have a drawable icon", drawable)
            requireNotNull(drawable)
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(
                "${shortcut.id} icon must contain a visible dark foreground",
                pixels.any { pixel ->
                    val alpha = pixel ushr 24
                    val red = pixel shr 16 and 0xff
                    val green = pixel shr 8 and 0xff
                    val blue = pixel and 0xff
                    alpha > 0x7f && red + green + blue < 0x180
                },
            )
        }
    }

    private companion object {
        val EXPECTED_IDS = setOf("toggle", "enable", "disable", "scan")
        val EXPECTED_ICONS = mapOf(
            "toggle" to R.drawable.ic_qu_shadowsocks_launcher,
            "enable" to R.drawable.ic_qu_enable_launcher,
            "disable" to R.drawable.ic_qu_disable_launcher,
            "scan" to R.drawable.ic_qu_scan_launcher,
        )
    }
}
