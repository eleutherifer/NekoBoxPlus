package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.openconnect.parseOpenConnectConfig
import io.nekohasekai.sagernet.fmt.openconnect.parseOpenConnectServerList
import io.nekohasekai.sagernet.fmt.openvpn.parseOpenVPNConfig
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardConfParser
import io.nekohasekai.sagernet.ktx.AmneziaApiKeyUnsupportedException
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.decodeBase64UrlSafe
import io.nekohasekai.sagernet.ktx.parseProxies
import org.json.JSONTokener

internal interface SubscriptionContentParser {
    suspend fun parse(text: String, fileName: String = ""): List<AbstractBean>?
}

internal object DefaultSubscriptionContentParser : SubscriptionContentParser {
    override suspend fun parse(text: String, fileName: String): List<AbstractBean>? {
        val importedName = fileName.substringBeforeLast('.', fileName)
        if (
            fileName.endsWith(".ovpn", ignoreCase = true) ||
            (text.lineSequence().any { it.trimStart().startsWith("remote ") } && text.contains("client"))
        ) {
            return listOf(parseOpenVPNConfig(text, importedName))
        }
        if (text.contains("<HostEntry", ignoreCase = true) && text.contains("<HostAddress", ignoreCase = true)) {
            return parseOpenConnectServerList(text)
        }
        if (
            (fileName.endsWith(".conf", ignoreCase = true) || fileName.endsWith(".config", ignoreCase = true)) &&
            !text.contains("[Interface]") &&
            text.lineSequence().any { it.trimStart().removePrefix("--").startsWith("server") }
        ) {
            return listOf(parseOpenConnectConfig(text, importedName))
        }

        XrayParser.parse(text)?.takeIf { it.isNotEmpty() }?.let { return it }
        ClashParser.parse(text)?.takeIf { it.isNotEmpty() }?.let { return it }

        if (WireGuardConfParser.looksLikeWireGuardConf(text)) {
            try {
                val document = WireGuardConfParser.parse(text)
                val profiles = if (document.isAmneziaWG) {
                    RawUpdater.parseAmneziaWG(text)
                } else {
                    RawUpdater.parseWireGuard(text)
                }
                profiles.forEach { profile ->
                    if (fileName.isNotBlank()) profile.name = fileName.removeSuffix(".conf")
                }
                return profiles
            } catch (error: Exception) {
                Logs.w(error)
            }
        }

        runCatching { JSONTokener(text).nextValue() }
            .mapCatching(RawUpdater::parseJSON)
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        try {
            parseProxies(text.decodeBase64UrlSafe()).takeIf { it.isNotEmpty() }?.let { return it }
        } catch (error: Exception) {
            if (error is AmneziaApiKeyUnsupportedException) throw error
            Logs.w(error)
        }

        try {
            return parseProxies(text).takeIf { it.isNotEmpty() }
        } catch (error: Exception) {
            if (error is AmneziaApiKeyUnsupportedException) throw error
        }
        return null
    }
}
