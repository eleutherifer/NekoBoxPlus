package io.nekohasekai.sagernet.fmt.openconnect

import io.nekohasekai.sagernet.ktx.applyDefaultValues

private fun configValue(line: String): Pair<String, String>? {
    val clean = line.substringBefore('#').trim()
    if (clean.isEmpty()) return null
    val separator = clean.indexOfAny(charArrayOf('=', ' ', '\t'))
    return if (separator < 0) clean.removePrefix("--") to "true"
    else clean.substring(0, separator).removePrefix("--") to clean.substring(separator + 1).trim().trim('"', '\'')
}

fun parseOpenConnectConfig(text: String, profileName: String = ""): OpenConnectBean {
    val bean = OpenConnectBean().applyDefaultValues().apply { name = profileName }
    for (raw in text.lineSequence()) {
        val (rawKey, value) = configValue(raw) ?: continue
        when (val key = rawKey.lowercase()) {
            "server" -> bean.server = value
            "protocol" -> bean.flavor = value
            "user" -> bean.username = value
            "authgroup" -> bean.authGroup = value
            "cookie" -> bean.cookie = value
            "token-mode" -> bean.tokenMode = value
            "token-secret" -> bean.tokenSecret = value
            "os" -> bean.reportedOS = value
            "useragent" -> bean.userAgent = value
            "version-string" -> bean.clientVersion = value
            "local-hostname" -> bean.localHostname = value
            "no-dtls" -> bean.noUDP = value.toBoolean()
            "base-mtu" -> bean.baseMTU = value.toIntOrNull() ?: 0
            "mtu" -> bean.mtu = value.toIntOrNull() ?: 0
            "force-dpd" -> bean.dpdInterval = value
            "reconnect-timeout" -> bean.reconnectTimeout = value
            "trojan-interval" -> bean.trojanInterval = value
            "queue-len" -> bean.queueLength = value.toIntOrNull() ?: 0
            "no-ipv6" -> bean.ipv6Disabled = value.toBoolean()
            "no-http-keepalive" -> bean.httpKeepaliveDisabled = value.toBoolean()
            "no-xmlpost" -> bean.xmlPostDisabled = value.toBoolean()
            "no-external-auth" -> bean.externalAuthDisabled = value.toBoolean()
            "no-passwd" -> bean.passwordAuthenticationDisabled = value.toBoolean()
            "pfs" -> bean.pfs = value.toBoolean()
            "no-system-trust" -> bean.tlsSystemTrustDisabled = value.toBoolean()
            "server-name" -> bean.tlsServerName = value
            "allow-insecure-crypto" -> bean.allowInsecureCrypto = value.toBoolean()
            "servercert" -> bean.tlsPeerFingerprints += value + "\n"
            "certificate", "sslkey", "cafile", "mca-certificate", "mca-key", "csd-wrapper", "hip-wrapper" ->
                error("External OpenConnect file reference '$key' is not supported")
            "form-entry" -> bean.formEntries = value
        }
    }
    if (bean.server.isBlank()) error("OpenConnect profile does not contain a server")
    bean.syncServerAddress()
    return bean
}

fun parseOpenConnectServerList(text: String): List<OpenConnectBean> {
    val entries = Regex("<HostEntry>(.*?)</HostEntry>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(text)
    return entries.mapNotNull { match ->
        fun tag(name: String) = Regex("<$name>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(match.groupValues[1])?.groupValues?.get(1)?.trim()
        val address = tag("HostAddress") ?: return@mapNotNull null
        OpenConnectBean().applyDefaultValues().apply { name = tag("HostName").orEmpty(); server = address; syncServerAddress() }
    }.toList()
}
