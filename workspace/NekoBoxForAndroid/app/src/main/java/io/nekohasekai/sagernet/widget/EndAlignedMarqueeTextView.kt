package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withClip
import kotlin.math.roundToLong

/**
 * End-aligns text that fits and scrolls overflowing text with a width-independent gap.
 */
class EndAlignedMarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val marqueeGap = 24f * resources.displayMetrics.density
    private val marqueeSpeed = 30f * resources.displayMetrics.density
    private var marqueeAnimator: ValueAnimator? = null
    private var marqueeOffset = 0f
    private var marqueeCycleDistance = 0f
    private var textWidth = 0f
    private var overflowing = false

    init {
        ellipsize = null
        setHorizontallyScrolling(true)
        isHorizontalFadingEdgeEnabled = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        updateOverflowState()
    }

    override fun onDraw(canvas: Canvas) {
        if (!overflowing || marqueeCycleDistance <= 0f) {
            super.onDraw(canvas)
            return
        }

        val textLayout = layout ?: return
        paint.drawableState = drawableState
        paint.color = currentTextColor
        val contentHeight = height - compoundPaddingTop - compoundPaddingBottom
        val verticalOffset = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.CENTER_VERTICAL -> (contentHeight - textLayout.height) / 2f
            Gravity.BOTTOM -> (contentHeight - textLayout.height).toFloat()
            else -> 0f
        }
        canvas.withClip(
            compoundPaddingLeft,
            compoundPaddingTop,
            width - compoundPaddingRight,
            height - compoundPaddingBottom,
        ) {
            translate(
                compoundPaddingLeft - marqueeOffset,
                compoundPaddingTop + verticalOffset,
            )
            textLayout.draw(this)
            translate(marqueeCycleDistance, 0f)
            textLayout.draw(this)
        }
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        updateOverflowState()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateOverflowState()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMarquee()
    }

    override fun onDetachedFromWindow() {
        stopMarquee()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateMarquee()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateMarquee()
    }

    private fun updateOverflowState() {
        textWidth = Layout.getDesiredWidth(text, paint)
        val availableWidth = width - compoundPaddingStart - compoundPaddingEnd
        val isOverflowing = availableWidth > 0 && textWidth > availableWidth
        val horizontalGravity = if (isOverflowing) Gravity.START else Gravity.END
        val updatedGravity =
            (gravity and Gravity.HORIZONTAL_GRAVITY_MASK.inv()) or horizontalGravity
        if (gravity != updatedGravity) gravity = updatedGravity

        val cycleDistance = if (isOverflowing) textWidth + marqueeGap else 0f
        if (overflowing != isOverflowing || marqueeCycleDistance != cycleDistance) {
            overflowing = isOverflowing
            marqueeCycleDistance = cycleDistance
            stopMarquee()
        }
        updateMarquee()
    }

    private fun updateMarquee() {
        if (
            !overflowing ||
            marqueeCycleDistance <= 0f ||
            !isAttachedToWindow ||
            windowVisibility != View.VISIBLE ||
            !isShown
        ) {
            stopMarquee()
            return
        }
        if (marqueeAnimator?.isStarted == true) return

        val duration = (marqueeCycleDistance / marqueeSpeed * 1000f).roundToLong()
            .coerceAtLeast(1L)
        marqueeAnimator = ValueAnimator.ofFloat(0f, marqueeCycleDistance).apply {
            this.duration = duration
            startDelay = MARQUEE_INITIAL_DELAY_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                marqueeOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopMarquee() {
        marqueeAnimator?.cancel()
        marqueeAnimator = null
        marqueeOffset = 0f
        invalidate()
    }

    private companion object {
        const val MARQUEE_INITIAL_DELAY_MS = 1_200L
    }
}
