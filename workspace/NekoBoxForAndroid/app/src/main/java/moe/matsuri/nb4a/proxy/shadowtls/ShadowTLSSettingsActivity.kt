package moe.matsuri.nb4a.proxy.shadowtls

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.ui.compose.ShadowTLSProfileSettingsScreen
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class ShadowTLSSettingsActivity : ProfileSettingsActivity<ShadowTLSBean>() {
    override val usesComposePreferences = true

    override fun createEntity() = ShadowTLSBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val version = pbm.add(PreferenceBinding(Type.TextToInt, "version"))
    private val proxyProtocol = pbm.add(PreferenceBinding(Type.TextToInt, "proxyProtocol"))
    private val sni = pbm.add(PreferenceBinding(Type.Text, "sni"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))

    override fun ShadowTLSBean.init() {
        pbm.writeToCacheAll(this)

    }

    override fun ShadowTLSBean.serialize() {
        pbm.fromCacheAll(this)
    }

    @Composable
    override fun ComposePreferences() = ShadowTLSProfileSettingsScreen()

}
