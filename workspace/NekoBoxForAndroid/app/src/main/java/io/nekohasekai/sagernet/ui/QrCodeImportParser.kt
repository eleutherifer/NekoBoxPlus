package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.parseAmneziaVpnLink

internal sealed class QrCodeImportResult {
    data class Profiles(val profiles: List<AbstractBean>) : QrCodeImportResult()
    data class Subscription(
        val candidate: SubscriptionLinkImportPolicy.Candidate,
    ) : QrCodeImportResult()
    object Empty : QrCodeImportResult()
}

internal object QrCodeImportParser {
    suspend fun parse(text: String): QrCodeImportResult {
        return try {
            val profiles = when {
                text.startsWith("vpn://") -> parseAmneziaVpnLink(text)
                else -> RawUpdater.parseRaw(text)
            }
            if (profiles.isNullOrEmpty()) {
                SubscriptionLinkImportPolicy.singleQrSubscriptionCandidate(text)
                    ?.let(QrCodeImportResult::Subscription)
                    ?: QrCodeImportResult.Empty
            } else {
                QrCodeImportResult.Profiles(profiles)
            }
        } catch (error: SubscriptionFoundException) {
            SubscriptionLinkImportPolicy.singleQrSubscriptionCandidate(error.link)
                ?.let(QrCodeImportResult::Subscription)
                ?: QrCodeImportResult.Empty
        }
    }
}
