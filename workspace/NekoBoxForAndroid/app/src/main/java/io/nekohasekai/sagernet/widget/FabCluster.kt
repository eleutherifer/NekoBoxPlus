package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.R as MaterialR
import io.nekohasekai.sagernet.R

/**
 * Anchors the connect button and its progress ring to [StatsBar] as one layout unit.
 *
 * The direct dependent of BottomAppBar deliberately is not a FloatingActionButton. This prevents
 * Material from installing another layout listener and changing the button's anchor gravity and
 * translation while CoordinatorLayout is already moving the anchored cluster with StatsBar.
 */
class FabCluster @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private lateinit var fab: ServiceButton
    private lateinit var progress: CircularProgressIndicator
    private var bottomSystemInset = 0

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val newBottomInset =
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            if (bottomSystemInset != newBottomInset) {
                bottomSystemInset = newBottomInset
                requestLayout()
            }
            insets
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        fab = findViewById(R.id.fab)
        progress = findViewById(R.id.fabProgress)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!this::fab.isInitialized || fab.measuredHeight <= 0) return

        val indicatorSize = fab.measuredHeight + progress.trackThickness
        if (progress.indicatorSize != indicatorSize) {
            progress.indicatorSize = indicatorSize
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val coordinator = parent as? CoordinatorLayout ?: return
        val anchorId = (layoutParams as? CoordinatorLayout.LayoutParams)?.anchorId ?: return
        val statsBar = coordinator.findViewById<StatsBar>(anchorId) ?: return
        syncWithStatsBar(statsBar)
    }

    internal fun syncWithStatsBar(statsBar: StatsBar) {
        updateStandaloneBottomMargin()
        translationY = statsBar.fabClusterTranslationY
        statsBar.updateFabCradle(fab)
    }

    private fun updateStandaloneBottomMargin() {
        val params = layoutParams as? CoordinatorLayout.LayoutParams ?: return
        val contentRect = Rect()
        fab.getMeasuredContentRect(contentRect)
        val contentHeight = contentRect.height()
        if (contentHeight <= 0) return

        val fabShadowPadding = (fab.measuredHeight - contentHeight) / 2
        val clusterPadding = (measuredHeight - fab.measuredHeight) / 2
        val targetMargin = (
            bottomSystemInset +
                resources.getDimensionPixelOffset(MaterialR.dimen.mtrl_bottomappbar_fab_bottom_margin) -
                fabShadowPadding -
                clusterPadding
            ).coerceAtLeast(0)
        if (params.bottomMargin == targetMargin) return

        params.bottomMargin = targetMargin
        layoutParams = params
    }
}
