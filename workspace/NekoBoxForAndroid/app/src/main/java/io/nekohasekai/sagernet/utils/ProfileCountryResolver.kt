package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isIpAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import libcore.Libcore
import java.net.InetAddress
import java.util.Locale

object ProfileCountryResolver {
    const val SOURCE_NONE = 0
    const val SOURCE_NAME = 1
    const val SOURCE_ENDPOINT = 2
    const val SOURCE_OUTBOUND = 3
    const val DOMAIN_BATCH_LIMIT = 10

    private const val DNS_TIMEOUT_MILLIS = 3_000L
    private const val REGIONAL_INDICATOR_START = 0x1F1E6
    private const val REGIONAL_INDICATOR_END = 0x1F1FF

    data class NameFlag(val code: String, val start: Int, val end: Int)

    fun initialize(entity: ProxyEntity) {
        entity.countryCode = ""
        entity.countrySource = SOURCE_NONE
        flagFromName(entity.displayName())?.let {
            entity.countryCode = it.code
            entity.countrySource = SOURCE_NAME
        }
        val address = entity.requireBean().serverAddress
        if (address.isIpAddress()) applyAddress(entity, address, SOURCE_ENDPOINT)
    }

    fun applyAddress(entity: ProxyEntity, address: String, source: Int): Boolean {
        val code = countryCodeForIp(address) ?: return false
        return applyCountryCode(entity, code, source)
    }

    private fun applyCountryCode(entity: ProxyEntity, code: String, source: Int): Boolean {
        if (!canReplace(entity.countrySource, source)) return false
        if (entity.countryCode == code && entity.countrySource == source) return false
        entity.countryCode = code
        entity.countrySource = source
        return true
    }

    internal fun canReplace(currentSource: Int, candidateSource: Int): Boolean = when (candidateSource) {
        SOURCE_NAME -> currentSource == SOURCE_NONE
        SOURCE_ENDPOINT -> currentSource != SOURCE_OUTBOUND
        SOURCE_OUTBOUND -> true
        else -> false
    }

    /**
     * Cheap load-time backfill. This deliberately never resolves host names: callers may invoke it
     * for arbitrarily large profile lists without causing DNS traffic.
     */
    fun backfillLiteralAddresses(profiles: List<ProxyEntity>): List<ProxyEntity> = profiles.filter {
        val address = it.requireBean().serverAddress
        address.isIpAddress() && applyAddress(it, address, SOURCE_ENDPOINT)
    }

    internal fun domainLookupIndexes(addresses: List<String>): List<Int> = addresses.withIndex()
        .filterNot { it.value.isIpAddress() }
        .take(DOMAIN_BATCH_LIMIT)
        .map { it.index }

    suspend fun updateFromAddress(profileId: Long, address: String, source: Int): Boolean {
        val profile = ProfileManager.getProfile(profileId) ?: return false
        if (!applyAddress(profile, address, source)) return false
        ProfileManager.updateProfile(profile)
        return true
    }

    suspend fun updateFromCountryCode(profileId: Long, code: String, source: Int): Boolean {
        val normalizedCode = code.trim().uppercase(Locale.ROOT).takeIf(::isCountryCode)
            ?: return false
        val profile = ProfileManager.getProfile(profileId) ?: return false
        if (!applyCountryCode(profile, normalizedCode, source)) return false
        ProfileManager.updateProfile(profile)
        return true
    }

    suspend fun resolveAndUpdateDomain(profileId: Long) {
        val profile = ProfileManager.getProfile(profileId) ?: return
        val host = profile.requireBean().serverAddress
        if (host.isBlank() || host.isIpAddress()) return
        val addresses = resolveDomain(host)
        for (address in addresses) {
            val code = countryCodeForIp(address) ?: continue
            if (applyCountryCode(profile, code, SOURCE_ENDPOINT)) ProfileManager.updateProfile(profile)
            break
        }
    }

    suspend fun resolveDomain(host: String): List<String> {
        return try {
            withTimeoutOrNull(DNS_TIMEOUT_MILLIS) {
                runInterruptible(Dispatchers.IO) {
                    val addresses = if (DataStore.serviceState.connected) {
                        // The default network uses the active VPN's remote DNS when available.
                        InetAddress.getAllByName(host).asList()
                    } else {
                        SagerNet.underlyingNetwork?.getAllByName(host)?.asList()
                            ?: InetAddress.getAllByName(host).asList()
                    }
                    addresses.mapNotNull(InetAddress::getHostAddress).distinct()
                }
            } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
            emptyList()
        }
    }

    fun countryCodeForIp(address: String): String? = runCatching {
        Libcore.countryCodeForIP(address).trim().uppercase(Locale.ROOT).takeIf(::isCountryCode)
    }.onFailure(Logs::w).getOrNull()

    fun flagFromName(name: String): NameFlag? {
        var index = 0
        while (index < name.length) {
            val first = name.codePointAt(index)
            val firstSize = Character.charCount(first)
            val secondIndex = index + firstSize
            if (first in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END && secondIndex < name.length) {
                val second = name.codePointAt(secondIndex)
                if (second in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END) {
                    val code = buildString(2) {
                        append(('A'.code + first - REGIONAL_INDICATOR_START).toChar())
                        append(('A'.code + second - REGIONAL_INDICATOR_START).toChar())
                    }
                    return NameFlag(code, index, secondIndex + Character.charCount(second))
                }
            }
            index += firstSize
        }
        return null
    }

    fun effectiveCountryCode(entity: ProxyEntity): String =
        entity.countryCode.takeIf { isCountryCode(it) }
            ?: flagFromName(entity.displayName())?.code.orEmpty()

    fun presentationName(entity: ProxyEntity, countryBadgeVisible: Boolean = true): String {
        val original = entity.displayName()
        if (!countryBadgeVisible || effectiveCountryCode(entity).isBlank()) return original
        val flag = flagFromName(original) ?: return original
        var start = flag.start
        var end = flag.end
        if (end < original.length && original[end].isWhitespace()) {
            end++
        } else if (start > 0 && original[start - 1].isWhitespace()) {
            start--
        }
        return original.removeRange(start, end).trim().ifBlank { entity.displayAddress() }
    }

    private fun isCountryCode(code: String): Boolean =
        code.length == 2 && code.all { it in 'A'..'Z' }
}
