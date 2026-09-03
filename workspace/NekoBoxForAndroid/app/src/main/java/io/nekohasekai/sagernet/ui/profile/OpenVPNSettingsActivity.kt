package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.compose.OpenVPNProfileSettingsScreen
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class OpenVPNSettingsActivity : ProfileSettingsActivity<OpenVPNBean>() {
    override val usesComposePreferences = true
    override fun createEntity() = OpenVPNBean().applyDefaultValues()

    private val binding = PreferenceBindingManager().apply {
        listOf("name", "mode", "serverAddress", "network", "additionalRemotes", "addresses", "peerAddress",
            "peerAddressIPv6", "topology", "username", "password", "authRetry", "staticChallenge", "staticKey",
            "staticKeyDirection", "udpTimeout", "tlsServerName", "tlsServerNameType", "caCertificates",
            "clientCertificate", "clientKey", "peerFingerprints", "remoteCertificateKU", "remoteCertificateEKU",
            "remoteCertificateTLS", "certificateProfile", "nsCertificateType", "tlsVersionMin", "tlsVersionMax",
            "tlsCipher", "tlsGroups", "controlWrapType", "controlWrapKey", "controlWrapDirection", "cipher",
            "dataCiphers", "dataCiphersFallback", "auth", "mssFixMode", "replayWindowTime", "compression", "compressionLZO",
            "allowCompression", "pullFilters", "routes", "routeGateway", "redirectGatewayFlags", "pingInterval",
            "pingRestart", "renegotiateInterval", "tlsTimeout", "handshakeWindow").forEach {
            add(PreferenceBinding(Type.Text, it))
        }
        listOf("serverPort", "mtu", "mssFix", "fragment", "replayWindow", "routeMetric", "explicitExitNotify").forEach {
            add(PreferenceBinding(Type.TextToInt, it))
        }
        listOf("renegotiateBytes", "renegotiatePackets").forEach { add(PreferenceBinding(Type.TextToLong, it)) }
        listOf("remoteRandom", "staticChallengeEcho", "mssFixDisabled", "routeNoPull", "redirectGateway",
            "redirectPrivate", "blockIPv6", "pingRestartDisabled", "renegotiateDisabled", "usePushedDNS",
            "acceptPushedDefaultResolvers", "expandPushedSearchDomains").forEach {
            add(PreferenceBinding(Type.Bool, it))
        }
    }
    override fun OpenVPNBean.init() = binding.writeToCacheAll(this)
    override fun OpenVPNBean.serialize() = binding.fromCacheAll(this)
    @Composable
    override fun ComposePreferences() = OpenVPNProfileSettingsScreen()
}
