package io.nekohasekai.sagernet.fmt.masque

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.blankAsNull
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.listByLineOrComma

internal fun masqueTransport(useHTTP2: Boolean?): String = if (useHTTP2 == true) "h2" else "h3"

fun MasqueBean.hasConfig(): Boolean {
    return !configPrivateKey.isNullOrBlank() ||
            !configEndpointV4.isNullOrBlank() ||
            !configEndpointV6.isNullOrBlank() ||
            !configEndpointH2V4.isNullOrBlank() ||
            !configEndpointH2V6.isNullOrBlank() ||
            !configEndpointPubKey.isNullOrBlank() ||
            !configLicense.isNullOrBlank() ||
            !configId.isNullOrBlank() ||
            !configAccessToken.isNullOrBlank() ||
            !configIPv4.isNullOrBlank() ||
            !configIPv6.isNullOrBlank()
}

fun MasqueBean.applyConfigJson(configJson: String): Boolean {
    if (configJson.isBlank()) return false
    val config = gson.fromJson(configJson, SingBoxOptions.MASQUEConfig::class.java) ?: return false
    val old = listOf(
        configPrivateKey,
        configEndpointV4,
        configEndpointV6,
        configEndpointH2V4,
        configEndpointH2V6,
        configEndpointPubKey,
        configLicense,
        configId,
        configAccessToken,
        configIPv4,
        configIPv6,
    )
    configPrivateKey = config.private_key.orEmpty()
    configEndpointV4 = config.endpoint_v4.orEmpty()
    configEndpointV6 = config.endpoint_v6.orEmpty()
    configEndpointH2V4 = config.endpoint_h2_v4.orEmpty()
    configEndpointH2V6 = config.endpoint_h2_v6.orEmpty()
    configEndpointPubKey = config.endpoint_pub_key.orEmpty()
    configLicense = config.license.orEmpty()
    configId = config.id.orEmpty()
    configAccessToken = config.access_token.orEmpty()
    configIPv4 = config.ipv4.orEmpty()
    configIPv6 = config.ipv6.orEmpty()
    return old != listOf(
        configPrivateKey,
        configEndpointV4,
        configEndpointV6,
        configEndpointH2V4,
        configEndpointH2V6,
        configEndpointPubKey,
        configLicense,
        configId,
        configAccessToken,
        configIPv4,
        configIPv6,
    )
}

private fun MasqueBean.buildConfig(): SingBoxOptions.MASQUEConfig? {
    if (!hasConfig()) return null
    return SingBoxOptions.MASQUEConfig().apply {
        private_key = configPrivateKey.blankAsNull()
        endpoint_v4 = configEndpointV4.blankAsNull()
        endpoint_v6 = configEndpointV6.blankAsNull()
        endpoint_h2_v4 = configEndpointH2V4.blankAsNull()
        endpoint_h2_v6 = configEndpointH2V6.blankAsNull()
        endpoint_pub_key = configEndpointPubKey.blankAsNull()
        license = configLicense.blankAsNull()
        id = configId.blankAsNull()
        access_token = configAccessToken.blankAsNull()
        ipv4 = configIPv4.blankAsNull()
        ipv6 = configIPv6.blankAsNull()
    }
}

fun buildSingBoxOutboundMasqueBean(
    bean: MasqueBean,
    detourTag: String?,
): SingBoxOptions.Outbound_MASQUEOptions {
    return SingBoxOptions.Outbound_MASQUEOptions().apply {
        type = "masque"
        allowed_ips = bean.allowedIPs.blankAsNull()?.listByLineOrComma()
        transport = masqueTransport(bean.useHTTP2)
        use_ipv6 = bean.useIPv6
        udp_timeout = bean.udpTimeout.blankAsNull()
        udp_keepalive_period = bean.udpKeepalivePeriod.blankAsNull()
        if (bean.udpInitialPacketSize > 0) {
            udp_initial_packet_size = bean.udpInitialPacketSize
        }
        reconnect_delay = bean.reconnectDelay.blankAsNull()
        profile = SingBoxOptions.CloudflareProfile().apply {
            id = bean.profileId.blankAsNull()
            auth_token = bean.profileAuthToken.blankAsNull()
            private_key = bean.profilePrivateKey.blankAsNull()
            recreate = bean.profileRecreate
            detour = detourTag
        }
        config = if (bean.profileRecreate == true) null else bean.buildConfig()
        tls = SingBoxOptions.MASQUEOutboundTLSOptions().apply {
            sni = bean.tlsSNI.blankAsNull()
            insecure = bean.tlsInsecure || DataStore.globalAllowInsecure
            cipher_suites = bean.tlsCipherSuites.blankAsNull()?.listByLineOrComma()
            curve_preferences = bean.tlsCurvePreferences.blankAsNull()?.listByLineOrComma()
            fragment = bean.tlsFragment
            fragment_fallback_delay = bean.tlsFragmentFallbackDelay.blankAsNull()
            record_fragment = bean.tlsRecordFragment
            kernel_tx = bean.tlsKernelTx
            kernel_rx = bean.tlsKernelRx
        }
    }
}
