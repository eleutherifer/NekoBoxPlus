package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr

internal fun View.installTvFocusOutline() {
    if (getTag(R.id.tag_tv_focus_outline) == true) return
    val radius = dp2px(8).toFloat()
    val focused = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = radius
        setStroke(dp2px(3), context.getColorAttr(R.attr.colorPrimary))
    }
    val normal = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
    foreground = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(), normal)
    }
    isFocusable = true
    isFocusableInTouchMode = true
    setTag(R.id.tag_tv_focus_outline, true)
}
