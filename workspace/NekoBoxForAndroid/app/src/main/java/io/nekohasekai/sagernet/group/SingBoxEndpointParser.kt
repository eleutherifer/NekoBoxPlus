package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.openconnect.OpenConnectBean
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.filterIsInstance
import io.nekohasekai.sagernet.ktx.getStr
import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import org.json.JSONArray
import org.json.JSONObject

/**
 * Imports endpoint types introduced by sing-box 1.14.
 */
internal object SingBoxEndpointParser {
    fun parseOpenVPN(json: JSONObject): OpenVPNBean? {
        if (json.getStr("type") !in setOf("openvpn", "openvpn-client")) return null
        return OpenVPNBean().applyDefaultValues().apply {
            name = json.getStr("tag") ?: ""
            mode = json.getStr("mode") ?: "tls"
            serverAddress = json.getStr("server") ?: return null
            serverPort = json.optInt("server_port", 1194)
            network = json.getStr("network") ?: "udp"
            username = json.getStr("username") ?: ""
            password = json.getStr("password") ?: ""
            authRetry = json.getStr("auth_retry") ?: ""
            staticChallenge = json.getStr("static_challenge") ?: ""
            staticChallengeEcho = json.optBoolean("static_challenge_echo")
            addresses = json.lines("address")
            peerAddress = json.getStr("peer_address") ?: ""
            peerAddressIPv6 = json.getStr("peer_address_ipv6") ?: ""
            topology = json.getStr("topology") ?: ""
            staticKey = json.lines("static_key")
            staticKeyDirection = json.getStr("key_direction") ?: ""
            mtu = json.optInt("mtu")
            udpTimeout = json.getStr("udp_timeout") ?: ""
            additionalRemotes =
                json.optJSONArray("servers")
                    ?.filterIsInstance<JSONObject>()
                    ?.mapNotNull { remote ->
                        val host = remote.getStr("server") ?: return@mapNotNull null
                        val port = remote.optInt("server_port", 1194)
                        val remoteNetwork = remote.getStr("network") ?: network
                        "${if (remoteNetwork.startsWith("tcp")) "tcp" else "udp"}://${host.wrapIPV6Host()}:$port"
                    }?.joinToString("\n")
                    ?: ""
            remoteRandom = json.optBoolean("remote_random")
            dataCiphers = json.lines("data_ciphers")
            dataCiphersFallback = json.getStr("data_ciphers_fallback") ?: ""
            cipher = json.getStr("cipher") ?: ""
            auth = json.getStr("auth") ?: ""
            mssFix = json.optInt("mss_fix")
            mssFixDisabled = json.optBoolean("mss_fix_disabled")
            mssFixMode = json.getStr("mss_fix_mode") ?: ""
            fragment = json.optInt("fragment")
            replayWindow = json.optInt("replay_window")
            replayWindowTime = json.getStr("replay_window_time") ?: ""
            compression = json.getStr("compression") ?: ""
            compressionLZO = json.getStr("compression_lzo") ?: ""
            allowCompression = json.getStr("allow_compression") ?: ""
            routeNoPull = json.optBoolean("route_no_pull")
            pullFilters =
                json.optJSONArray("pull_filters")
                    ?.filterIsInstance<JSONObject>()
                    ?.mapNotNull { filter ->
                        val action = filter.getStr("action") ?: return@mapNotNull null
                        val value = filter.getStr("text") ?: return@mapNotNull null
                        "$action $value"
                    }?.joinToString("\n")
                    ?: ""
            routes = json.lines("routes")
            routeGateway = json.getStr("route_gateway") ?: ""
            routeMetric = json.optInt("route_metric")
            redirectGateway = json.optBoolean("redirect_gateway")
            redirectGatewayFlags = json.lines("redirect_gateway_flags")
            redirectPrivate = json.optBoolean("redirect_private")
            blockIPv6 = json.optBoolean("block_ipv6")
            pingInterval = json.getStr("ping_interval") ?: ""
            pingRestart = json.getStr("ping_restart") ?: ""
            pingRestartDisabled = json.optBoolean("ping_restart_disabled")
            renegotiateInterval = json.getStr("renegotiate_interval") ?: ""
            renegotiateDisabled = json.optBoolean("renegotiate_disabled")
            renegotiateBytes = json.optLong("renegotiate_bytes")
            renegotiatePackets = json.optLong("renegotiate_packets")
            tlsTimeout = json.getStr("tls_timeout") ?: ""
            handshakeWindow = json.getStr("handshake_window") ?: ""
            explicitExitNotify = json.optInt("explicit_exit_notify")
            json.optJSONObject("tls")?.let { tls ->
                tlsServerName = tls.getStr("server_name") ?: ""
                tlsServerNameType = tls.getStr("server_name_type") ?: ""
                caCertificates = tls.lines("certificate")
                clientCertificate = tls.lines("client_certificate")
                clientKey = tls.lines("client_key")
                peerFingerprints = tls.lines("peer_fingerprint")
                remoteCertificateKU = tls.lines("remote_certificate_ku")
                remoteCertificateEKU = tls.getStr("remote_certificate_eku") ?: ""
                remoteCertificateTLS = tls.getStr("remote_certificate_tls") ?: ""
                certificateProfile = tls.getStr("certificate_profile") ?: ""
                nsCertificateType = tls.getStr("ns_certificate_type") ?: ""
                tlsVersionMin = tls.getStr("version_min") ?: ""
                tlsVersionMax = tls.getStr("version_max") ?: ""
                tlsCipher = tls.getStr("cipher") ?: ""
                tlsGroups = tls.getStr("groups") ?: ""
                tls.optJSONObject("control_wrap")?.let { wrap ->
                    controlWrapType = wrap.getStr("type") ?: ""
                    controlWrapKey = wrap.lines("key")
                    controlWrapDirection = wrap.getStr("direction") ?: ""
                }
            }
        }
    }

