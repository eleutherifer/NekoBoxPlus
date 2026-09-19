package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutToolsBinding
import io.nekohasekai.sagernet.widget.ListListener

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    companion object {
        private const val ARG_INITIAL_PAGE = "initialPage"
        private const val PAGE_BACKUP = 1

        fun backupPanel() = ToolsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_INITIAL_PAGE, PAGE_BACKUP) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_tools)

        val tools = mutableListOf<NamedFragment>()
        tools.add(NetworkFragment())
        tools.add(BackupFragment())

        val binding = LayoutToolsBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
        binding.toolsPager.adapter = ToolsAdapter(tools)
        binding.toolsPager.setCurrentItem(
            arguments?.getInt(ARG_INITIAL_PAGE, 0) ?: 0,
            false,
        )

        TabLayoutMediator(binding.toolsTab, binding.toolsPager) { tab, position ->
            tab.text = tools[position].name()
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()
    }

    inner class ToolsAdapter(val tools: List<Fragment>) : FragmentStateAdapter(this) {

        override fun getItemCount() = tools.size

        override fun createFragment(position: Int) = tools[position]
    }

}
