package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ui.compose.LogcatScreen
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.SendLog

class LogcatFragment : ToolbarFragment() {

    private val viewModel: LogcatViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NekoComposeTheme {
                LogcatScreen(
                    viewModel = viewModel,
                    onOpenDrawer = { (requireActivity() as MainActivity).openDrawer() },
                    onSendLog = {
                        val context = requireContext()
                        runOnDefaultDispatcher { SendLog.sendLog(context, "NB4A") }
                    },
                    onCopyLog = ::copyAllLogs,
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.errors.collect { snackbar(it).show() }
            }
        }
        viewModel.initialize()
    }

    private fun copyAllLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            val log = withContext(Dispatchers.IO) { SendLog.buildLog() }
            val copied = SagerNet.trySetPrimaryClip(log)
            Toast.makeText(
                requireContext(),
                if (copied) R.string.logs_copied else R.string.action_export_err,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
