package io.nekohasekai.sagernet.fmt.openvpn

import io.nekohasekai.sagernet.ktx.unwrapIPV6Host
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import java.net.URI

private fun String.nonBlankLines() = lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

private fun parseRemote(line: String): SingBoxOptions.OpenVPNRemoteOptions? = runCatching {
    val normalized = if (line.contains("://")) line else "udp://$line"
    val uri = URI(normalized)
    val host = uri.host ?: return null
    SingBoxOptions.OpenVPNRemoteOptions().apply {
        server = host
        server_port = uri.port.takeIf { it > 0 } ?: 1194
        network = uri.scheme.lowercase().takeIf { it == "tcp" || it == "udp" }
    }
}.getOrNull()

private fun parsePullFilter(line: String): SingBoxOptions.OpenVPNPullFilterOptions? {
    val separator = line.indexOfAny(charArrayOf(' ', ':'))
    if (separator <= 0) return null
    return SingBoxOptions.OpenVPNPullFilterOptions().apply {
        action = line.substring(0, separator).trim()
        text = line.substring(separator + 1).trim()
    }.takeIf { it.text.isNotEmpty() }
}

fun buildSingBoxEndpointOpenVPNBean(bean: OpenVPNBean) =
    SingBoxOptions.OpenVPNClientEndpointOptions().apply {
        type = "openvpn-client"
        val staticMode = bean.mode == "static_key"
        mode = bean.mode.takeIf { it.isNotBlank() && it != "tls" }
        network = bean.network.takeIf(String::isNotBlank)
        val additionalRemotes = bean.additionalRemotes.nonBlankLines().mapNotNull(::parseRemote)
        if (additionalRemotes.isEmpty()) {
            server = bean.serverAddress.unwrapIPV6Host()
            server_port = bean.serverPort
        } else {
            servers = buildList {
                add(
                    SingBoxOptions.OpenVPNRemoteOptions().apply {
                        server = bean.serverAddress.unwrapIPV6Host()
                        server_port = bean.serverPort
                        network = bean.network.takeIf(String::isNotBlank)
                    },
                )
                addAll(additionalRemotes)
            }
        }
        remote_random = bean.remoteRandom.takeIf { it }
        username = bean.username.takeIf { it.isNotBlank() && !staticMode }
        password = bean.password.takeIf { it.isNotBlank() && !staticMode }
        auth_retry = bean.authRetry.takeIf { it.isNotBlank() && !staticMode }
        static_challenge = bean.staticChallenge.takeIf { it.isNotBlank() && !staticMode }
        static_challenge_echo = bean.staticChallengeEcho.takeIf { it && !staticMode }
        address = bean.addresses.listByLineOrComma().takeIf(List<*>::isNotEmpty)
        peer_address = bean.peerAddress.takeIf(String::isNotBlank)
        peer_address_ipv6 = bean.peerAddressIPv6.takeIf(String::isNotBlank)
        topology = bean.topology.takeIf(String::isNotBlank)
        static_key = bean.staticKey.takeIf { it.isNotBlank() && staticMode }?.let(::listOf)
        key_direction = bean.staticKeyDirection.takeIf { it.isNotBlank() && staticMode }
        mtu = bean.mtu.takeIf { it > 0 }
        udp_timeout = bean.udpTimeout.takeIf(String::isNotBlank)
        cipher = bean.cipher.takeIf { it.isNotBlank() && staticMode }
        data_ciphers = bean.dataCiphers.listByLineOrComma().takeIf { it.isNotEmpty() && !staticMode }
        data_ciphers_fallback = bean.dataCiphersFallback.takeIf { it.isNotBlank() && !staticMode }
        auth = bean.auth.takeIf(String::isNotBlank)
        mss_fix = bean.mssFix.takeIf { it > 0 && !bean.mssFixDisabled }
        mss_fix_disabled = bean.mssFixDisabled.takeIf { it }
        mss_fix_mode = bean.mssFixMode.takeIf { it.isNotBlank() && !bean.mssFixDisabled }
        fragment = bean.fragment.takeIf { it > 0 }
        replay_window = bean.replayWindow.takeIf { it > 0 }
        replay_window_time = bean.replayWindowTime.takeIf(String::isNotBlank)
        compression = bean.compression.takeIf(String::isNotBlank)
        compression_lzo = bean.compressionLZO.takeIf(String::isNotBlank)
        allow_compression = bean.allowCompression.takeIf(String::isNotBlank)
        route_no_pull = bean.routeNoPull.takeIf { it && !staticMode }
        pull_filters = bean.pullFilters.nonBlankLines().mapNotNull(::parsePullFilter).takeIf { it.isNotEmpty() && !staticMode }
        routes = bean.routes.listByLineOrComma().takeIf(List<*>::isNotEmpty)
        route_gateway = bean.routeGateway.takeIf(String::isNotBlank)
        route_metric = bean.routeMetric.takeIf { it != 0 }
        redirect_gateway = bean.redirectGateway.takeIf { it }
        redirect_gateway_flags = bean.redirectGatewayFlags.listByLineOrComma().takeIf(List<*>::isNotEmpty)
        redirect_private = bean.redirectPrivate.takeIf { it }
        block_ipv6 = bean.blockIPv6.takeIf { it }
        ping_interval = bean.pingInterval.takeIf(String::isNotBlank)
        ping_restart = bean.pingRestart.takeIf { it.isNotBlank() && !bean.pingRestartDisabled }
        ping_restart_disabled = bean.pingRestartDisabled.takeIf { it }
        renegotiate_interval = bean.renegotiateInterval.takeIf { it.isNotBlank() && !bean.renegotiateDisabled && !staticMode }
        renegotiate_disabled = bean.renegotiateDisabled.takeIf { it && !staticMode }
        renegotiate_bytes = bean.renegotiateBytes.takeIf { it > 0 && !staticMode }
        renegotiate_packets = bean.renegotiatePackets.takeIf { it > 0 && !staticMode }
        tls_timeout = bean.tlsTimeout.takeIf { it.isNotBlank() && !staticMode }
        handshake_window = bean.handshakeWindow.takeIf { it.isNotBlank() && !staticMode }
        explicit_exit_notify = bean.explicitExitNotify.takeIf { it > 0 }
        if (!staticMode) tls = SingBoxOptions.OpenVPNOutboundTLSOptions().apply {
            server_name = bean.tlsServerName.takeIf(String::isNotBlank)
            server_name_type = bean.tlsServerNameType.takeIf(String::isNotBlank)
            certificate = bean.caCertificates.takeIf(String::isNotBlank)?.let(::listOf)
            client_certificate = bean.clientCertificate.takeIf(String::isNotBlank)?.let(::listOf)
            client_key = bean.clientKey.takeIf(String::isNotBlank)?.let(::listOf)
            peer_fingerprint = bean.peerFingerprints.listByLineOrComma().takeIf(List<*>::isNotEmpty)
            remote_certificate_ku = bean.remoteCertificateKU.listByLineOrComma().takeIf(List<*>::isNotEmpty)
            remote_certificate_eku = bean.remoteCertificateEKU.takeIf(String::isNotBlank)
            remote_certificate_tls =
                bean.remoteCertificateTLS.takeIf { it.isNotBlank() && bean.remoteCertificateEKU.isBlank() }
            certificate_profile = bean.certificateProfile.takeIf(String::isNotBlank)
            ns_certificate_type = bean.nsCertificateType.takeIf(String::isNotBlank)
            version_min = bean.tlsVersionMin.takeIf(String::isNotBlank)
            version_max = bean.tlsVersionMax.takeIf(String::isNotBlank)
            cipher = bean.tlsCipher.takeIf(String::isNotBlank)
            groups = bean.tlsGroups.takeIf(String::isNotBlank)
            if (bean.controlWrapType.isNotBlank() || bean.controlWrapKey.isNotBlank()) {
                val controlWrapType = bean.controlWrapType.replace('-', '_')
                control_wrap = SingBoxOptions.OpenVPNControlWrapOptions().apply {
                    type = controlWrapType.takeIf(String::isNotBlank)
                    key = bean.controlWrapKey.takeIf(String::isNotBlank)?.let(::listOf)
                    direction = bean.controlWrapDirection.takeIf { it.isNotBlank() && controlWrapType == "tls_auth" }
                }
            }
        }
    }
