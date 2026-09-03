package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.ToolsScreen

class ToolsFragment : ToolbarFragment() {

    companion object {
        private const val ARG_INITIAL_PAGE = "initialPage"
        private const val PAGE_BACKUP = 1

        fun backupPanel() = ToolsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_INITIAL_PAGE, PAGE_BACKUP) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val tools = mutableListOf<NamedFragment>()
        tools.add(NetworkFragment())
        tools.add(BackupFragment())
        val initialPage = arguments?.getInt(ARG_INITIAL_PAGE, 0) ?: 0
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                var selectedPage by rememberSaveable { mutableIntStateOf(initialPage) }
                NekoComposeTheme {
                    ToolsScreen(
                        pageNames = tools.map { it.name() },
                        selectedPage = selectedPage,
                        onOpenDrawer = { (requireActivity() as MainActivity).openDrawer() },
                        onPageSelected = { selectedPage = it },
                        createPager = {
                            ViewPager2(requireContext()).apply {
                                id = View.generateViewId()
                                adapter = ToolsAdapter(tools)
                                setCurrentItem(initialPage, false)
                                if (SagerNet.isTv) {
                                    isUserInputEnabled = false
                                    isFocusable = false
                                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                                    (getChildAt(0) as? RecyclerView)?.apply {
                                        isFocusable = false
                                        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                                    }
                                }
                                registerOnPageChangeCallback(
                                    object : ViewPager2.OnPageChangeCallback() {
                                        override fun onPageSelected(position: Int) {
                                            selectedPage = position
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    inner class ToolsAdapter(val tools: List<Fragment>) : FragmentStateAdapter(this) {

        override fun getItemCount() = tools.size

        override fun createFragment(position: Int) = tools[position]
    }

}
