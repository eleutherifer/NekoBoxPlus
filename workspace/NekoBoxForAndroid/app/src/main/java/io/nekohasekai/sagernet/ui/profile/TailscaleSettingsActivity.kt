package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.compose.TailscaleProfileSettingsScreen
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class TailscaleSettingsActivity : ProfileSettingsActivity<TailscaleBean>() {
    override val usesComposePreferences = true

    override fun createEntity() = TailscaleBean().applyDefaultValues()

    private val binding = PreferenceBindingManager().apply {
        add(PreferenceBinding(Type.Text, "name"))
        add(PreferenceBinding(Type.Text, "authKey"))
        add(PreferenceBinding(Type.Text, "controlURL"))
        add(PreferenceBinding(Type.Bool, "ephemeral"))
        add(PreferenceBinding(Type.Text, "hostname"))
        add(PreferenceBinding(Type.Bool, "acceptRoutes"))
        add(PreferenceBinding(Type.Text, "exitNode"))
        add(PreferenceBinding(Type.Bool, "exitNodeAllowLANAccess"))
        add(PreferenceBinding(Type.Text, "advertiseRoutes"))
        add(PreferenceBinding(Type.Bool, "advertiseExitNode"))
        add(PreferenceBinding(Type.Text, "advertiseTags"))
        add(PreferenceBinding(Type.TextToInt, "relayServerPort"))
        add(PreferenceBinding(Type.Text, "relayServerStaticEndpoints"))
        add(PreferenceBinding(Type.Text, "udpTimeout"))
        add(PreferenceBinding(Type.Bool, "magicDNS"))
    }

    override fun TailscaleBean.init() = binding.writeToCacheAll(this)

    override fun TailscaleBean.serialize() = binding.fromCacheAll(this)

    @Composable
    override fun ComposePreferences() = TailscaleProfileSettingsScreen()
}
