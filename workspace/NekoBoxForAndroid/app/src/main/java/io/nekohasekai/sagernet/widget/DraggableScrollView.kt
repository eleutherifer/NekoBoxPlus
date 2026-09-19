package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.view.doOnLayout
import com.google.android.material.color.MaterialColors
import io.nekohasekai.sagernet.R
import kotlin.math.max
import kotlin.math.roundToInt

class DraggableScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    private val touchTargetWidth = 24.dp
    private val minThumbHeight = 48.dp
    private var scrollbarThumb: View? = null
    private var draggingScrollbar = false
    private var scrollbarDragOffset = 0f

    init {
        isVerticalScrollBarEnabled = false
    }

    fun attachScrollbar(track: View, thumb: View) {
        scrollbarThumb = thumb

        track.visibility = View.INVISIBLE
        thumb.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6.dp.toFloat()
            setColor(MaterialColors.getColor(this@DraggableScrollView, R.attr.colorPrimary, Color.MAGENTA))
        }

        doOnLayout { updateScrollbarThumb() }
        getChildAt(0)?.doOnLayout { updateScrollbarThumb() }
    }

    private fun updateScrollbarThumb() {
        val thumb = scrollbarThumb ?: return
        val metrics = getScrollbarMetrics()
        thumb.visibility = if (metrics == null) View.GONE else View.VISIBLE
        if (metrics == null) return

        val params = thumb.layoutParams as ViewGroup.MarginLayoutParams
        if (params.height != metrics.thumbHeight || params.topMargin != metrics.thumbTop.roundToInt()) {
            params.height = metrics.thumbHeight
            params.topMargin = metrics.thumbTop.roundToInt()
            thumb.layoutParams = params
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && isInScrollbarTouchTarget(ev.x)) {
            draggingScrollbar = canScrollVertically()
            if (draggingScrollbar) {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrollbarDragOffset = getScrollbarMetrics()?.let { metrics ->
                    if (ev.y in metrics.thumbTop..metrics.thumbBottom) {
                        ev.y - metrics.thumbTop
                    } else {
                        metrics.thumbHeight / 2f
                    }
                } ?: 0f
                scrollToTouch(ev.y)
                return true
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (draggingScrollbar || isInScrollbarTouchTarget(ev.x)) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    draggingScrollbar = canScrollVertically()
                    if (draggingScrollbar) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        scrollbarDragOffset = getScrollbarMetrics()?.let { metrics ->
                            if (ev.y in metrics.thumbTop..metrics.thumbBottom) {
                                ev.y - metrics.thumbTop
                            } else {
                                metrics.thumbHeight / 2f
                            }
                        } ?: 0f
                        scrollToTouch(ev.y)
                        return true
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (draggingScrollbar) {
                        scrollToTouch(ev.y)
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (draggingScrollbar) {
                        draggingScrollbar = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        invalidate()
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun isInScrollbarTouchTarget(x: Float): Boolean {
        val targetWidth = max(verticalScrollbarWidth, touchTargetWidth)
        return if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            x <= targetWidth
        } else {
            x >= width - targetWidth
        }
    }

    private fun canScrollVertically(): Boolean {
        return getScrollRange() > 0
    }

    private fun scrollToTouch(y: Float) {
        val metrics = getScrollbarMetrics() ?: return

        val touchOffset = (y - paddingTop - scrollbarDragOffset)
            .coerceIn(0f, metrics.scrollTrackHeight.toFloat())
        val targetScrollY = (touchOffset / metrics.scrollTrackHeight * metrics.maxScroll).roundToInt()
        scrollTo(scrollX, targetScrollY)
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        updateScrollbarThumb()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateScrollbarThumb()
    }

    private fun getScrollbarMetrics(): ScrollbarMetrics? {
        val maxScroll = getScrollRange()
        if (maxScroll <= 0) return null

        val availableHeight = height - paddingTop - paddingBottom
        if (availableHeight <= 0) return null
        val contentHeight = getChildAt(0)?.height ?: return null

        val thumbHeight = max(
            minThumbHeight,
            (availableHeight.toFloat() / contentHeight * availableHeight).roundToInt()
        ).coerceAtMost(availableHeight)
        val scrollTrackHeight = availableHeight - thumbHeight
        if (scrollTrackHeight <= 0) return null

        val thumbTop = paddingTop + scrollY.toFloat() / maxScroll * scrollTrackHeight
        return ScrollbarMetrics(
            maxScroll = maxScroll,
            scrollTrackHeight = scrollTrackHeight,
            thumbHeight = thumbHeight,
            thumbTop = thumbTop,
            thumbBottom = thumbTop + thumbHeight,
        )
    }

    private fun getScrollRange(): Int {
        val child = getChildAt(0) ?: return 0
        return (child.height - (height - paddingTop - paddingBottom)).coerceAtLeast(0)
    }

    private data class ScrollbarMetrics(
        val maxScroll: Int,
        val scrollTrackHeight: Int,
        val thumbHeight: Int,
        val thumbTop: Float,
        val thumbBottom: Float,
    )

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()
}
