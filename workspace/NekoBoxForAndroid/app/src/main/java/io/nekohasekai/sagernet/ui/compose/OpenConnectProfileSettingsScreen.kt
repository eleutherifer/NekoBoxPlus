package io.nekohasekai.sagernet.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.nekohasekai.sagernet.R

@Composable
internal fun OpenConnectProfileSettingsScreen() {
    val preferences = remember {
        fun c(title: Int) = CachePreferenceCategory(title)
        fun t(icon: Int, title: Int, key: String, summary: Int? = null, show: Boolean = true,
              secret: Boolean = false, number: Boolean = false, max: Int = Int.MAX_VALUE) =
            CacheTextPreference(icon, title, key, summary, show, secret, number, max)
        fun s(icon: Int, title: Int, key: String, dependency: String? = null) =
            CacheSwitchPreference(icon, title, key, dependency)
        listOf(
            t(R.drawable.ic_social_emoji_symbols, R.string.profile_name, "name"),
            c(R.string.openconnect_connection),
            t(R.drawable.ic_baseline_http_24, R.string.openconnect_server_url, "server"),
            t(R.drawable.ic_action_settings, R.string.openconnect_flavor, "flavor", R.string.openconnect_flavor_summary),
            t(R.drawable.ic_baseline_timer_24, R.string.udp_timeout, "udpTimeout"),
            t(R.drawable.ic_baseline_grid_3x3_24, R.string.openconnect_dtls_local_port, "dtlsLocalPort", number = true, max = 5),
            t(R.drawable.ic_baseline_tune_24, R.string.mtu, "mtu", number = true),
            t(R.drawable.ic_baseline_tune_24, R.string.openconnect_base_mtu, "baseMTU", number = true),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openconnect_no_udp, "noUDP"),
            s(R.drawable.ic_baseline_warning_24, R.string.openconnect_allow_insecure_crypto, "allowInsecureCrypto"),
            c(R.string.openconnect_authentication),
            t(R.drawable.ic_baseline_person_24, R.string.username, "username"),
            t(R.drawable.ic_settings_password, R.string.password, "password", secret = true),
            t(R.drawable.ic_baseline_person_24, R.string.openconnect_auth_group, "authGroup"),
            t(R.drawable.ic_settings_password, R.string.openconnect_cookie, "cookie", secret = true),
            t(R.drawable.ic_action_settings, R.string.openconnect_reported_os, "reportedOS"),
            t(R.drawable.ic_baseline_person_24, R.string.user_agent, "userAgent"),
            t(R.drawable.ic_action_settings, R.string.openconnect_client_version, "clientVersion"),
            t(R.drawable.ic_baseline_domain_24, R.string.openconnect_local_hostname, "localHostname"),
            t(R.drawable.ic_action_description, R.string.openconnect_form_entries, "formEntries", R.string.openconnect_form_entries_summary),
            c(R.string.openconnect_token),
            t(R.drawable.ic_action_settings, R.string.openconnect_token_mode, "tokenMode"),
            t(R.drawable.ic_settings_password, R.string.openconnect_token_secret, "tokenSecret", secret = true),
            t(R.drawable.ic_settings_password, R.string.pin, "tokenPIN", secret = true),
            t(R.drawable.ic_settings_password, R.string.openconnect_token_password, "tokenPassword", secret = true),
            t(R.drawable.ic_device_developer_mode, R.string.openconnect_token_device_id, "tokenDeviceID"),
            t(R.drawable.ic_baseline_grid_3x3_24, R.string.openconnect_token_counter, "tokenCounter", number = true),
            c(R.string.tls_settings),
            s(R.drawable.ic_baseline_warning_24, R.string.openconnect_tls_insecure, "tlsInsecure"),
            t(R.drawable.ic_baseline_domain_24, R.string.server_name, "tlsServerName"),
            t(R.drawable.ic_baseline_fingerprint_24, R.string.openconnect_tls_peer_fingerprints, "tlsPeerFingerprints", show = false),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openconnect_disable_system_trust, "tlsSystemTrustDisabled"),
            t(R.drawable.ic_baseline_security_24, R.string.openvpn_ca_certificates, "caCertificates", show = false),
            t(R.drawable.ic_baseline_security_24, R.string.client_certificate, "clientCertificate", show = false),
            t(R.drawable.ic_settings_password, R.string.client_key, "clientKey", secret = true),
            t(R.drawable.ic_settings_password, R.string.openconnect_client_key_password, "clientKeyPassword", secret = true),
            t(R.drawable.ic_baseline_security_24, R.string.openconnect_mca_certificate, "mcaCertificate", show = false),
            t(R.drawable.ic_settings_password, R.string.openconnect_mca_key, "mcaKey", secret = true),
            t(R.drawable.ic_settings_password, R.string.openconnect_mca_key_password, "mcaKeyPassword", secret = true),
            c(R.string.openconnect_mobile_identity),
            t(R.drawable.ic_device_developer_mode, R.string.openconnect_mobile_platform_version, "mobilePlatformVersion"),
            t(R.drawable.ic_device_developer_mode, R.string.openconnect_mobile_device_type, "mobileDeviceType"),
            t(R.drawable.ic_baseline_fingerprint_24, R.string.openconnect_mobile_device_id, "mobileDeviceUniqueID"),
            c(R.string.openconnect_transport_controls),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openconnect_disable_compression, "compressionDisabled"),
            t(R.drawable.ic_action_settings, R.string.openconnect_compression_mode, "compressionMode"),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openconnect_disable_ipv6, "ipv6Disabled"),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openconnect_disable_http_keepalive, "httpKeepaliveDisabled"),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openconnect_disable_xml_post, "xmlPostDisabled"),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openconnect_disable_external_auth, "externalAuthDisabled"),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openconnect_disable_password_auth, "passwordAuthenticationDisabled"),
            s(R.drawable.ic_baseline_refresh_24, R.string.openconnect_tcp_keep_alive, "tcpKeepAliveEnabled"),
            s(R.drawable.ic_baseline_security_24, R.string.openconnect_pfs, "pfs"),
            t(R.drawable.ic_baseline_timer_24, R.string.openconnect_dpd_interval, "dpdInterval"),
            t(R.drawable.ic_baseline_timer_24, R.string.openconnect_reconnect_timeout, "reconnectTimeout"),
            t(R.drawable.ic_baseline_timer_24, R.string.openconnect_trojan_interval, "trojanInterval"),
            t(R.drawable.ic_baseline_grid_3x3_24, R.string.openconnect_queue_length, "queueLength", number = true),
            c(R.string.openconnect_fortinet_host_check),
            t(R.drawable.ic_baseline_security_24, R.string.openconnect_fortinet_hostcheck, "fortinetHostCheck"),
            t(R.drawable.ic_device_developer_mode, R.string.openconnect_fortinet_virtual_desktop, "fortinetVirtualDesktopCheck"),
            c(R.string.tncc),
            t(R.drawable.ic_device_developer_mode, R.string.openconnect_tncc_device_id, "tnccDeviceID"),
            t(R.drawable.ic_baseline_person_24, R.string.openconnect_tncc_user_agent, "tnccUserAgent"),
            s(R.drawable.ic_device_developer_mode, R.string.openconnect_tncc_machine_id, "tnccMachineIdentification"),
            t(R.drawable.ic_baseline_security_24, R.string.openconnect_tncc_certificates, "tnccCertificates", show = false),
            c(R.string.pushed_dns),
            s(R.drawable.ic_baseline_dns_24, R.string.use_pushed_dns, "usePushedDNS"),
            s(R.drawable.ic_baseline_dns_24, R.string.accept_pushed_default_resolvers, "acceptPushedDefaultResolvers", "usePushedDNS"),
            s(R.drawable.ic_baseline_domain_24, R.string.expand_pushed_search_domains, "expandPushedSearchDomains", "usePushedDNS"),
        )
    }
    CacheProfileSettingsScreen(preferences)
}
