package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.fmt.openconnect.OpenConnectBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.compose.OpenConnectProfileSettingsScreen
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class OpenConnectSettingsActivity : ProfileSettingsActivity<OpenConnectBean>() {
    override val usesComposePreferences = true
    override fun createEntity() = OpenConnectBean().applyDefaultValues()
    private val binding = PreferenceBindingManager().apply {
        listOf("name", "server", "flavor", "username", "password", "authGroup", "cookie", "tokenMode", "tokenSecret",
            "tokenPIN", "tokenPassword", "tokenDeviceID", "reportedOS", "userAgent", "clientVersion", "localHostname",
            "mobilePlatformVersion", "mobileDeviceType", "mobileDeviceUniqueID", "fortinetHostCheck",
            "fortinetVirtualDesktopCheck", "compressionMode", "dpdInterval", "reconnectTimeout", "trojanInterval", "udpTimeout",
            "caCertificates", "clientCertificate", "clientKey", "clientKeyPassword", "mcaCertificate", "mcaKey",
            "mcaKeyPassword", "tlsServerName", "tlsPeerFingerprints", "formEntries", "tnccDeviceID", "tnccUserAgent",
            "tnccCertificates").forEach {
            add(PreferenceBinding(Type.Text, it))
        }
        listOf("tokenCounter", "dtlsLocalPort", "mtu", "baseMTU", "queueLength").forEach {
            add(PreferenceBinding(Type.TextToInt, it))
        }
        listOf("noUDP", "compressionDisabled", "ipv6Disabled", "httpKeepaliveDisabled", "xmlPostDisabled",
            "externalAuthDisabled", "passwordAuthenticationDisabled", "tcpKeepAliveEnabled", "pfs",
            "allowInsecureCrypto", "tlsInsecure", "tlsSystemTrustDisabled", "tnccMachineIdentification",
            "usePushedDNS", "acceptPushedDefaultResolvers", "expandPushedSearchDomains").forEach {
            add(PreferenceBinding(Type.Bool, it))
        }
    }
    override fun OpenConnectBean.init() = binding.writeToCacheAll(this)
    override fun OpenConnectBean.serialize() {
        binding.fromCacheAll(this)
        syncServerAddress()
    }
    @Composable
    override fun ComposePreferences() = OpenConnectProfileSettingsScreen()
}
