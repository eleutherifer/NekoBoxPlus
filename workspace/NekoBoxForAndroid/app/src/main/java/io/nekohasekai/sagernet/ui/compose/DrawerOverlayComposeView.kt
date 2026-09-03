package io.nekohasekai.sagernet.ui.compose

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import io.nekohasekai.sagernet.ktx.dp2pxf
import kotlin.math.ceil
import kotlin.math.min

internal data class DrawerGestureExclusionBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun drawerGestureExclusionBounds(
    width: Int,
    height: Int,
    edgeWidthPx: Int,
    maxHeightPx: Int,
): DrawerGestureExclusionBounds? {
    if (width <= 0 || height <= 0 || edgeWidthPx <= 0 || maxHeightPx <= 0) return null
    val exclusionHeight = min(height, maxHeightPx)
    val top = (height - exclusionHeight) / 2
    return DrawerGestureExclusionBounds(
        left = 0,
        top = top,
        right = min(width, edgeWidthPx),
        bottom = top + exclusionHeight,
    )
}

internal class DrawerOverlayTouchPolicy(
    private val edgeWidthPx: Float,
) {
    private var trackingEdgeGesture = false

    fun shouldDispatch(action: Int, x: Float, drawerActive: Boolean): Boolean {
        if (action == MotionEvent.ACTION_DOWN) {
            trackingEdgeGesture = drawerActive || x <= edgeWidthPx
        }
        val dispatch = drawerActive || trackingEdgeGesture
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            trackingEdgeGesture = false
        }
        return dispatch
    }
}

internal class DrawerOverlayComposeView(context: Context) : FrameLayout(context) {
    private val edgeWidthPx = ceil(dp2pxf(24)).toInt()
    private val maxExclusionHeightPx = ceil(dp2pxf(200)).toInt()
    private val touchPolicy = DrawerOverlayTouchPolicy(edgeWidthPx.toFloat())
    private val composeView = ComposeView(context).also {
        addView(
            it,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    var drawerActive: () -> Boolean = { false }
    private var exclusionEnabled = true

    fun setContent(content: @Composable () -> Unit) = composeView.setContent(content)

    fun setDrawerActive(active: Boolean) {
        exclusionEnabled = !active
        updateGestureExclusion()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGestureExclusion()
    }

    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val bounds = if (exclusionEnabled) {
            drawerGestureExclusionBounds(width, height, edgeWidthPx, maxExclusionHeightPx)
        } else null
        systemGestureExclusionRects = if (bounds == null) emptyList() else listOf(
            Rect(bounds.left, bounds.top, bounds.right, bounds.bottom),
        )
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!touchPolicy.shouldDispatch(event.actionMasked, event.x, drawerActive())) return false
        return super.dispatchTouchEvent(event)
    }
}
