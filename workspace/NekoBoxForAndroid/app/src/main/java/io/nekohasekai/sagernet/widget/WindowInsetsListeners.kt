package io.nekohasekai.sagernet.widget

import android.view.View
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.util.WeakHashMap

object ListListener : OnApplyWindowInsetsListener {
    private val initialBottomPadding = WeakHashMap<View, Int>()

    override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat) = insets.apply {
        val initialBottom = initialBottomPadding.getOrPut(view) { view.paddingBottom }
        view.updatePadding(
            bottom = initialBottom + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        )
    }
}
