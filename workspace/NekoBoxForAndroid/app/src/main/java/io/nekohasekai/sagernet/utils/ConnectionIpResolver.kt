package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.CONNECTION_IP_RESOLVE_URL
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.dto.IPAPIInfo
import io.nekohasekai.sagernet.ktx.Logs
import libcore.Libcore
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.Util
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit

object ConnectionIpResolver {
    data class Result(
        val displayText: String,
        val ip: String,
        val countryCode: String?,
    )

    fun resolve(): Result? {
        val url = DataStore.connectionIPResolveURL.takeIf { it.isNotBlank() } ?: CONNECTION_IP_RESOLVE_URL
        return if (DataStore.serviceMode == Key.MODE_PROXY) {
            resolveViaHttpProxy(url)
        } else {
            resolveViaSocks(url)
        }
    }

    private fun resolveViaSocks(url: String): Result? {
        val client =
            Libcore.newHttpClient().apply {
                withUTLS(DataStore.appUTLSFingerprint)
                modernTLS()
                keepAlive()
                trySocks5(
                    DataStore.mixedListener,
                    DataStore.mixedPort,
                    DataStore.mixedUsername,
                    DataStore.mixedPassword,
                )
            }

        return try {
            val response =
                client
                    .newRequest()
                    .apply {
                        setURL(url)
                    }.execute()
            val content = Util.getStringBox(response.contentString)
            format(JavaUtil.gson.fromJson(content, IPAPIInfo::class.java))
        } catch (e: Exception) {
            Logs.w(e)
            null
        } finally {
            client.close()
        }
    }

    private fun resolveViaHttpProxy(url: String): Result? {
        val proxyCredentials = Credentials.basic(DataStore.mixedUsername, DataStore.mixedPassword)
        val client =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(DataStore.mixedListener, DataStore.mixedPort)))
                .proxyAuthenticator { _, response ->
                    response.request
                        .newBuilder()
                        .header("Proxy-Authorization", proxyCredentials)
                        .build()
                }.build()

        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) error("IP resolve failed: HTTP ${response.code}")
                val content = response.body?.string() ?: return null
                format(JavaUtil.gson.fromJson(content, IPAPIInfo::class.java))
            }
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
    }

    internal fun format(ipInfo: IPAPIInfo?): Result? {
        if (ipInfo == null) return null

        val ip =
            firstNotBlank(
                ipInfo.ip,
                ipInfo.clientIp,
                ipInfo.ip_addr,
                ipInfo.query,
            ) ?: return null

        val countryCode = firstCountryCode(
            ipInfo.country_code,
            ipInfo.countryCode,
            ipInfo.location?.country_code,
            ipInfo.country_info?.country_code,
            ipInfo.countryInfo?.country_code,
            ipInfo.country_info?.code,
            ipInfo.countryInfo?.code,
        )
        val country =
            firstNotBlank(
                countryCode,
                ipInfo.country,
                ipInfo.country_name,
                ipInfo.location?.country_name,
                ipInfo.country_info?.country_name,
                ipInfo.countryInfo?.country_name,
                ipInfo.country_info?.name,
                ipInfo.countryInfo?.name,
            )
        val region =
            firstNotBlank(
                ipInfo.region_name,
                ipInfo.regionName,
                ipInfo.region,
                ipInfo.location?.region_name,
                ipInfo.region_info?.region_name,
                ipInfo.regionInfo?.region_name,
                ipInfo.region_info?.name,
                ipInfo.regionInfo?.name,
                ipInfo.city_name,
                ipInfo.city,
                ipInfo.location?.city_name,
                ipInfo.city_info?.city_name,
                ipInfo.cityInfo?.city_name,
                ipInfo.city_info?.name,
                ipInfo.cityInfo?.name,
            )

        val flag = countryCode?.let(::flagEmoji)
        val displayText = buildString {
            if (!flag.isNullOrBlank()) append(flag).append(' ')
            append(ip)
            if (!country.isNullOrBlank()) append(' ').append(country)
            if (!region.isNullOrBlank()) append(", ").append(region)
        }
        return Result(displayText, ip, countryCode)
    }

    private fun firstNotBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

    private fun firstCountryCode(vararg values: String?): String? =
        values.firstNotNullOfOrNull(::normalizeCountryCode)

    internal fun normalizeCountryCode(countryCode: String?): String? {
        val code = countryCode?.trim()?.uppercase(Locale.ROOT) ?: return null
        return code.takeIf { it.length == 2 && it.all { character -> character in 'A'..'Z' } }
    }

    private fun flagEmoji(countryCode: String): String? {
        val code = normalizeCountryCode(countryCode) ?: return null
        val first = code[0].code - 'A'.code + 0x1F1E6
        val second = code[1].code - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }
}
