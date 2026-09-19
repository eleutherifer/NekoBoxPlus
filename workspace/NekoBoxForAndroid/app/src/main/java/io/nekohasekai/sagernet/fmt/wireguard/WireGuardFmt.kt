package io.nekohasekai.sagernet.fmt.wireguard

import io.nekohasekai.sagernet.ktx.isIpAddressV6
import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma

private fun normalizeWireGuardAddress(value: String): String {
    if (value.isEmpty() || value.contains("/")) return value
    return if (value.isIpAddressV6()) "$value/128" else "$value/32"
}

internal fun normalizeWireGuardAddressList(value: String): List<String> {
    return value
        .listByLineOrComma()
        .map(::normalizeWireGuardAddress)
}

internal fun parseReservedValues(value: String): List<Int>? {
    val reservedValues = value
        .removePrefix("[")
        .removeSuffix("]")
        .listByLineOrComma()
        .mapNotNull { it.trim().toIntOrNull() }
    return reservedValues.takeIf { it.size == 3 }
}

fun genReserved(anyStr: String): String {
    try {
        val list = anyStr.listByLineOrComma()
        val ba = ByteArray(3)
        if (list.size == 3) {
            list.forEachIndexed { index, s ->
                val i = s
                    .replace("[", "")
                    .replace("]", "")
                    .replace(" ", "")
                    .toIntOrNull() ?: return anyStr
                ba[index] = i.toByte()
            }
            return Util.b64EncodeOneLine(ba)
        } else {
            return anyStr
        }
    } catch (e: Exception) {
        return anyStr
    }
}

fun WireGuardBean.buildWireGuardConfig(): String = buildString {
    append("[Interface]\n")
    normalizeWireGuardAddressList(localAddress).forEach {
        append("Address = ").append(it).append('\n')
    }
    append("PrivateKey = ").append(privateKey).append('\n')
    if (mtu > 0) append("MTU = ").append(mtu).append('\n')
    append('\n')
    append("[Peer]\n")
    append("PublicKey = ").append(peerPublicKey).append('\n')
    if (peerPreSharedKey.isNotBlank()) {
        append("PresharedKey = ").append(peerPreSharedKey).append('\n')
    }
    append("Endpoint = ").append(serverAddress.wrapIPV6Host()).append(':').append(serverPort).append('\n')
    append("AllowedIPs = 0.0.0.0/0, ::/0\n")
    if (peerPersistentKeepalive > 0) {
        append("PersistentKeepalive = ").append(peerPersistentKeepalive).append('\n')
    }
    if (reserved.isNotBlank()) {
        append("Reserved = ").append(reserved).append('\n')
    }
}

fun buildSingBoxEndpointWireguardBean(bean: WireGuardBean): SingBoxOptions.WireGuardEndpointOptions {
    return SingBoxOptions.WireGuardEndpointOptions().apply {
        address = normalizeWireGuardAddressList(bean.localAddress)
        private_key = bean.privateKey
        mtu = bean.mtu
        peers = listOf(SingBoxOptions.WireGuardEndpointPeer().apply {
            address = bean.serverAddress.wrapIPV6Host()
            port = bean.serverPort
            public_key = bean.peerPublicKey
            if (bean.peerPreSharedKey.isNotBlank()) pre_shared_key = bean.peerPreSharedKey
            if ((bean.peerPersistentKeepalive ?: 0) > 0) {
                persistent_keepalive_interval = bean.peerPersistentKeepalive
            }
            allowed_ips = listOf("0.0.0.0/0", "::/0")
            if (bean.reserved.isNotBlank()) {
                parseReservedValues(bean.reserved.trim())?.let { reserved = it }
            }
        })
    }
}
