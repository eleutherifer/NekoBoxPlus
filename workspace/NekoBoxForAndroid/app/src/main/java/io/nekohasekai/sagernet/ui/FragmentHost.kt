package io.nekohasekai.sagernet.ui

import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import io.nekohasekai.sagernet.R

internal fun ThemedActivity.installFragmentHost() {
    setContentView(CoordinatorLayout(this).apply {
        id = R.id.fragment_holder
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    })
}
