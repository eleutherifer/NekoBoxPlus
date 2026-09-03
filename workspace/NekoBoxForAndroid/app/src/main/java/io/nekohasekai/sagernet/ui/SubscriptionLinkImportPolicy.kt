package io.nekohasekai.sagernet.ui

import okhttp3.HttpUrl
import java.net.URLDecoder

object SubscriptionLinkImportPolicy {

    const val HAPP_DECRYPTOR_URL = "https://leeeet.dev/happ-decryptor/"

    data class Candidate(
        val link: String,
        val name: String?,
    )

    fun extractLinks(text: String): List<String> {
        return text.splitToSequence(Regex("""[\s<>\"']+"""))
            .map(::cleanLink)
            .filter { it.isNotBlank() }
            .toList()
    }

    fun singleHttpCandidate(text: String): Candidate? {
        val links = extractLinks(text).filter(::isHttpLink)
        if (links.size != 1) return null

        val link = links.single()
        return Candidate(
            link = linkWithoutFragment(link),
            name = linkFragment(link),
        )
    }

    fun singleQrSubscriptionCandidate(text: String): Candidate? {
        val links = extractLinks(text)
        if (links.size != 1) return null

        val link = links.single()
        return when {
            isHttpLink(link) -> singleHttpCandidate(link)
            link.startsWith("clash://install-config?", ignoreCase = true) ||
                    link.startsWith("clash://install-config/?", ignoreCase = true) ||
                    link.startsWith("sn://subscription?", ignoreCase = true) -> Candidate(link, null)
            else -> null
        }
    }

    fun toImportLink(candidate: Candidate): String {
        if (!isHttpLink(candidate.link)) return candidate.link

        return HttpUrl.Builder()
            .scheme("https")
            .host("install-config")
            .addQueryParameter("url", candidate.link)
            .apply {
                candidate.name?.let { addQueryParameter("name", it) }
            }
            .build()
            .toString()
            .replaceFirst("https://", "clash://")
    }

    fun isHappCryptLink(text: String): Boolean {
        val links = extractLinks(text)
        return links.size == 1 && links.single().startsWith("happ://crypt", ignoreCase = true)
    }

    fun isHttpLink(link: String): Boolean {
        return link.startsWith("http://", ignoreCase = true) ||
                link.startsWith("https://", ignoreCase = true)
    }

    fun linkWithoutFragment(link: String): String = link.substringBefore('#')

    fun linkFragment(link: String): String? {
        val encodedFragment = link.substringAfter('#', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            URLDecoder.decode(
                encodedFragment.replace("+", "%2B"),
                Charsets.UTF_8.name(),
            )
        }.getOrDefault(encodedFragment).takeIf { it.isNotBlank() }
    }

    private fun cleanLink(link: String): String {
        return link.trim()
            .trimStart('(', '[', '{', '<', '"', '\'')
            .trimEnd(',', '.', ';', ')', ']', '}', '>', '"', '\'')
    }
}
