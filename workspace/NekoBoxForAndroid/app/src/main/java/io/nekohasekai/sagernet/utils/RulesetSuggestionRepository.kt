package io.nekohasekai.sagernet.utils

import android.content.Context
import io.nekohasekai.sagernet.SagerNet
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

data class RulesetSuggestionCatalog(
    val rsipSuggestions: List<String>,
    val rssiteSuggestions: List<String>,
    val rsipUrls: Map<String, String>,
    val rssiteUrls: Map<String, String>,
) {
    val allSuggestions: List<String> = rsipSuggestions + rssiteSuggestions

    fun resolve(prefixedEntry: String): String? {
        return when {
            prefixedEntry.startsWith("rsip:") -> rsipUrls[prefixedEntry]
            prefixedEntry.startsWith("rssite:") -> rssiteUrls[prefixedEntry]
            else -> null
        }
    }
}

object RulesetSuggestionRepository {
    private const val throneExternalFileName = "throne-ruleset-srslist.h"
    private const val throneAssetPath = "sing-box/throne-ruleset-srslist.h"
    private const val throneVersionFileName = "throne-ruleset.version.txt"
    private const val itdogExternalFileName = "itdog-ruleset.json"
    private const val itdogVersionFileName = "itdog-ruleset.version.txt"
    private const val rsipPrefix = "rsip:"
    private const val rssitePrefix = "rssite:"

    enum class Source(
        val externalFileName: String,
        val versionFileName: String,
        val assetPath: String? = null,
    ) {
        THRONE(
            externalFileName = throneExternalFileName,
            versionFileName = throneVersionFileName,
            assetPath = throneAssetPath,
        ),
        ITDOG(
            externalFileName = itdogExternalFileName,
            versionFileName = itdogVersionFileName,
            assetPath = "sing-box/$itdogExternalFileName",
        ),
    }

    @Volatile
    private var cachedSignature: String? = null

    @Volatile
    private var cachedCatalog: RulesetSuggestionCatalog? = null

    fun load(context: Context = SagerNet.application): RulesetSuggestionCatalog {
        val signature = Source.values().joinToString(separator = "|") { source ->
            val sourceFile = ensureExternalFile(source, context)
            when {
                sourceFile.isFile -> "${source.name}:external:${sourceFile.length()}:${sourceFile.lastModified()}"
                source.assetPath != null -> "${source.name}:asset"
                else -> "${source.name}:missing"
            }
        }
        cachedCatalog?.takeIf { cachedSignature == signature }?.let { return it }
        return synchronized(this) {
            cachedCatalog?.takeIf { cachedSignature == signature }?.let { return@synchronized it }
            val rsipMap = linkedMapOf<String, String>()
            val rssiteMap = linkedMapOf<String, String>()

            Source.values().forEach { source ->
                val content = loadContent(source, context) ?: return@forEach
                when (source) {
                    Source.THRONE -> parseThrone(content, rsipMap, rssiteMap)
                    Source.ITDOG -> parseITDog(content, rsipMap)
                }
            }

            buildCatalog(rsipMap, rssiteMap).also {
                cachedSignature = signature
                cachedCatalog = it
            }
        }
    }

    fun resolveExternalFile(context: Context = SagerNet.application): File {
        return resolveExternalFile(Source.THRONE, context)
    }

    fun resolveExternalFile(source: Source, context: Context = SagerNet.application): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, source.externalFileName)
    }

    fun resolveVersionFile(source: Source, context: Context = SagerNet.application): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, source.versionFileName)
    }

    fun ensureExternalFile(source: Source, context: Context = SagerNet.application): File {
        val externalFile = resolveExternalFile(source, context)
        if (externalFile.isFile || source.assetPath == null) {
            return externalFile
        }

        val versionFile = resolveVersionFile(source, context)
        val parent = externalFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        runCatching {
            context.assets.open(source.assetPath).use { input ->
                externalFile.outputStream().use { output -> input.copyTo(output) }
            }
            context.assets.open("sing-box/${source.versionFileName}").bufferedReader().use { reader ->
                versionFile.writeText(reader.readText().trim())
            }
        }.onFailure {
            externalFile.delete()
            versionFile.delete()
        }

        return externalFile
    }

    private fun loadContent(source: Source, context: Context): String? {
        val sourceFile = ensureExternalFile(source, context)
        if (sourceFile.isFile) {
            return sourceFile.readText()
        }
        val assetPath = source.assetPath ?: return null
        return context.assets.open(assetPath).bufferedReader().use { it.readText() }
    }

    private fun parseThrone(
        content: String,
        rsipMap: MutableMap<String, String>,
        rssiteMap: MutableMap<String, String>,
    ) {
        // Matches C++ map entries like:
        // {"geoip-cn", "https://example.com/file.srs"},
        // {"geosite-google", "https://example.com/file.srs"},
        val entryRegex = Regex(
            """\{\s*"((?:\\.|[^"\\])*)"\s*,\s*"((?:\\.|[^"\\])*)"\s*\}""",
            setOf(RegexOption.MULTILINE)
        )

        fun unescapeCppString(value: String): String {
            return buildString(value.length) {
                var i = 0
                while (i < value.length) {
                    val ch = value[i]
                    if (ch == '\\' && i + 1 < value.length) {
                        when (val next = value[i + 1]) {
                            '\\' -> append('\\')
                            '"' -> append('"')
                            'n' -> append('\n')
                            'r' -> append('\r')
                            't' -> append('\t')
                            else -> append(next)
                        }
                        i += 2
                    } else {
                        append(ch)
                        i++
                    }
                }
            }
        }

        for (match in entryRegex.findAll(content)) {
            val tag = unescapeCppString(match.groupValues[1])
            val url = unescapeCppString(match.groupValues[2])

            when {
                tag.startsWith("geoip-") -> {
                    val alias = tag.removePrefix("geoip-")
                    if (alias.isNotBlank()) rsipMap["$rsipPrefix$alias"] = url
                }
                tag.startsWith("geosite-") -> {
                    val alias = tag.removePrefix("geosite-")
                    if (alias.isNotBlank()) rssiteMap["$rssitePrefix$alias"] = url
                }
            }
        }
    }

    private fun parseITDog(
        content: String,
        rsipMap: MutableMap<String, String>,
    ) {
        val root = JSONObject(content)
        val keys = root.keys()
        while (keys.hasNext()) {
            val alias = keys.next()
            val entry = root.opt(alias) ?: continue
            when (entry) {
                is JSONObject -> {
                    entry.optString("rsip").takeIf { it.isNotBlank() }?.let { rsipMap["$rsipPrefix$alias"] = it }
                }
                is String -> {
                    when {
                        entry.isITDogSrsList() -> rsipMap["$rsipPrefix$alias"] = entry
                    }
                }
            }
        }
    }

    private fun buildCatalog(
        rsipMap: Map<String, String>,
        rssiteMap: Map<String, String>,
    ): RulesetSuggestionCatalog {
        val comparator = compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it }
        val sortedRsip = rsipMap.keys.sortedWith(comparator)
        val sortedRssite = rssiteMap.keys.sortedWith(comparator)

        return RulesetSuggestionCatalog(
            rsipSuggestions = sortedRsip,
            rssiteSuggestions = sortedRssite,
            rsipUrls = sortedRsip.associateWith { rsipMap.getValue(it) },
            rssiteUrls = sortedRssite.associateWith { rssiteMap.getValue(it) },
        )
    }

    private fun String.isITDogSrsList(): Boolean {
        return substringAfterLast('/').substringBefore('?').endsWith(".srs", ignoreCase = true)
    }
}
