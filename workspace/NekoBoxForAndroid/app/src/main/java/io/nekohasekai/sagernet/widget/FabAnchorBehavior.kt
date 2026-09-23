package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Pins a cradled [FloatingActionButton] to its resting position over the [BottomAppBar].
 *
 * The connect button must sit in the bar's cradle at all times while the bar is relevant, while the
 * bar itself slides up and down independently (it is `performHide()`-ed at Idle so only the button
 * is shown, and `performShow()`-ed once connected). The FAB therefore must NOT ride the bar's
 * translation — doing so hides the button together with the bar and makes connecting impossible.
 *
 * The earlier drift ("snaps", "stays at the bottom", "random position after theme change") comes
 * from Material resetting the FAB's `translationY` during BottomAppBar and FAB layout changes. A
 * Snackbar can trigger the latter while CoordinatorLayout moves views around its inset, so compact
 * mode's custom resting offset must be restored after Material's FAB layout listener runs.
 *
 * Only `translationY` is constrained; the bar still owns `translationX` (fab alignment) and the
 * cradle cutout, while CoordinatorLayout still owns snackbar dodging and inset-aware positioning.
 */
class FabAnchorBehavior(
    context: Context,
    attrs: AttributeSet?,
) : FloatingActionButton.Behavior(context, attrs) {
    private var observedFab: FloatingActionButton? = null

    private val fabLayoutListener = View.OnLayoutChangeListener {
            view, _, _, _, _, _, _, _, _,
        ->
        val fab = view as? FloatingActionButton ?: return@OnLayoutChangeListener
        val parent = fab.parent as? CoordinatorLayout ?: return@OnLayoutChangeListener
        syncRestingTranslation(parent, fab)
    }

    private fun CoordinatorLayout.boundAppBar(child: View): BottomAppBar? =
        getDependencies(child).filterIsInstance<BottomAppBar>().firstOrNull()

    private val BottomAppBar.restingFabTranslationY: Float
        get() = (this as? StatsBar)?.fabRestingTranslationY ?: 0f

    private fun syncRestingTranslation(
        parent: CoordinatorLayout,
        child: FloatingActionButton,
    ): Boolean {
        val targetTranslationY =
            parent.boundAppBar(child)?.restingFabTranslationY ?: return false
        if (child.translationY == targetTranslationY) return false
        child.translationY = targetTranslationY
        return true
    }

    private fun observeLayoutChanges(child: FloatingActionButton) {
        if (observedFab === child) return
        observedFab?.removeOnLayoutChangeListener(fabLayoutListener)
        observedFab = child
        child.addOnLayoutChangeListener(fabLayoutListener)
    }

    override fun onLayoutChild(
        parent: CoordinatorLayout,
        child: FloatingActionButton,
        layoutDirection: Int,
    ): Boolean {
        val handled = super.onLayoutChild(parent, child, layoutDirection)
        // BottomAppBar registers its listener before its dependent FAB is laid out. Register ours
        // after super so our compact offset is the final value on later Snackbar/inset relayouts.
        observeLayoutChanges(child)
        // Re-seed on layout: onDependentViewChanged does not fire on the first layout or right
        // after a recreate/fragment swap, so without this the FAB could rest off-position.
        syncRestingTranslation(parent, child)
        return handled
    }

    override fun onDependentViewChanged(
        parent: CoordinatorLayout,
        child: FloatingActionButton,
        dependency: View,
    ): Boolean {
        val handled = super.onDependentViewChanged(parent, child, dependency)
        if (dependency is BottomAppBar) {
            // Apply our invariant after Material's behavior so it cannot overwrite the resting
            // offset. Returning true also propagates the movement to the anchored progress ring.
            val changed = syncRestingTranslation(parent, child)
            return handled || changed
        }
        return handled
    }

    override fun onDetachedFromLayoutParams() {
        observedFab?.removeOnLayoutChangeListener(fabLayoutListener)
        observedFab = null
        super.onDetachedFromLayoutParams()
    }
}
