/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.mieru

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseMieru(link: String): List<MieruBean> {
    val url = link.replaceFirst("mierus://", "https://", ignoreCase = true).toHttpUrlOrNull()
        ?: error("Invalid mieru URL")
    if (url.username.isBlank() || url.password.isBlank()) {
        error("empty username or password")
    }
    val portCount = url.querySize("port")
    if (portCount != url.querySize("protocol")) {
        error("port count and protocol count mismatch")
    }
    if (portCount == 0) {
        error("missing port and protocol")
    }

    val tcpPorts = mutableListOf<String>()
    val udpPorts = mutableListOf<String>()
    repeat(portCount) { index ->
        val port = url.queryParameterValues("port")[index] ?: error("empty port")
        when (val protocol = url.queryParameterValues("protocol")[index]) {
            "TCP" -> tcpPorts.add(port)
            "UDP" -> udpPorts.add(port)
            else -> error("unknown protocol: $protocol")
        }
    }

    val multiplexing = url.queryParameter("multiplexing")?.toMieruMultiplexingLevel()
    val handshakeMode = url.queryParameter("handshake-mode")?.toMieruHandshakeMode()
    val trafficPattern = url.queryParameter("traffic-pattern")
    val lowEntropyMode = url.queryParameter("low-entropy-mode")
    val lowEntropyMaskRotation = url.queryParameter("low-entropy-mask-rotation")
    val profileName = url.queryParameter("profile") ?: url.fragment

    fun buildBean(protocol: Int, ports: List<String>) = MieruBean().apply {
        serverAddress = url.host.ifEmpty { error("empty host") }
        if (ports.size == 1 && ports[0].toIntOrNull() != null) {
            serverPort = ports[0].toInt()
        } else {
            serverPort = 0
            portRange = ports.joinToString("\n")
        }
        username = url.username
        password = url.password
        name = profileName
        this.protocol = protocol
        multiplexingLevel = multiplexing
        this.handshakeMode = handshakeMode
        this.trafficPattern = trafficPattern
        this.lowEntropyMode = lowEntropyMode
        this.lowEntropyMaskRotation = lowEntropyMaskRotation
        initializeDefaultValues()
    }

    return buildList {
        if (tcpPorts.isNotEmpty()) add(buildBean(MieruBean.PROTOCOL_TCP, tcpPorts))
        if (udpPorts.isNotEmpty()) add(buildBean(MieruBean.PROTOCOL_UDP, udpPorts))
    }
}

fun MieruBean.toUri(): String {
    val builder = linkBuilder()
        .host(serverAddress.ifEmpty { error("empty server address") })
    if (username.isBlank()) error("empty username")
    if (password.isBlank()) error("empty password")
    builder.username(username)
    builder.password(password)

    if (name.isNotBlank()) {
        builder.addQueryParameter("profile", name)
    }
    val transport = mieruTransport()
    val ports = if (portRange.isNotBlank()) portRange.listByLineOrComma() else listOf(serverPort.toString())
    for (port in ports) {
        builder.addQueryParameter("port", port)
        builder.addQueryParameter("protocol", transport)
    }
    mieruMultiplexingName()?.let { builder.addQueryParameter("multiplexing", it) }
    mieruHandshakeName()?.let { builder.addQueryParameter("handshake-mode", it) }
    if (trafficPattern.isNotBlank()) {
        builder.addQueryParameter("traffic-pattern", trafficPattern)
    }
    if (lowEntropyMode.isNotBlank()) {
        builder.addQueryParameter("low-entropy-mode", lowEntropyMode)
    }
    if (lowEntropyMaskRotation.isNotBlank()) {
        builder.addQueryParameter("low-entropy-mask-rotation", lowEntropyMaskRotation)
    }
    return builder.toLink("mierus", false)
}

fun buildSingBoxOutboundMieruBean(bean: MieruBean): SingBoxOptions.Outbound_MieruOptions {
    return SingBoxOptions.Outbound_MieruOptions().apply {
        type = "mieru"
        server = bean.serverAddress
        if (bean.portRange.isNotBlank()) {
            server_ports = bean.portRange.listByLineOrComma()
        } else {
            server_port = bean.serverPort
        }
        transport = bean.mieruTransport()
        username = bean.username
        password = bean.password
        bean.mieruMultiplexingName()?.let { multiplexing = it }
        bean.mieruHandshakeName()?.let { handshake_mode = it }
        traffic_pattern = bean.trafficPattern.takeIf { it.isNotBlank() }
        low_entropy_mode = bean.lowEntropyMode.takeIf { it.isNotBlank() }
        low_entropy_mask_rotation = bean.lowEntropyMaskRotation.takeIf { it.isNotBlank() }
    }
}

private fun HttpUrl.querySize(name: String): Int = queryParameterValues(name).size

private fun String.toMieruMultiplexingLevel(): Int = when (this) {
    "MULTIPLEXING_OFF" -> MieruBean.MULTIPLEXING_OFF
    "MULTIPLEXING_LOW" -> MieruBean.MULTIPLEXING_LOW
    "MULTIPLEXING_MIDDLE" -> MieruBean.MULTIPLEXING_MIDDLE
    "MULTIPLEXING_HIGH" -> MieruBean.MULTIPLEXING_HIGH
    else -> MieruBean.MULTIPLEXING_DEFAULT
}

private fun String.toMieruHandshakeMode(): Int = when (this) {
    "HANDSHAKE_STANDARD" -> MieruBean.HANDSHAKE_STANDARD
    "HANDSHAKE_NO_WAIT" -> MieruBean.HANDSHAKE_NO_WAIT
    else -> MieruBean.HANDSHAKE_DEFAULT
}

private fun MieruBean.mieruTransport(): String = when (protocol) {
    MieruBean.PROTOCOL_UDP -> "UDP"
    else -> "TCP"
}

private fun MieruBean.mieruMultiplexingName(): String? = when (multiplexingLevel) {
    MieruBean.MULTIPLEXING_OFF -> "MULTIPLEXING_OFF"
    MieruBean.MULTIPLEXING_LOW -> "MULTIPLEXING_LOW"
    MieruBean.MULTIPLEXING_MIDDLE -> "MULTIPLEXING_MIDDLE"
    MieruBean.MULTIPLEXING_HIGH -> "MULTIPLEXING_HIGH"
    else -> null
}

private fun MieruBean.mieruHandshakeName(): String? = when (handshakeMode) {
    MieruBean.HANDSHAKE_STANDARD -> "HANDSHAKE_STANDARD"
    MieruBean.HANDSHAKE_NO_WAIT -> "HANDSHAKE_NO_WAIT"
    else -> null
}
