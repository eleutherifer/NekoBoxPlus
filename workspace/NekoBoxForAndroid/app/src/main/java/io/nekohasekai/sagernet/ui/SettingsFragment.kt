package io.nekohasekai.sagernet.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ListListener

class SettingsFragment : ToolbarFragment(R.layout.layout_config_settings) {

    companion object {
        private const val INTERFACE_GROUP_ID = "interface"
        private var openInterfaceOnCreate = false

        fun restoreInterfaceOnNextCreate() {
            openInterfaceOnCreate = true
        }
    }

    private var searchView: SearchView? = null
    private var searchFragment: SettingsPreferenceFragment? = null
    private var currentGroupId: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        if (openInterfaceOnCreate) {
            openInterfaceOnCreate = false
            openGroup(INTERFACE_GROUP_ID, animate = false)
        } else {
            showTopLevel()
        }
    }

    fun openGroup(groupId: String, highlightKey: String? = null, animate: Boolean = true) {
        hideSearchView()
        currentGroupId = groupId
        searchFragment = null
        toolbar.menu.clear()
        toolbar.setTitle(SettingsPreferenceFragment.groupTitle(groupId))
        toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
        toolbar.setNavigationOnClickListener { showTopLevel() }

        replaceSettingsFragment(
            SettingsPreferenceFragment.forGroup(groupId, highlightKey),
            if (animate) Direction.FORWARD else Direction.NONE,
        )
    }

    private fun showTopLevel() {
        val direction = if (currentGroupId != null) Direction.BACK else Direction.NONE
        currentGroupId = null
        hideSearchView()
        toolbar.menu.clear()
        toolbar.setTitle(R.string.settings)
        toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
        toolbar.setNavigationOnClickListener {
            (activity as MainActivity).binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
        }
        toolbar.inflateMenu(R.menu.settings_menu)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings_search) {
                showSearch()
                true
            } else {
                false
            }
        }

        replaceSettingsFragment(SettingsPreferenceFragment.topLevel(), direction)
    }

    private fun showSearch() {
        currentGroupId = null
        toolbar.menu.clear()
        toolbar.title = null
        toolbar.setNavigationIcon(null)

        val view = SearchView(requireContext()).apply {
            queryHint = getString(R.string.settings_search_hint)
            isIconified = false
            maxWidth = Int.MAX_VALUE
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    searchFragment?.updateSearch(query.orEmpty())
                    clearFocus()
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    searchFragment?.updateSearch(newText.orEmpty())
                    return true
                }
            })
            setOnCloseListener {
                showTopLevel()
                true
            }
        }
        toolbar.addView(
            view,
            androidx.appcompat.widget.Toolbar.LayoutParams(
                androidx.appcompat.widget.Toolbar.LayoutParams.MATCH_PARENT,
                androidx.appcompat.widget.Toolbar.LayoutParams.MATCH_PARENT,
            )
        )
        searchView = view

        searchFragment = SettingsPreferenceFragment.searchResults().also { fragment ->
            replaceSettingsFragment(fragment, Direction.FORWARD)
        }

        view.post {
            view.requestFocus()
            view.findViewById<View>(androidx.appcompat.R.id.search_src_text)?.requestFocus()
            view.findViewById<View>(androidx.appcompat.R.id.search_close_btn)?.setOnClickListener {
                showTopLevel()
            }
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view.findViewById(androidx.appcompat.R.id.search_src_text), InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideSearchView() {
        searchView?.let {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
            toolbar.removeView(it)
        }
        searchView = null
        searchFragment = null
    }

    private fun replaceSettingsFragment(fragment: Fragment, direction: Direction) {
        childFragmentManager.commitNow(allowStateLoss = true) {
            replace(R.id.settings, fragment)
        }
        animateNewSettingsView(fragment.view, direction)
    }

    private fun animateNewSettingsView(view: View?, direction: Direction) {
        if (view == null || direction == Direction.NONE) return
        val offset = resources.displayMetrics.widthPixels * 0.08f
        view.translationX = when (direction) {
            Direction.FORWARD -> offset
            Direction.BACK -> -offset
            Direction.NONE -> 0f
        }
        view.alpha = 0.82f
        view.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(180L)
            .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                requireContext(),
                android.R.interpolator.fast_out_slow_in,
            ))
            .start()
    }

    fun syncServiceState() {
        (childFragmentManager.findFragmentById(R.id.settings) as? SettingsPreferenceFragment)
            ?.syncServiceState()
    }

    override fun onBackPressed(): Boolean {
        if (searchView != null) {
            showTopLevel()
            return true
        }
        if (currentGroupId != null) {
            showTopLevel()
            return true
        }
        return false
    }

    private enum class Direction { NONE, FORWARD, BACK }

}
