package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import kotlin.math.max

/**
 * Places ping before traffic on one line when they fit. When they do not fit,
 * traffic occupies the first line and ping the second. Both lines are end-aligned.
 */
class ProfileStatsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private val itemSpacing = (8 * resources.displayMetrics.density).toInt()
    private var singleLine = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd)
            .coerceAtLeast(0)
        val childWidthSpec = MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        visibleChildren().forEach { it.measure(childWidthSpec, childHeightSpec) }

        val ping = getChildAt(0).takeUnless { it.isGone }
        val traffic = getChildAt(1).takeUnless { it.isGone }
        singleLine = ping != null && traffic != null &&
                ping.measuredWidth + itemSpacing + traffic.measuredWidth <= availableWidth

        val contentHeight = when {
            ping == null -> traffic?.measuredHeight ?: 0
            traffic == null -> ping.measuredHeight
            singleLine -> max(ping.measuredHeight, traffic.measuredHeight)
            else -> traffic.measuredHeight + ping.measuredHeight
        }
        val desiredWidth = paddingStart + paddingEnd + when {
            ping == null -> traffic?.measuredWidth ?: 0
            traffic == null -> ping.measuredWidth
            singleLine -> ping.measuredWidth + itemSpacing + traffic.measuredWidth
            else -> max(ping.measuredWidth, traffic.measuredWidth)
        }
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(paddingTop + contentHeight + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val ping = getChildAt(0).takeUnless { it.isGone }
        val traffic = getChildAt(1).takeUnless { it.isGone }
        val end = width - paddingEnd

        if (singleLine && ping != null && traffic != null) {
            traffic.layout(
                end - traffic.measuredWidth,
                paddingTop,
                end,
                paddingTop + traffic.measuredHeight,
            )
            val pingEnd = traffic.left - itemSpacing
            ping.layout(
                pingEnd - ping.measuredWidth,
                paddingTop,
                pingEnd,
                paddingTop + ping.measuredHeight,
            )
            return
        }

        var childTop = paddingTop
        traffic?.let {
            it.layout(
                paddingStart,
                childTop,
                paddingStart + it.measuredWidth,
                childTop + it.measuredHeight,
            )
            childTop += it.measuredHeight
        }
        ping?.layout(end - ping.measuredWidth, childTop, end, childTop + ping.measuredHeight)
    }

    private fun visibleChildren(): List<View> =
        (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
}
