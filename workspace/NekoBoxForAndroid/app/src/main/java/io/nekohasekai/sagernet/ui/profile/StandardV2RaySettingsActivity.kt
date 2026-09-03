package io.nekohasekai.sagernet.ui.profile

import androidx.compose.runtime.Composable
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ui.compose.StandardV2RayProfileSettingsScreen
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

abstract class StandardV2RaySettingsActivity : ProfileSettingsActivity<StandardV2RayBean>() {
    override val usesComposePreferences = true

    var tmpBean: StandardV2RayBean? = null

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val uuid = pbm.add(PreferenceBinding(Type.Text, "uuid"))
    private val username = pbm.add(PreferenceBinding(Type.Text, "username"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val alterId = pbm.add(PreferenceBinding(Type.TextToInt, "alterId"))
    private val encryption = pbm.add(PreferenceBinding(Type.Text, "encryption"))
    private val type = pbm.add(PreferenceBinding(Type.Text, "type"))
    private val host = pbm.add(PreferenceBinding(Type.Text, "host"))
    private val path = pbm.add(PreferenceBinding(Type.Text, "path"))
    private val packetEncoding = pbm.add(PreferenceBinding(Type.TextToInt, "packetEncoding"))
    private val wsMaxEarlyData = pbm.add(PreferenceBinding(Type.TextToInt, "wsMaxEarlyData"))
    private val earlyDataHeaderName = pbm.add(PreferenceBinding(Type.Text, "earlyDataHeaderName"))
    private val security = pbm.add(PreferenceBinding(Type.Text, "security"))
    private val sni = pbm.add(PreferenceBinding(Type.Text, "sni"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))
    private val realityPubKey = pbm.add(PreferenceBinding(Type.Text, "realityPubKey"))
    private val realityShortId = pbm.add(PreferenceBinding(Type.Text, "realityShortId"))

    private val enableECH = pbm.add(PreferenceBinding(Type.Bool, "enableECH"))
    private val echConfig = pbm.add(PreferenceBinding(Type.Text, "echConfig"))

    private val enableMux = pbm.add(PreferenceBinding(Type.Bool, "enableMux"))
    private val muxPadding = pbm.add(PreferenceBinding(Type.Bool, "muxPadding"))
    private val muxType = pbm.add(PreferenceBinding(Type.TextToInt, "muxType"))
    private val muxConcurrency = pbm.add(PreferenceBinding(Type.TextToInt, "muxConcurrency"))
    private val muxMode = pbm.add(PreferenceBinding(Type.TextToInt, "muxMode"))
    private val muxMaxConnections = pbm.add(PreferenceBinding(Type.TextToInt, "muxMaxConnections"))
    private val muxMinStreams = pbm.add(PreferenceBinding(Type.TextToInt, "muxMinStreams"))
    private val muxBrutal = pbm.add(PreferenceBinding(Type.Bool, "muxBrutal"))
    private val muxBrutalUpMbps = pbm.add(PreferenceBinding(Type.TextToInt, "muxBrutalUpMbps"))
    private val muxBrutalDownMbps = pbm.add(PreferenceBinding(Type.TextToInt, "muxBrutalDownMbps"))

    private val xhttpMode = pbm.add(PreferenceBinding(Type.Text, "xhttpMode"))
    private val xhttpHeaders = pbm.add(PreferenceBinding(Type.Text, "xhttpHeaders"))
    private val xhttpUplinkDataPlacement = pbm.add(PreferenceBinding(Type.Text, "xhttpUplinkDataPlacement"))
    private val xhttpSessionPlacement = pbm.add(PreferenceBinding(Type.Text, "xhttpSessionPlacement"))
    private val xhttpSessionPlacementOld = pbm.add(PreferenceBinding(Type.Text, "xhttpSessionPlacementOld"))
    private val xhttpPaddingMethod = pbm.add(PreferenceBinding(Type.Text, "xhttpPaddingMethod"))
    private val xhttpPaddingObfsMode = pbm.add(PreferenceBinding(Type.Bool, "xhttpPaddingObfsMode"))
    private val xhttpExtra = pbm.add(PreferenceBinding(Type.Text, "xhttpExtra"))
    private val xhttpNoGrpcHeader = pbm.add(PreferenceBinding(Type.Bool, "xhttpNoGrpcHeader"))
    private val xhttpNoSseHeader = pbm.add(PreferenceBinding(Type.Bool, "xhttpNoSseHeader"))
    private val xhttpXmuxMaxConcurrency = pbm.add(PreferenceBinding(Type.Text, "xhttpXmuxMaxConcurrency"))
    private val xhttpXmuxMaxConnections = pbm.add(PreferenceBinding(Type.Text, "xhttpXmuxMaxConnections"))
    private val xhttpXmuxCMaxReuseTimes = pbm.add(PreferenceBinding(Type.Text, "xhttpXmuxCMaxReuseTimes"))
    private val xhttpXmuxHMaxRequestTimes = pbm.add(PreferenceBinding(Type.Text, "xhttpXmuxHMaxRequestTimes"))
    private val xhttpXmuxHMaxReusableSecs = pbm.add(PreferenceBinding(Type.Text, "xhttpXmuxHMaxReusableSecs"))
    private val xhttpXmuxHKeepAlivePeriod = pbm.add(PreferenceBinding(Type.Text, "xhttpXmuxHKeepAlivePeriod"))
    private val xhttpXPaddingKey = pbm.add(PreferenceBinding(Type.Text, "xhttpXPaddingKey"))
    private val xhttpXPaddingHeader = pbm.add(PreferenceBinding(Type.Text, "xhttpXPaddingHeader"))
    private val xhttpXPaddingPlacement = pbm.add(PreferenceBinding(Type.Text, "xhttpXPaddingPlacement"))
    private val xhttpUplinkHttpMethod = pbm.add(PreferenceBinding(Type.Text, "xhttpUplinkHttpMethod"))
    private val xhttpUplinkDataKey = pbm.add(PreferenceBinding(Type.Text, "xhttpUplinkDataKey"))
    private val xhttpSessionKey = pbm.add(PreferenceBinding(Type.Text, "xhttpSessionKey"))
    private val xhttpSessionKeyOld = pbm.add(PreferenceBinding(Type.Text, "xhttpSessionKeyOld"))
    private val xhttpSessionIdTable = pbm.add(PreferenceBinding(Type.Text, "xhttpSessionIdTable"))
    private val xhttpSessionIdLength = pbm.add(PreferenceBinding(Type.Text, "xhttpSessionIdLength"))
    private val xhttpSeqPlacement = pbm.add(PreferenceBinding(Type.Text, "xhttpSeqPlacement"))
    private val xhttpSeqKey = pbm.add(PreferenceBinding(Type.Text, "xhttpSeqKey"))
    private val xhttpXPaddingBytes = pbm.add(PreferenceBinding(Type.Text, "xhttpXPaddingBytes"))
    private val xhttpScMaxEachPostBytes = pbm.add(PreferenceBinding(Type.Text, "xhttpScMaxEachPostBytes"))
    private val xhttpScMinPostsIntervalMs = pbm.add(PreferenceBinding(Type.Text, "xhttpScMinPostsIntervalMs"))
    private val xhttpScMaxBufferedPosts = pbm.add(PreferenceBinding(Type.Text, "xhttpScMaxBufferedPosts"))
    private val xhttpScStreamUpServerSecs = pbm.add(PreferenceBinding(Type.Text, "xhttpScStreamUpServerSecs"))
    private val xhttpUplinkChunkSize = pbm.add(PreferenceBinding(Type.Text, "xhttpUplinkChunkSize"))
    private val xhttpServerMaxHeaderBytes = pbm.add(PreferenceBinding(Type.Text, "xhttpServerMaxHeaderBytes"))
    private val xhttpCongestionController = pbm.add(PreferenceBinding(Type.Text, "xhttpCongestionController"))
    private val xhttpCwnd = pbm.add(PreferenceBinding(Type.Text, "xhttpCwnd"))
    private val vlessEncryption = pbm.add(PreferenceBinding(Type.Text, "vlessEncryption"))

    // KCP
    private val mKcpSeed = pbm.add(PreferenceBinding(Type.Text, "mKcpSeed"))
    private val headerType = pbm.add(PreferenceBinding(Type.Text, "headerType"))
    private val kcpMtu = pbm.add(PreferenceBinding(Type.TextToInt, "kcpMtu"))
    private val kcpTti = pbm.add(PreferenceBinding(Type.TextToInt, "kcpTti"))
    private val kcpCwndMultiplier = pbm.add(PreferenceBinding(Type.TextToInt, "kcpCwndMultiplier"))
    private val kcpMaxSendingWindow = pbm.add(PreferenceBinding(Type.TextToInt, "kcpMaxSendingWindow"))

    override fun StandardV2RayBean.init() {
        this@StandardV2RaySettingsActivity.uuid.fieldName = "uuid"
        this@StandardV2RaySettingsActivity.username.disable = this !is HttpBean
        this@StandardV2RaySettingsActivity.password.disable = this !is HttpBean
        this@StandardV2RaySettingsActivity.alterId.disable = this !is VMessBean

        if (this is TrojanBean) {
            this@StandardV2RaySettingsActivity.uuid.fieldName = "password"
            this@StandardV2RaySettingsActivity.password.disable = true
        }

        tmpBean = this // copy bean
        pbm.writeToCacheAll(this)
    }

    override fun StandardV2RayBean.serialize() {
        pbm.fromCacheAll(this)
    }

    @Composable
    override fun ComposePreferences() = StandardV2RayProfileSettingsScreen(
        isHttp = tmpBean is HttpBean,
        isVmess = tmpBean is VMessBean && tmpBean?.isVLESS == false,
        isVless = tmpBean?.isVLESS == true,
        isTrojan = tmpBean is TrojanBean,
    )

}
