package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import com.caverock.androidsvg.SVG
import io.nekohasekai.sagernet.ktx.Logs
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object CountryFlagRenderer {
    private val cache = ConcurrentHashMap<String, SVG>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    fun loadSvg(context: Context, countryCode: String): SVG? {
        val code = countryCode.lowercase(Locale.ROOT).takeIf {
            it.length == 2 && it.all { character -> character in 'a'..'z' }
        } ?: return null
        cache[code]?.let { return it }
        if (code in missing) return null
        return runCatching {
            SVG.getFromAsset(context.assets, "flags/1x1/$code.svg")
        }.onSuccess {
            cache[code] = it
        }.onFailure {
            missing += code
            Logs.w(it)
        }.getOrNull()
    }

    fun renderNotificationIcon(context: Context, countryCode: String): Bitmap? {
        val flag = loadSvg(context, countryCode) ?: return null
        val resources = context.resources
        val width = resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        val height = resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
        val size = minOf(width, height)
        if (size <= 0) return null

        return runCatching {
            createBitmap(size, size).apply {
                val bounds = RectF(0f, 0f, size.toFloat(), size.toFloat())
                val clip = Path().apply { addOval(bounds, Path.Direction.CW) }
                Canvas(this).withClip(clip) {
                    flag.renderToCanvas(this, bounds)
                }
            }
        }.onFailure(Logs::w).getOrNull()
    }

    fun renderCircularIcon(context: Context, countryCode: String, size: Int): Bitmap? {
        if (size <= 0) return null
        val flag = loadSvg(context, countryCode) ?: return null
        return runCatching {
            createBitmap(size, size).apply {
                val bounds = RectF(0f, 0f, size.toFloat(), size.toFloat())
                val clip = Path().apply { addOval(bounds, Path.Direction.CW) }
                Canvas(this).withClip(clip) {
                    flag.renderToCanvas(this, bounds)
                }
            }
        }.onFailure(Logs::w).getOrNull()
    }
}
