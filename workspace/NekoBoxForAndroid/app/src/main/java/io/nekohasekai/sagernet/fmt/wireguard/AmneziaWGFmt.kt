package io.nekohasekai.sagernet.fmt.wireguard

import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import moe.matsuri.nb4a.SingBoxOptions

fun AmneziaWGBean.buildAmneziaWGConfig(): String = buildString {
    append("[Interface]\n")
    normalizeWireGuardAddressList(localAddress).forEach {
        append("Address = ").append(it).append('\n')
    }
    append("PrivateKey = ").append(privateKey).append('\n')
    if (mtu > 0) append("MTU = ").append(mtu).append('\n')
    if (jc != 0) append("Jc = ").append(jc).append('\n')
    if (jmin != 0) append("Jmin = ").append(jmin).append('\n')
    if (jmax != 0) append("Jmax = ").append(jmax).append('\n')
    if (s1 != 0) append("S1 = ").append(s1).append('\n')
    if (s2 != 0) append("S2 = ").append(s2).append('\n')
    if (h1.isNotBlank()) append("H1 = ").append(h1).append('\n')
    if (h2.isNotBlank()) append("H2 = ").append(h2).append('\n')
    if (h3.isNotBlank()) append("H3 = ").append(h3).append('\n')
    if (h4.isNotBlank()) append("H4 = ").append(h4).append('\n')
    if (i1.isNotBlank()) append("I1 = ").append(i1).append('\n')
    if (i2.isNotBlank()) append("I2 = ").append(i2).append('\n')
    if (i3.isNotBlank()) append("I3 = ").append(i3).append('\n')
    if (i4.isNotBlank()) append("I4 = ").append(i4).append('\n')
    if (i5.isNotBlank()) append("I5 = ").append(i5).append('\n')
    if (s3 != 0) append("S3 = ").append(s3).append('\n')
    if (s4 != 0) append("S4 = ").append(s4).append('\n')
    if (headerProtectionKey.isNotBlank()) {
        append("HeaderProtectionKey = ").append(headerProtectionKey).append('\n')
    }
    if (contentPaddingAddition.isNotBlank()) {
        append("ContentPaddingAddition = ").append(contentPaddingAddition).append('\n')
    }
    if (rekeyAfterTime.isNotBlank()) append("RekeyAfterTime = ").append(rekeyAfterTime).append('\n')
    if (rekeyTimeout.isNotBlank()) append("RekeyTimeout = ").append(rekeyTimeout).append('\n')
    if (rejectAfterTime.isNotBlank()) append("RejectAfterTime = ").append(rejectAfterTime).append('\n')
    if (keepaliveTimeout.isNotBlank()) append("KeepaliveTimeout = ").append(keepaliveTimeout).append('\n')
    if (maxHandshakeAttempts.isNotBlank()) {
        append("MaxHandshakeAttempts = ").append(maxHandshakeAttempts).append('\n')
    }
    append('\n')
    append("[Peer]\n")
    append("PublicKey = ").append(peerPublicKey).append('\n')
    if (peerPreSharedKey.isNotBlank()) {
        append("PresharedKey = ").append(peerPreSharedKey).append('\n')
    }
    append("Endpoint = ").append(serverAddress.wrapIPV6Host()).append(':').append(serverPort).append('\n')
    append("AllowedIPs = 0.0.0.0/0, ::/0\n")
    if (peerPersistentKeepalive.isNotBlank() && peerPersistentKeepalive != "0") {
        append("PersistentKeepalive = ").append(peerPersistentKeepalive).append('\n')
    }
    if (reserved.isNotBlank()) {
        append("Reserved = ").append(reserved).append('\n')
    }
}

fun buildSingBoxEndpointAwgBean(bean: AmneziaWGBean): SingBoxOptions.AwgEndpointOptions {
    return SingBoxOptions.AwgEndpointOptions().apply {
        type = "awg"
        address = normalizeWireGuardAddressList(bean.localAddress)
        private_key = bean.privateKey
        mtu = bean.mtu

        val peer = SingBoxOptions.AwgPeer().apply {
            address = bean.serverAddress.wrapIPV6Host()
            port = bean.serverPort
            public_key = bean.peerPublicKey
            if (bean.peerPreSharedKey.isNotBlank()) preshared_key = bean.peerPreSharedKey
            if (bean.peerPersistentKeepalive.isNotBlank() && bean.peerPersistentKeepalive != "0") {
                persistent_keepalive_interval = bean.peerPersistentKeepalive
            }
            allowed_ips = listOf("0.0.0.0/0", "::/0")
            if (bean.reserved.isNotBlank()) {
                parseReservedValues(bean.reserved.trim())?.let { reserved = it }
            }
        }
        peers = listOf(peer)

        // AWG 1.0 obfuscation parameters
        if (bean.jc != 0) jc = bean.jc
        if (bean.jmin != 0) jmin = bean.jmin
        if (bean.jmax != 0) jmax = bean.jmax
        if (bean.s1 != 0) s1 = bean.s1
        if (bean.s2 != 0) s2 = bean.s2
        if (bean.h1.isNotBlank()) h1 = bean.h1
        if (bean.h2.isNotBlank()) h2 = bean.h2
        if (bean.h3.isNotBlank()) h3 = bean.h3
        if (bean.h4.isNotBlank()) h4 = bean.h4

        // AWG 1.5 signature chain parameters
        if (bean.i1.isNotBlank()) i1 = bean.i1
        if (bean.i2.isNotBlank()) i2 = bean.i2
        if (bean.i3.isNotBlank()) i3 = bean.i3
        if (bean.i4.isNotBlank()) i4 = bean.i4
        if (bean.i5.isNotBlank()) i5 = bean.i5

        // AWG 2.0 additional packet padding parameters
        if (bean.s3 != 0) s3 = bean.s3
        if (bean.s4 != 0) s4 = bean.s4

        // AWG 3.0 parameters
        if (bean.headerProtectionKey.isNotBlank()) {
            header_protection_key = bean.headerProtectionKey
        }
        if (bean.contentPaddingAddition.isNotBlank()) {
            content_padding_addition = bean.contentPaddingAddition
        }
        if (bean.rekeyAfterTime.isNotBlank()) rekey_after_time = bean.rekeyAfterTime
        if (bean.rekeyTimeout.isNotBlank()) rekey_timeout = bean.rekeyTimeout
        if (bean.rejectAfterTime.isNotBlank()) reject_after_time = bean.rejectAfterTime
        if (bean.keepaliveTimeout.isNotBlank()) keepalive_timeout = bean.keepaliveTimeout
        if (bean.maxHandshakeAttempts.isNotBlank()) {
            max_handshake_attempts = bean.maxHandshakeAttempts
        }
    }
}

fun AmneziaWGBean.hasAmneziaWG3Options(): Boolean =
    !headerProtectionKey.isNullOrBlank() ||
        !contentPaddingAddition.isNullOrBlank() ||
        !rekeyAfterTime.isNullOrBlank() ||
        !rekeyTimeout.isNullOrBlank() ||
        !rejectAfterTime.isNullOrBlank() ||
        !keepaliveTimeout.isNullOrBlank() ||
        !maxHandshakeAttempts.isNullOrBlank() ||
        peerPersistentKeepalive?.let {
            it.contains('-') || (it.toULongOrNull()?.let { value -> value > 65_535u } == true)
        } == true
