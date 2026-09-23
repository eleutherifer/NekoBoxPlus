package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.progressindicator.CircularProgressIndicator

class FabProgressBehavior(
    context: Context,
    attrs: AttributeSet?,
) : CoordinatorLayout.Behavior<CircularProgressIndicator>(context, attrs) {
    override fun layoutDependsOn(
        parent: CoordinatorLayout,
        child: CircularProgressIndicator,
        dependency: View,
    ): Boolean = dependency.id == (child.layoutParams as CoordinatorLayout.LayoutParams).anchorId

    override fun onLayoutChild(
        parent: CoordinatorLayout,
        child: CircularProgressIndicator,
        layoutDirection: Int,
    ): Boolean {
        val fab = parent.getDependencies(child).single()
        // CoordinatorLayout includes the FAB's transformed bounds when laying out this anchored
        // view. Mirroring its translation here would apply compact mode's vertical offset twice.
        // Keep the ring's own translation clear and let the anchor follow every FAB movement.
        child.translationX = 0f
        child.translationY = 0f
        val size = fab.measuredHeight + child.trackThickness
        if (child.indicatorSize != size) {
            child.indicatorSize = size
        }
        // Returning false delegates the actual layout to CoordinatorLayout, which centers the
        // ring on the FAB anchor using its current translated position.
        return false
    }
}
