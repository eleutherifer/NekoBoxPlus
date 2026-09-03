package io.nekohasekai.sagernet.widget

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewConfiguration
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ui.DpadDoublePressTracker

interface TvProfileFocusTarget {
    fun requestTvProfileFocus(): Boolean
}

class ProfileListRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FastScrollRecyclerView(context, attrs, defStyleAttr) {

    private val bottomScrollSpace =
        resources.getDimensionPixelSize(R.dimen.profile_list_bottom_scroll_space)
    private val invalidateDecorations = object : Runnable {
        override fun run() {
            if (isComputingLayout) {
                post(this)
            } else {
                invalidateItemDecorations()
            }
        }
    }
    private val adapterObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = scheduleDecorationInvalidation()

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
            scheduleDecorationInvalidation()

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) =
            scheduleDecorationInvalidation()

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
            scheduleDecorationInvalidation()
    }
    private var observedAdapter: RecyclerView.Adapter<*>? = null
    private val tvDpadDoublePress by lazy {
        DpadDoublePressTracker(ViewConfiguration.getDoubleTapTimeout().toLong())
    }
    private var pendingTvFocusPosition = NO_POSITION
    private val tvChildAttachListener = object : OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            focusPendingTvProfile()
        }

        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }
    private val location = IntArray(2)
    private val overlayLocation = IntArray(2)
    private val tvObstructionListener = ViewTreeObserver.OnPreDrawListener {
        updateTvBottomInset()
        true
    }

    init {
        if (!SagerNet.isTv) addItemDecoration(BottomScrollSpaceDecoration(bottomScrollSpace))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (SagerNet.isTv) {
            viewTreeObserver.addOnPreDrawListener(tvObstructionListener)
            addOnChildAttachStateChangeListener(tvChildAttachListener)
        }
    }

    override fun onDetachedFromWindow() {
        if (SagerNet.isTv && viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnPreDrawListener(tvObstructionListener)
        }
        if (SagerNet.isTv) removeOnChildAttachStateChangeListener(tvChildAttachListener)
        pendingTvFocusPosition = NO_POSITION
        tvDpadDoublePress.reset()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (SagerNet.isTv && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.repeatCount == 0) tvDpadDoublePress.reset()
            } else if (findContainingItemView(findFocus()) != null) {
                if (event.repeatCount == 0 &&
                    tvDpadDoublePress.record(event.keyCode, event.eventTime)
                ) {
                    pendingTvFocusPosition = NO_POSITION
                    return focusTvFab()
                }
                if (moveTvFocusDown()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveTvFocusDown(): Boolean {
        val manager = layoutManager ?: return false
        val itemCount = adapter?.itemCount ?: return false
        val focusedItem = findContainingItemView(findFocus())
        if (focusedItem == null) {
            return focusFirstTvProfile()
        }

        val currentPosition = pendingTvFocusPosition.takeIf { it != NO_POSITION }
            ?: getChildAdapterPosition(focusedItem)
        if (currentPosition == NO_POSITION) return false
        val rowSize = (manager as? GridLayoutManager)?.spanCount ?: 1
        val targetPosition = currentPosition + rowSize
        if (targetPosition >= itemCount) {
            pendingTvFocusPosition = NO_POSITION
            return focusTvFab()
        }

        findViewHolderForAdapterPosition(targetPosition)?.let { target ->
            if ((target as? TvProfileFocusTarget)?.requestTvProfileFocus() == true) {
                pendingTvFocusPosition = NO_POSITION
                return true
            }
        }
        pendingTvFocusPosition = targetPosition
        scrollToPosition(targetPosition)
        postOnAnimation(::focusPendingTvProfile)
        return true
    }

    private fun focusPendingTvProfile() {
        val targetPosition = pendingTvFocusPosition
        if (targetPosition == NO_POSITION) return
        val target = findViewHolderForAdapterPosition(targetPosition) as? TvProfileFocusTarget
            ?: return
        if (target.requestTvProfileFocus()) pendingTvFocusPosition = NO_POSITION
    }

    private fun focusTvFab(): Boolean {
        rootView.findViewById<StatsBar>(R.id.stats)?.revealForTvFocus()
        return rootView.findViewById<View>(R.id.fab)?.requestFocus() == true
    }

    fun focusFirstTvProfile(): Boolean {
        val visibleChildren = (0 until childCount)
            .asSequence()
            .map(::getChildAt)
            .sortedWith(compareBy<View> { it.top }.thenBy { it.left })
        for (child in visibleChildren) {
            val holder = getChildViewHolder(child) as? TvProfileFocusTarget
            if (child.bottom > paddingTop && holder?.requestTvProfileFocus() == true) return true
        }
        return false
    }

    private fun updateTvBottomInset() {
        val activity = context as? Activity ?: return
        getLocationInWindow(location)
        val listBottom = location[1] + height
        val overlayTop = sequenceOf(
            activity.findViewById<View>(R.id.stats),
            activity.findViewById<View>(R.id.fabCluster),
        ).filterNotNull().filter { it.isVisible }.map { overlay ->
            overlay.getLocationInWindow(overlayLocation)
            overlayLocation[1]
        }.filter { it < listBottom }.minOrNull() ?: listBottom
        val desiredBottom = (listBottom - overlayTop + dp2px(8)).coerceAtLeast(0)
        if (paddingBottom != desiredBottom) {
            setPadding(paddingLeft, paddingTop, paddingRight, desiredBottom)
        }
    }

    // The space is content rather than padding so FastScroller draws through to the viewport edge.
    override fun getAvailableScrollHeight(adapterHeight: Int, yOffset: Int): Int {
        return super.getAvailableScrollHeight(adapterHeight, yOffset) + bottomScrollSpace
    }

    override fun setAdapter(adapter: RecyclerView.Adapter<*>?) {
        pendingTvFocusPosition = NO_POSITION
        tvDpadDoublePress.reset()
        observedAdapter?.unregisterAdapterDataObserver(adapterObserver)
        super.setAdapter(adapter)
        observedAdapter = adapter
        adapter?.registerAdapterDataObserver(adapterObserver)
        scheduleDecorationInvalidation()
    }

    private fun scheduleDecorationInvalidation() {
        removeCallbacks(invalidateDecorations)
        post(invalidateDecorations)
    }

    private class BottomScrollSpaceDecoration(
        private val height: Int,
    ) : RecyclerView.ItemDecoration() {

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val itemCount = parent.adapter?.itemCount ?: return
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION || itemCount == 0) return

            val layoutManager = parent.layoutManager
            val isInLastRow = if (layoutManager is GridLayoutManager) {
                val spanSizeLookup = layoutManager.spanSizeLookup
                spanSizeLookup.getSpanGroupIndex(position, layoutManager.spanCount) ==
                    spanSizeLookup.getSpanGroupIndex(itemCount - 1, layoutManager.spanCount)
            } else {
                position == itemCount - 1
            }

            if (isInLastRow) outRect.bottom = height
        }
    }
}
