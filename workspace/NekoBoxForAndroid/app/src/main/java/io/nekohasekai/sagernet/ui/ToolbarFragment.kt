package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    fun toolbarOrNull(): Toolbar? {
        return if (::toolbar.isInitialized) toolbar else null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewToolbar = view.findViewById<Toolbar>(R.id.toolbar) ?: return
        toolbar = viewToolbar
        viewToolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
        viewToolbar.setNavigationContentDescription(R.string.abc_action_bar_up_description)
        viewToolbar.setNavigationOnClickListener {
            (activity as MainActivity).openDrawer()
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
