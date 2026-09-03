package io.nekohasekai.sagernet.fmt.openconnect

import com.google.gson.reflect.TypeToken
import io.nekohasekai.sagernet.ktx.isIpAddressV6
import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.JavaUtil

private fun parseFormEntries(value: String): List<SingBoxOptions.OpenConnectFormEntryOptions>? {
    if (value.isBlank()) return null
    val type = object : TypeToken<List<SingBoxOptions.OpenConnectFormEntryOptions>>() {}.type
    return runCatching {
        JavaUtil.gson.fromJson<List<SingBoxOptions.OpenConnectFormEntryOptions>>(value, type)
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

private fun normalizeServer(server: String): String {
    if (server.contains("://")) return server
    return "https://${if (server.isIpAddressV6()) server.wrapIPV6Host() else server}"
}

fun buildSingBoxEndpointOpenConnectBean(bean: OpenConnectBean) =
    SingBoxOptions.OpenConnectEndpointOptions().apply {
        type = "openconnect"
        server = normalizeServer(bean.server)
        flavor = bean.flavor.takeIf(String::isNotBlank)
        username = bean.username.takeIf(String::isNotBlank)
        password = bean.password.takeIf(String::isNotBlank)
        auth_group = bean.authGroup.takeIf(String::isNotBlank)
        cookie = bean.cookie.takeIf(String::isNotBlank)
        reported_os = bean.reportedOS.takeIf(String::isNotBlank)
        user_agent = bean.userAgent.takeIf(String::isNotBlank)
        version = bean.clientVersion.takeIf(String::isNotBlank)
        local_hostname = bean.localHostname.takeIf(String::isNotBlank)
        if (listOf(bean.mobilePlatformVersion, bean.mobileDeviceType, bean.mobileDeviceUniqueID).any(String::isNotBlank)) {
            mobile = SingBoxOptions.OpenConnectMobileOptions().apply {
                platform_version = bean.mobilePlatformVersion.takeIf(String::isNotBlank)
                device_type = bean.mobileDeviceType.takeIf(String::isNotBlank)
                device_unique_id = bean.mobileDeviceUniqueID.takeIf(String::isNotBlank)
            }
        }
        if (bean.fortinetHostCheck.isNotBlank()) {
            fortinet_host_check = SingBoxOptions.OpenConnectFortinetHostCheckOptions().apply {
                hostcheck = bean.fortinetHostCheck
                check_virtual_desktop = bean.fortinetVirtualDesktopCheck.takeIf(String::isNotBlank)
            }
        }
        no_udp = bean.noUDP.takeIf { it }
        dtls_local_port = bean.dtlsLocalPort.takeIf { it > 0 }
        compression_disabled = bean.compressionDisabled.takeIf { it }
        compression_mode = bean.compressionMode.takeIf { it.isNotBlank() && !bean.compressionDisabled }
        ipv6_disabled = bean.ipv6Disabled.takeIf { it }
        http_keepalive_disabled = bean.httpKeepaliveDisabled.takeIf { it }
        xml_post_disabled = bean.xmlPostDisabled.takeIf { it }
        external_auth_disabled = bean.externalAuthDisabled.takeIf { it }
        password_authentication_disabled = bean.passwordAuthenticationDisabled.takeIf { it }
        tcp_keep_alive_enabled = bean.tcpKeepAliveEnabled.takeIf { it && bean.disableTcpKeepAlive != true }
        pfs = bean.pfs.takeIf { it }
        mtu = bean.mtu.takeIf { it > 0 }
        base_mtu = bean.baseMTU.takeIf { it > 0 }
        dpd_interval = bean.dpdInterval.takeIf(String::isNotBlank)
        reconnect_timeout = bean.reconnectTimeout.takeIf(String::isNotBlank)
        trojan_interval = bean.trojanInterval.takeIf(String::isNotBlank)
        queue_length = bean.queueLength.takeIf { it > 0 }
        allow_insecure_crypto = bean.allowInsecureCrypto.takeIf { it }
        udp_timeout = bean.udpTimeout.takeIf(String::isNotBlank)
        if (bean.tokenMode.isNotBlank() || bean.tokenSecret.isNotBlank()) {
            token = SingBoxOptions.OpenConnectTokenOptions().apply {
                mode = bean.tokenMode.takeIf(String::isNotBlank)
                secret = bean.tokenSecret.takeIf(String::isNotBlank)
                pin = bean.tokenPIN.takeIf(String::isNotBlank)
                password = bean.tokenPassword.takeIf(String::isNotBlank)
                device_id = bean.tokenDeviceID.takeIf(String::isNotBlank)
                counter = bean.tokenCounter.takeIf { it > 0 }?.toLong()
            }
        }
        tls = SingBoxOptions.OpenConnectTLSOptions().apply {
            insecure = bean.tlsInsecure.takeIf { it }
            server_name = bean.tlsServerName.takeIf(String::isNotBlank)
            peer_fingerprint = bean.tlsPeerFingerprints.lineSequence().map(String::trim).filter(String::isNotBlank)
                .toList().takeIf(List<*>::isNotEmpty)
            system_trust_disabled = bean.tlsSystemTrustDisabled.takeIf { it }
            certificate_authority = bean.caCertificates.takeIf(String::isNotBlank)?.let(::listOf)
            client_certificate = bean.clientCertificate.takeIf(String::isNotBlank)?.let(::listOf)
            client_key = bean.clientKey.takeIf(String::isNotBlank)?.let(::listOf)
            client_key_password = bean.clientKeyPassword.takeIf(String::isNotBlank)
            mca_certificate = bean.mcaCertificate.takeIf(String::isNotBlank)?.let(::listOf)
            mca_key = bean.mcaKey.takeIf(String::isNotBlank)?.let(::listOf)
            mca_key_password = bean.mcaKeyPassword.takeIf(String::isNotBlank)
        }
        form_entries = parseFormEntries(bean.formEntries)
        if (
            bean.tnccDeviceID.isNotBlank() ||
            bean.tnccUserAgent.isNotBlank() ||
            bean.tnccMachineIdentification ||
            bean.tnccCertificates.isNotBlank()
        ) {
            tncc = SingBoxOptions.OpenConnectTNCCOptions().apply {
                device_id = bean.tnccDeviceID.takeIf(String::isNotBlank)
                user_agent = bean.tnccUserAgent.takeIf(String::isNotBlank)
                machine_identification_enabled = bean.tnccMachineIdentification.takeIf { it }
                certificates = bean.tnccCertificates.takeIf(String::isNotBlank)?.let {
                    listOf(
                        SingBoxOptions.OpenConnectTNCCCertificateOptions().apply {
                            certificate = listOf(it)
                        },
                    )
                }
            }
        }
    }
