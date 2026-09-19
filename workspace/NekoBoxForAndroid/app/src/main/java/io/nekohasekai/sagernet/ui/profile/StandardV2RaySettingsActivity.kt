package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import moe.matsuri.nb4a.ui.MaterialSwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type
import moe.matsuri.nb4a.ui.SimpleMenuPreference

abstract class StandardV2RaySettingsActivity : ProfileSettingsActivity<StandardV2RayBean>() {

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

    private lateinit var securityCategory: PreferenceCategory
    private lateinit var tlsCamouflageCategory: PreferenceCategory
    private lateinit var wsCategory: PreferenceCategory
    private lateinit var xhttpCategory: PreferenceCategory
    private lateinit var echCategory: PreferenceCategory

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.standard_v2ray_preferences)
        pbm.setPreferenceFragment(this)
        securityCategory = findPreference(Key.SERVER_SECURITY_CATEGORY)!!
        tlsCamouflageCategory = findPreference(Key.SERVER_TLS_CAMOUFLAGE_CATEGORY)!!
        echCategory = findPreference(Key.SERVER_ECH_CATEORY)!!
        wsCategory = findPreference(Key.SERVER_WS_CATEGORY)!!
        xhttpCategory = findPreference("serverXhttpCategory")!!


        // vmess/vless/http/trojan
        val isHttp = tmpBean is HttpBean
        val isVmess = tmpBean is VMessBean && tmpBean?.isVLESS == false
        val isVless = tmpBean?.isVLESS == true

        serverPort.preference.apply {
            this as EditTextPreference
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }

        alterId.preference.apply {
            this as EditTextPreference
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }

        uuid.preference.summaryProvider = PasswordSummaryProvider

        type.preference.isVisible = !isHttp
        uuid.preference.isVisible = !isHttp
        packetEncoding.preference.isVisible = isVmess || isVless
        packetEncoding.preference.apply {
            this as SimpleMenuPreference
            if (isVless) {
                setEntries(R.array.vless_packet_encoding_entry)
                setEntryValues(R.array.vless_packet_encoding_value)
            } else {
                setEntries(R.array.packet_encoding_entry)
                setEntryValues(R.array.int_array_3)
            }
        }
        alterId.preference.isVisible = isVmess
        encryption.preference.isVisible = isVmess || isVless
        vlessEncryption.preference.apply {
            isVisible = isVless
            this as EditTextPreference
            setOnBindEditTextListener { editText ->
                editText.hint = getString(R.string.vless_encryption_hint)
            }
        }
        username.preference.isVisible = isHttp
        password.preference.isVisible = isHttp

        if (tmpBean is TrojanBean) {
            uuid.preference.title = resources.getString(R.string.password)
        }

        encryption.preference.apply {
            this as SimpleMenuPreference
            if (isVless) {
                title = resources.getString(R.string.xtls_flow)
                setIcon(R.drawable.ic_baseline_stream_24)
                setEntries(R.array.xtls_flow_value)
                setEntryValues(R.array.xtls_flow_value)
            } else {
                setEntries(R.array.vmess_encryption_entry)
                setEntryValues(R.array.vmess_encryption_value)
            }
        }

        // menu with listener

        type.preference.apply {
            updateView(type.readStringFromCache())
            this as SimpleMenuPreference
            setOnPreferenceChangeListener { _, newValue ->
                updateView(newValue as String)
                true
            }
        }

        security.preference.apply {
            updateTls(security.readStringFromCache())
            this as SimpleMenuPreference
            setOnPreferenceChangeListener { _, newValue ->
                updateTls(newValue as String)
                true
            }
        }

        // Mux mode visibility control
        muxMode.preference.apply {
            updateMuxMode(muxMode.readIntFromCache())
            this as SimpleMenuPreference
            setOnPreferenceChangeListener { _, newValue ->
                updateMuxMode((newValue as String).toInt())
                true
            }
        }

        muxBrutal.preference.apply {
            updateMuxBrutal(muxBrutal.readBoolFromCache())
            this as MaterialSwitchPreference
            setOnPreferenceChangeListener { _, newValue ->
                updateMuxBrutal(newValue as Boolean)
                true
            }
        }
    }

    private fun updateMuxBrutal(enabled: Boolean) {
        muxBrutalUpMbps.preference.isVisible = enabled
        muxBrutalDownMbps.preference.isVisible = enabled
    }

    private fun updateMuxMode(mode: Int) {
        // mode 0: max_streams mode - show muxConcurrency, hide muxMaxConnections/muxMinStreams
        // mode 1: connections mode - hide muxConcurrency, show muxMaxConnections/muxMinStreams
        val isMaxStreamsMode = mode == 0
        muxConcurrency.preference.isVisible = isMaxStreamsMode
        muxMaxConnections.preference.isVisible = !isMaxStreamsMode
        muxMinStreams.preference.isVisible = !isMaxStreamsMode
    }

    private fun updateView(network: String) {
        host.preference.isVisible = false
        path.preference.isVisible = false
        mKcpSeed.preference.isVisible = false
        headerType.preference.isVisible = false
        kcpMtu.preference.isVisible = false
        kcpTti.preference.isVisible = false
        kcpCwndMultiplier.preference.isVisible = false
        kcpMaxSendingWindow.preference.isVisible = false
        wsCategory.isVisible = false
        xhttpCategory.isVisible = false

        when (network) {
            "tcp" -> {
                host.preference.setTitle(R.string.http_host)
                path.preference.setTitle(R.string.http_path)
            }

            "kcp" -> {
                mKcpSeed.preference.isVisible = true
                headerType.preference.isVisible = true
                kcpMtu.preference.isVisible = true
                kcpTti.preference.isVisible = true
                kcpCwndMultiplier.preference.isVisible = true
                kcpMaxSendingWindow.preference.isVisible = true
            }

            "http" -> {
                host.preference.setTitle(R.string.http_host)
                path.preference.setTitle(R.string.http_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
            }

            "ws" -> {
                host.preference.setTitle(R.string.ws_host)
                path.preference.setTitle(R.string.ws_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
                wsCategory.isVisible = true
            }

            "grpc" -> {
                path.preference.setTitle(R.string.grpc_service_name)
                path.preference.isVisible = true
            }

            "httpupgrade" -> {
                host.preference.setTitle(R.string.http_upgrade_host)
                path.preference.setTitle(R.string.http_upgrade_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
            }

            "xhttp" -> {
                host.preference.setTitle(R.string.xhttp_host)
                path.preference.setTitle(R.string.xhttp_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
                xhttpCategory.isVisible = true
            }
        }
    }

    private fun updateTls(tls: String) {
        val isTLS = tls == "tls" || tls == "reality"
        securityCategory.isVisible = isTLS
        tlsCamouflageCategory.isVisible = isTLS
        echCategory.isVisible = isTLS
    }

}
