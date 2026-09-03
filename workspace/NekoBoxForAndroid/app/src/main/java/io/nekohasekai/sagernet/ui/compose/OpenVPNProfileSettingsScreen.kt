package io.nekohasekai.sagernet.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.nekohasekai.sagernet.R

@Composable
internal fun OpenVPNProfileSettingsScreen() {
    val preferences = remember {
        fun c(title: Int) = CachePreferenceCategory(title)
        fun t(icon: Int, title: Int, key: String, summary: Int? = null, show: Boolean = true,
              secret: Boolean = false, number: Boolean = false, max: Int = Int.MAX_VALUE) =
            CacheTextPreference(icon, title, key, summary, show, secret, number, max)
        fun s(icon: Int, title: Int, key: String, dependency: String? = null) =
            CacheSwitchPreference(icon, title, key, dependency)
        listOf(
            t(R.drawable.ic_social_emoji_symbols, R.string.profile_name, "name"),
            c(R.string.openvpn_connection),
            t(R.drawable.ic_action_settings, R.string.openvpn_mode, "mode", R.string.openvpn_mode_summary),
            t(R.drawable.ic_baseline_domain_24, R.string.server_address, "serverAddress"),
            t(R.drawable.ic_baseline_grid_3x3_24, R.string.server_port, "serverPort", number = true, max = 5),
            t(R.drawable.ic_baseline_compare_arrows_24, R.string.network, "network"),
            t(R.drawable.baseline_public_24, R.string.openvpn_additional_remotes, "additionalRemotes", R.string.openvpn_additional_remotes_summary),
            s(R.drawable.ic_baseline_shuffle_24, R.string.openvpn_remote_random, "remoteRandom"),
            t(R.drawable.ic_baseline_tune_24, R.string.mtu, "mtu", number = true),
            t(R.drawable.ic_baseline_call_split_24, R.string.openvpn_addresses, "addresses", show = false),
            t(R.drawable.baseline_public_24, R.string.openvpn_peer_address, "peerAddress"),
            t(R.drawable.baseline_public_24, R.string.openvpn_peer_address_ipv6, "peerAddressIPv6"),
            t(R.drawable.ic_action_settings, R.string.openvpn_topology, "topology"),
            t(R.drawable.ic_baseline_timer_24, R.string.udp_timeout, "udpTimeout"),
            c(R.string.openvpn_authentication),
            t(R.drawable.ic_baseline_person_24, R.string.username, "username"),
            t(R.drawable.ic_settings_password, R.string.password, "password", secret = true),
            t(R.drawable.ic_baseline_refresh_24, R.string.openvpn_auth_retry, "authRetry"),
            t(R.drawable.ic_settings_password, R.string.openvpn_static_challenge, "staticChallenge"),
            s(R.drawable.ic_baseline_visibility_off_24, R.string.openvpn_static_challenge_echo, "staticChallengeEcho"),
            t(R.drawable.ic_settings_password, R.string.openvpn_static_key, "staticKey", secret = true),
            t(R.drawable.ic_baseline_compare_arrows_24, R.string.openvpn_static_key_direction, "staticKeyDirection"),
            c(R.string.tls_settings),
            t(R.drawable.ic_baseline_domain_24, R.string.server_name, "tlsServerName"),
            t(R.drawable.ic_action_settings, R.string.openvpn_server_name_type, "tlsServerNameType"),
            t(R.drawable.ic_baseline_security_24, R.string.openvpn_ca_certificates, "caCertificates", show = false),
            t(R.drawable.ic_baseline_security_24, R.string.client_certificate, "clientCertificate", show = false),
            t(R.drawable.ic_settings_password, R.string.client_key, "clientKey", secret = true),
            t(R.drawable.ic_baseline_fingerprint_24, R.string.openvpn_peer_fingerprints, "peerFingerprints", show = false),
            t(R.drawable.ic_baseline_security_24, R.string.openvpn_remote_certificate_ku, "remoteCertificateKU", show = false),
            t(R.drawable.ic_baseline_security_24, R.string.openvpn_remote_certificate_eku, "remoteCertificateEKU"),
            t(R.drawable.ic_baseline_security_24, R.string.openvpn_remote_certificate_tls, "remoteCertificateTLS"),
            t(R.drawable.ic_baseline_security_24, R.string.openvpn_certificate_profile, "certificateProfile"),
            t(R.drawable.ic_baseline_security_24, R.string.openvpn_ns_certificate_type, "nsCertificateType"),
            t(R.drawable.ic_action_settings, R.string.tls_version_min, "tlsVersionMin"),
            t(R.drawable.ic_action_settings, R.string.tls_version_max, "tlsVersionMax"),
            t(R.drawable.ic_settings_password, R.string.cipher, "tlsCipher"),
            t(R.drawable.ic_action_settings, R.string.openvpn_tls_groups, "tlsGroups"),
            t(R.drawable.ic_baseline_security_24, R.string.openvpn_control_wrap_type, "controlWrapType"),
            t(R.drawable.ic_settings_password, R.string.openvpn_control_wrap_key, "controlWrapKey", secret = true),
            t(R.drawable.ic_baseline_compare_arrows_24, R.string.openvpn_key_direction, "controlWrapDirection"),
            c(R.string.openvpn_data_channel),
            t(R.drawable.ic_settings_password, R.string.openvpn_static_cipher, "cipher"),
            t(R.drawable.ic_settings_password, R.string.openvpn_data_ciphers, "dataCiphers", show = false),
            t(R.drawable.ic_settings_password, R.string.openvpn_data_cipher_fallback, "dataCiphersFallback"),
            t(R.drawable.ic_baseline_security_24, R.string.openvpn_packet_auth, "auth"),
            t(R.drawable.ic_baseline_tune_24, R.string.openvpn_mss_fix, "mssFix", number = true),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openvpn_mss_fix_disabled, "mssFixDisabled"),
            t(R.drawable.ic_action_settings, R.string.openvpn_mss_fix_mode, "mssFixMode"),
            t(R.drawable.ic_baseline_tune_24, R.string.openvpn_fragment, "fragment", number = true),
            t(R.drawable.ic_baseline_security_24, R.string.openvpn_replay_window, "replayWindow", number = true),
            t(R.drawable.ic_baseline_timer_24, R.string.openvpn_replay_window_time, "replayWindowTime"),
            t(R.drawable.ic_action_settings, R.string.openvpn_compression, "compression"),
            t(R.drawable.ic_action_settings, R.string.openvpn_compression_lzo, "compressionLZO"),
            t(R.drawable.ic_action_settings, R.string.openvpn_allow_compression, "allowCompression"),
            c(R.string.openvpn_routes_timing),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openvpn_route_no_pull, "routeNoPull"),
            t(R.drawable.ic_baseline_filter_list_24, R.string.openvpn_pull_filters, "pullFilters", show = false),
            t(R.drawable.ic_baseline_call_split_24, R.string.routes, "routes", show = false),
            t(R.drawable.baseline_public_24, R.string.openvpn_route_gateway, "routeGateway"),
            t(R.drawable.ic_baseline_grid_3x3_24, R.string.openvpn_route_metric, "routeMetric", number = true),
            s(R.drawable.ic_baseline_call_split_24, R.string.openvpn_redirect_gateway, "redirectGateway"),
            t(R.drawable.ic_action_settings, R.string.openvpn_redirect_gateway_flags, "redirectGatewayFlags", show = false),
            s(R.drawable.ic_baseline_call_split_24, R.string.openvpn_redirect_private, "redirectPrivate"),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openvpn_block_ipv6, "blockIPv6"),
            t(R.drawable.ic_baseline_timer_24, R.string.openvpn_ping_interval, "pingInterval"),
            t(R.drawable.ic_baseline_timer_24, R.string.openvpn_ping_restart, "pingRestart"),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openvpn_ping_restart_disabled, "pingRestartDisabled"),
            t(R.drawable.ic_baseline_timer_24, R.string.openvpn_renegotiate_interval, "renegotiateInterval"),
            s(R.drawable.ic_baseline_no_encryption_gmailerrorred_24, R.string.openvpn_renegotiate_disabled, "renegotiateDisabled"),
            t(R.drawable.ic_baseline_grid_3x3_24, R.string.openvpn_renegotiate_bytes, "renegotiateBytes", number = true),
            t(R.drawable.ic_baseline_grid_3x3_24, R.string.openvpn_renegotiate_packets, "renegotiatePackets", number = true),
            t(R.drawable.ic_baseline_timer_24, R.string.openvpn_tls_timeout, "tlsTimeout"),
            t(R.drawable.ic_baseline_timer_24, R.string.openvpn_handshake_window, "handshakeWindow"),
            t(R.drawable.baseline_keyboard_tab_24, R.string.openvpn_explicit_exit_notify, "explicitExitNotify", number = true),
            c(R.string.pushed_dns),
            s(R.drawable.ic_baseline_dns_24, R.string.use_pushed_dns, "usePushedDNS"),
            s(R.drawable.ic_baseline_dns_24, R.string.accept_pushed_default_resolvers, "acceptPushedDefaultResolvers", "usePushedDNS"),
            s(R.drawable.ic_baseline_domain_24, R.string.expand_pushed_search_domains, "expandPushedSearchDomains", "usePushedDNS"),
        )
    }
    CacheProfileSettingsScreen(preferences)
}
