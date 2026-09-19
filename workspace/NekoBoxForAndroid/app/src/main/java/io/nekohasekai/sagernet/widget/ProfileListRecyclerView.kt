package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.R

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

    init {
        addItemDecoration(BottomScrollSpaceDecoration(bottomScrollSpace))
    }

    // The space is content rather than padding so FastScroller draws through to the viewport edge.
    override fun getAvailableScrollHeight(adapterHeight: Int, yOffset: Int): Int {
        return super.getAvailableScrollHeight(adapterHeight, yOffset) + bottomScrollSpace
    }

    override fun setAdapter(adapter: RecyclerView.Adapter<*>?) {
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