    fun parseOpenConnect(json: JSONObject): OpenConnectBean? {
        if (json.getStr("type") != "openconnect") return null
        return OpenConnectBean().applyDefaultValues().apply {
            name = json.getStr("tag") ?: ""
            server = json.getStr("server") ?: return null
            flavor = json.getStr("flavor") ?: "anyconnect"
            username = json.getStr("username") ?: ""
            password = json.getStr("password") ?: ""
            authGroup = json.getStr("auth_group") ?: ""
            cookie = json.getStr("cookie") ?: ""
            reportedOS = json.getStr("reported_os") ?: ""
            userAgent = json.getStr("user_agent") ?: ""
            clientVersion = json.getStr("version") ?: ""
            localHostname = json.getStr("local_hostname") ?: ""
            json.optJSONObject("mobile")?.let { mobile ->
                mobilePlatformVersion = mobile.getStr("platform_version") ?: ""
                mobileDeviceType = mobile.getStr("device_type") ?: ""
                mobileDeviceUniqueID = mobile.getStr("device_unique_id") ?: ""
            }
            json.optJSONObject("fortinet_host_check")?.let { hostCheck ->
                fortinetHostCheck = hostCheck.getStr("hostcheck") ?: ""
                fortinetVirtualDesktopCheck = hostCheck.getStr("check_virtual_desktop") ?: ""
            }
            noUDP = json.optBoolean("no_udp")
            dtlsLocalPort = json.optInt("dtls_local_port")
            compressionDisabled = json.optBoolean("compression_disabled")
            compressionMode = json.getStr("compression_mode") ?: ""
            ipv6Disabled = json.optBoolean("ipv6_disabled")
            httpKeepaliveDisabled = json.optBoolean("http_keepalive_disabled")
            xmlPostDisabled = json.optBoolean("xml_post_disabled")
            externalAuthDisabled = json.optBoolean("external_auth_disabled")
            passwordAuthenticationDisabled = json.optBoolean("password_authentication_disabled")
            tcpKeepAliveEnabled = json.optBoolean("tcp_keep_alive_enabled")
            pfs = json.optBoolean("pfs")
            mtu = json.optInt("mtu")
            baseMTU = json.optInt("base_mtu")
            dpdInterval = json.getStr("dpd_interval") ?: ""
            reconnectTimeout = json.getStr("reconnect_timeout") ?: ""
            trojanInterval = json.getStr("trojan_interval") ?: ""
            queueLength = json.optInt("queue_length")
            allowInsecureCrypto = json.optBoolean("allow_insecure_crypto")
            udpTimeout = json.getStr("udp_timeout") ?: ""
            formEntries = json.optJSONArray("form_entries")?.toString() ?: ""
            json.optJSONObject("token")?.let { token ->
                tokenMode = token.getStr("mode") ?: ""
                tokenSecret = token.getStr("secret") ?: ""
                tokenPIN = token.getStr("pin") ?: ""
                tokenPassword = token.getStr("password") ?: ""
                tokenDeviceID = token.getStr("device_id") ?: ""
                tokenCounter = token.optInt("counter")
            }
            json.optJSONObject("tls")?.let { tls ->
                caCertificates = tls.lines("certificate_authority")
                tlsInsecure = tls.optBoolean("insecure")
                tlsServerName = tls.getStr("server_name") ?: ""
                tlsPeerFingerprints = tls.lines("peer_fingerprint")
                tlsSystemTrustDisabled = tls.optBoolean("system_trust_disabled")
                clientCertificate = tls.lines("client_certificate")
                clientKey = tls.lines("client_key")
                clientKeyPassword = tls.getStr("client_key_password") ?: ""
                mcaCertificate = tls.lines("mca_certificate")
                mcaKey = tls.lines("mca_key")
                mcaKeyPassword = tls.getStr("mca_key_password") ?: ""
            }
            json.optJSONObject("tncc")?.let { tncc ->
                tnccDeviceID = tncc.getStr("device_id") ?: ""
                tnccUserAgent = tncc.getStr("user_agent") ?: ""
                tnccMachineIdentification = tncc.optBoolean("machine_identification_enabled")
                tnccCertificates =
                    tncc.optJSONArray("certificates")
                        ?.filterIsInstance<JSONObject>()
                        ?.mapNotNull {
                            it.optJSONArray("certificate")
                                ?.filterIsInstance<String>()
                                ?.joinToString("\n")
                        }?.joinToString("\n")
                        ?: ""
            }
            syncServerAddress()
        }
    }

    private fun JSONObject.lines(key: String): String =
        when (val value = opt(key)) {
            is JSONArray -> value.filterIsInstance<String>().joinToString("\n")
            is String -> value
            else -> ""
        }
}
