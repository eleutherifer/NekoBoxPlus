package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withClip
import androidx.core.view.isVisible
import com.caverock.androidsvg.SVG
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.utils.ProfileCountryResolver

class CountryBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var flag: SVG? = null
    private val bounds = RectF()
    private val clip = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(entity: ProxyEntity): Boolean {
        if (!DataStore.profileCountryIndicator) {
            flag = null
            isVisible = false
            contentDescription = null
            invalidate()
            return false
        }
        val countryCode = ProfileCountryResolver.effectiveCountryCode(entity)
        flag = CountryFlagRenderer.loadSvg(context, countryCode)
        isVisible = flag != null
        contentDescription = flag?.let {
            context.getString(R.string.profile_country, countryCode)
        }
        invalidate()
        return flag != null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentFlag = flag ?: return
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset()
        clip.addOval(bounds, Path.Direction.CW)
        canvas.withClip(clip) {
            currentFlag.renderToCanvas(this, bounds)
        }
    }

}
