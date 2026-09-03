package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.compose.NekoComposeTheme
import io.nekohasekai.sagernet.ui.compose.NetworkToolsScreen

class NetworkFragment : NamedFragment() {

    override fun name0() = app.getString(R.string.tools_network)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            NekoComposeTheme {
                NetworkToolsScreen(
                    onStunTest = { open(StunActivity::class.java) },
                    onSpeedTest = { open(SpeedTestActivity::class.java) },
                    onRuleSetMatch = { open(RuleSetMatchActivity::class.java) },
                    onCellularNetwork = { open(CellularNetworkActivity::class.java) },
                )
            }
        }
    }

    private fun open(activityClass: Class<*>) {
        startActivity(Intent(requireContext(), activityClass))
    }
}
