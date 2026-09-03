package io.nekohasekai.sagernet.group

import androidx.core.net.toUri
import io.nekohasekai.sagernet.SpoofApp
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.app
import libcore.Libcore
import libcore.HTTPRequest
import libcore.HTTPResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.matsuri.nb4a.utils.Util
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class SubscriptionDocument(
    val body: String,
    val headers: SubscriptionHeaders = SubscriptionHeaders.EMPTY,
    val source: Source,
) {
    enum class Source {
        CONTENT,
        NETWORK,
    }
}

internal class SubscriptionHeaders private constructor(
    private val values: Map<String, String>,
) {
    operator fun get(name: String): String = values[name.lowercase(Locale.ROOT)].orEmpty()

    companion object {
        val EMPTY = SubscriptionHeaders(emptyMap())

        fun of(values: Map<String, String>) = SubscriptionHeaders(
            values.mapKeys { (name, _) -> name.lowercase(Locale.ROOT) },
        )
    }
}

internal interface SubscriptionReader {
    suspend fun read(subscription: SubscriptionBean): SubscriptionDocument
}

private suspend fun HTTPRequest.executeCancellable(): HTTPResponse =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        CoroutineScope(continuation.context).launch(Dispatchers.IO) {
            runCatching { execute() }
                .onSuccess { response ->
                    if (continuation.isActive) continuation.resume(response)
                }
                .onFailure { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
    }

internal object DefaultSubscriptionReader : SubscriptionReader {
    private val capturedHeaders = listOf(
        "Subscription-Userinfo",
        "announce",
        "announce-url",
        "autorouting",
        "content-disposition",
        "homepage",
        "profile-title",
        "profile-update-interval",
        "profile-web-page-url",
        "routing",
        "support-email",
        "support-url",
        "x-hwid-limit",
        "x-hwid-max-devices-reached",
        "x-hwid-not-supported",
    )

    override suspend fun read(subscription: SubscriptionBean): SubscriptionDocument {
        val link = subscription.link
        if (link.startsWith("content://")) {
            val body = app.contentResolver.openInputStream(link.toUri())?.use { input ->
                input.bufferedReader().use { it.readText() }
            }.orEmpty()
            return SubscriptionDocument(body, source = SubscriptionDocument.Source.CONTENT)
        }

        val client = Libcore.newHttpClient().apply {
            withUTLS(DataStore.appUTLSFingerprint)
            setTimeoutMillis(GroupUpdater.SUBSCRIPTION_UPDATE_TIMEOUT_MILLIS)
            tryH3Direct()
            if (DataStore.appTLSVersion == "1.3") restrictedTLS()
        }
        return try {
            val response = client.newRequest().apply {
                if (DataStore.allowInsecureOnRequest) allowInsecure()
                setURL(link)
                val fingerprint = buildSubscriptionRequestFingerprint(
                    spoofApp = subscription.spoofApp ?: SpoofApp.NONE,
                    hwidEnabled = subscription.hwidEnabled == true,
                    customUserAgent = subscription.customUserAgent,
                    fallbackUserAgent = USER_AGENT,
                )
                setUserAgent(fingerprint.userAgent)
                for ((name, value) in fingerprint.headers) setHeader(name, value)
            }.executeCancellable()
            SubscriptionDocument(
                body = Util.getStringBox(response.contentString),
                headers = SubscriptionHeaders.of(
                    capturedHeaders.associateWith { name ->
                        Util.getStringBox(response.getHeader(name))
                    },
                ),
                source = SubscriptionDocument.Source.NETWORK,
            )
        } finally {
            client.close()
        }
    }
}
