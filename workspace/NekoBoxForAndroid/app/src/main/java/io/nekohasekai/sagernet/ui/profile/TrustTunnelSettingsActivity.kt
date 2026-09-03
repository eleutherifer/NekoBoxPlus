package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.compose.TrustTunnelProfileSettingsScreen
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class TrustTunnelSettingsActivity : ProfileSettingsActivity<TrustTunnelBean>() {

    override val usesComposePreferences = true

    companion object {
        private const val KEY_PROTOCOL = "trustTunnelProtocol"
        private const val KEY_CRONET_STACK = "trustTunnelCronetStack"
        private const val PROTOCOL_HTTPS = "https"
        private const val PROTOCOL_PREFER_QUIC = "prefer_quic"
        private const val PROTOCOL_FORCE_QUIC = "force_quic"
        private const val CRONET_NO = "no"
        private const val CRONET_FOR_HTTPS = "https"
        private const val CRONET_FOR_QUIC = "quic"
        private const val CRONET_FOR_HTTPS_AND_QUIC = "https_quic"
    }

    override fun createEntity() = TrustTunnelBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val username = pbm.add(PreferenceBinding(Type.Text, "username"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val healthCheck = pbm.add(PreferenceBinding(Type.Bool, "healthCheck"))
    private val quicBinding = pbm.add(PreferenceBinding(Type.Bool, "quic"))
    private val forceQuicBinding = pbm.add(PreferenceBinding(Type.Bool, "forceQuic"))
    private val useCronetQuicBinding = pbm.add(PreferenceBinding(Type.Bool, "useCronetQuic"))
    private val useCronetHttpsBinding = pbm.add(PreferenceBinding(Type.Bool, "useCronetHttps"))
    private val quicCongestionControl = pbm.add(PreferenceBinding(Type.Text, "quicCongestionControl"))
    private val clientRandomPrefix = pbm.add(PreferenceBinding(Type.Text, "clientRandomPrefix"))
    private val serverName = pbm.add(PreferenceBinding(Type.Text, "serverName"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val certPublicKeySha256 = pbm.add(PreferenceBinding(Type.Text, "certPublicKeySha256"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val tlsFragment = pbm.add(PreferenceBinding(Type.Bool, "tlsFragment"))
    private val tlsFragmentFallbackDelay = pbm.add(PreferenceBinding(Type.Text, "tlsFragmentFallbackDelay"))
    private val tlsRecordFragment = pbm.add(PreferenceBinding(Type.Bool, "tlsRecordFragment"))
    private val ech = pbm.add(PreferenceBinding(Type.Bool, "ech"))
    private val echConfig = pbm.add(PreferenceBinding(Type.Text, "echConfig"))
    private val echQueryServerName = pbm.add(PreferenceBinding(Type.Text, "echQueryServerName"))
    private val clientCert = pbm.add(PreferenceBinding(Type.Text, "clientCert"))
    private val clientKey = pbm.add(PreferenceBinding(Type.Text, "clientKey"))

    override fun TrustTunnelBean.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(
            KEY_PROTOCOL,
            when {
                !quic -> PROTOCOL_HTTPS
                forceQuic -> PROTOCOL_FORCE_QUIC
                else -> PROTOCOL_PREFER_QUIC
            },
        )
        DataStore.profileCacheStore.putString(
            KEY_CRONET_STACK,
            when {
                useCronetHttps && useCronetQuic -> CRONET_FOR_HTTPS_AND_QUIC
                useCronetHttps -> CRONET_FOR_HTTPS
                useCronetQuic -> CRONET_FOR_QUIC
                else -> CRONET_NO
            },
        )
    }

    @Composable
    override fun ComposePreferences() = TrustTunnelProfileSettingsScreen()

    override fun TrustTunnelBean.serialize() {
        val protocol = DataStore.profileCacheStore.getString(KEY_PROTOCOL) ?: PROTOCOL_HTTPS
        val cronetStack = DataStore.profileCacheStore.getString(KEY_CRONET_STACK) ?: CRONET_NO
        DataStore.profileCacheStore.putBoolean("quic", protocol != PROTOCOL_HTTPS)
        DataStore.profileCacheStore.putBoolean("forceQuic", protocol == PROTOCOL_FORCE_QUIC)
        DataStore.profileCacheStore.putBoolean(
            "useCronetQuic",
            cronetStack == CRONET_FOR_QUIC || cronetStack == CRONET_FOR_HTTPS_AND_QUIC,
        )
        DataStore.profileCacheStore.putBoolean(
            "useCronetHttps",
            cronetStack == CRONET_FOR_HTTPS || cronetStack == CRONET_FOR_HTTPS_AND_QUIC,
        )
        if (DataStore.profileCacheStore.getString("utlsFingerprint") == "cronet") {
            DataStore.profileCacheStore.putBoolean("useCronetHttps", true)
            DataStore.profileCacheStore.putString("utlsFingerprint", "")
        }
        pbm.fromCacheAll(this)
    }
}
