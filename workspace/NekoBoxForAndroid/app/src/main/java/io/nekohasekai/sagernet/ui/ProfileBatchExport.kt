package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.buildAmneziaWGJsonContainer
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class ProfileBatchExportResult(
    val entries: List<ProfileBatchExportEntry>,
    val skipped: Int,
) {
    val text: String
        get() = entries.joinToString("\n") { it.content }
}

internal data class ProfileBatchExportEntry(
    val profileName: String,
    val fileName: String,
    val content: String,
)

internal object ProfileBatchExport {
    fun standardLinks(profiles: List<ProxyEntity>) = collect(profiles) { profile ->
        if (!profile.haveStandardLink()) return@collect null
        ProfileBatchExportEntry(
            profile.displayName(),
            "profiles.txt",
            profile.toStdLink(),
        )
    }

    fun universalLinks(profiles: List<ProxyEntity>) = collect(profiles) { profile ->
        if (!profile.haveLink()) return@collect null
        ProfileBatchExportEntry(
            profile.displayName(),
            "profiles.txt",
            profile.requireBean().toUniversalLink(),
        )
    }

    fun configurations(profiles: List<ProxyEntity>) = collect(profiles) { profile ->
        val (content, fileName) = profile.exportConfig()
        ProfileBatchExportEntry(profile.displayName(), fileName, content)
    }

    fun amneziaWGJson(profiles: List<ProxyEntity>): ProfileBatchExportResult {
        val beans = profiles.mapNotNull { it.requireBean() as? AmneziaWGBean }
        if (beans.isEmpty()) return ProfileBatchExportResult(emptyList(), profiles.size)
        return ProfileBatchExportResult(
            entries = listOf(
                ProfileBatchExportEntry(
                    profileName = "AmneziaWG",
                    fileName = "amneziawg.json",
                    content = buildAmneziaWGJsonContainer(beans),
                ),
            ),
            skipped = profiles.size - beans.size,
        )
    }

    fun configurationClipboardText(entries: List<ProfileBatchExportEntry>): String =
        entries.joinToString("\n\n") {
            "# ${it.profileName} (${it.fileName})\n${it.content}"
        }

    fun configurationZip(entries: List<ProfileBatchExportEntry>): ByteArray {
        val usedNames = mutableSetOf<String>()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEachIndexed { index, entry ->
                val fallback = "profile_${index + 1}.txt"
                val safeName = uniqueFileName(sanitizeFileName(entry.fileName, fallback), usedNames)
                zip.putNextEntry(ZipEntry(safeName))
                zip.write(entry.content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    internal fun sanitizeFileName(fileName: String, fallback: String): String {
        val leaf = fileName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\u0000-\u001f<>:"|?*]"""), "_")
            .trim()
            .trim('.')
        return leaf.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: fallback
    }

    private fun uniqueFileName(fileName: String, usedNames: MutableSet<String>): String {
        if (usedNames.add(fileName.lowercase())) return fileName
        val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        val base = fileName.substring(0, dot)
        val extension = fileName.substring(dot)
        var suffix = 2
        while (true) {
            val candidate = "$base ($suffix)$extension"
            if (usedNames.add(candidate.lowercase())) return candidate
            suffix++
        }
    }

    private inline fun collect(
        profiles: List<ProxyEntity>,
        exporter: (ProxyEntity) -> ProfileBatchExportEntry?,
    ): ProfileBatchExportResult {
        val entries = ArrayList<ProfileBatchExportEntry>(profiles.size)
        var skipped = 0
        profiles.forEach { profile ->
            val entry = runCatching { exporter(profile) }.getOrNull()
            if (entry == null || entry.content.isBlank()) {
                skipped++
            } else {
                entries += entry
            }
        }
        return ProfileBatchExportResult(entries, skipped)
    }
}
