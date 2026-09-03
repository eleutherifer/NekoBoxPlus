package io.nekohasekai.sagernet.utils

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

object CustomThemeLink {

    private const val PREFIX = "sn://customtheme?"
    private const val MAX_LINK_LENGTH = 8 * 1024
    private val colorPattern = Regex("^[0-9A-Fa-f]{6}$")

    fun encode(state: CustomTheme.State): String {
        return buildString {
            append(PREFIX)
            var first = true
            fun parameter(key: String, value: String) {
                if (!first) append('&')
                first = false
                append(key)
                append('=')
                append(value)
            }

            CustomTheme.colorSpecs.forEach { spec ->
                parameter("light.${spec.key}", colorValue(state.light, spec.key))
            }
            CustomTheme.colorSpecs.forEach { spec ->
                parameter("dark.${spec.key}", colorValue(state.dark, spec.key))
            }
            parameter("dynamicColors", state.dynamicColors.toString())
            parameter("headerPrimary", state.headerPrimary.toString())
            parameter("statsBarPrimary", state.statsBarPrimary.toString())
        }
    }

    fun decode(link: String): CustomTheme.State {
        require(link.length <= MAX_LINK_LENGTH) { "Custom theme link is too long" }
        val uri = URI(link)
        require(uri.scheme.equals("sn", ignoreCase = true)) { "Invalid custom theme scheme" }
        require(uri.host.equals("customtheme", ignoreCase = true)) { "Invalid custom theme host" }
        require(uri.rawUserInfo == null && uri.port == -1 && uri.rawPath.isNullOrEmpty()) {
            "Invalid custom theme authority or path"
        }
        require(uri.rawFragment == null) { "Custom theme links cannot contain fragments" }
        val query = uri.rawQuery?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Custom theme link has no options")
        val knownKeys = buildSet {
            CustomTheme.colorSpecs.forEach { spec ->
                add("light.${spec.key}")
                add("dark.${spec.key}")
            }
            add("dynamicColors")
            add("headerPrimary")
            add("statsBarPrimary")
        }
        val parameters = mutableMapOf<String, String>()
        query.split('&').forEach { item ->
            require(item.isNotBlank() && item.contains('=')) { "Invalid custom theme option" }
            val key = decodeComponent(item.substringBefore('='))
            val value = decodeComponent(item.substringAfter('='))
            if (key in knownKeys) {
                require(parameters.put(key, value) == null) { "Duplicate custom theme option: $key" }
            }
        }
        require(parameters.keys.containsAll(knownKeys)) { "Custom theme link is incomplete" }

        fun palette(prefix: String): CustomTheme.Palette {
            return CustomTheme.Palette(CustomTheme.colorSpecs.associate { spec ->
                val value = parameters.getValue("$prefix.${spec.key}")
                require(colorPattern.matches(value)) { "Invalid custom theme color" }
                spec.key to (0xFF000000L or value.toLong(16)).toInt()
            }.toMutableMap())
        }

        return CustomTheme.State(
            light = palette("light"),
            dark = palette("dark"),
            dynamicColors = booleanValue(parameters.getValue("dynamicColors")),
            headerPrimary = booleanValue(parameters.getValue("headerPrimary")),
            statsBarPrimary = booleanValue(parameters.getValue("statsBarPrimary")),
        )
    }

    fun extractCandidates(text: String): List<String> {
        return text.splitToSequence(Regex("""[\s<>\"']+"""))
            .map { candidate ->
                candidate.trim()
                    .trimStart('(', '[', '{', '<', '"', '\'')
                    .trimEnd(',', '.', ';', ')', ']', '}', '>', '"', '\'')
            }
            .filter { it.startsWith(PREFIX, ignoreCase = true) }
            .toList()
    }

    private fun colorValue(palette: CustomTheme.Palette, key: String): String {
        val color = palette.colors[key]
            ?: throw IllegalArgumentException("Missing custom theme color: $key")
        return String.format(Locale.ROOT, "%06X", color and 0xFFFFFF)
    }

    private fun booleanValue(value: String): Boolean {
        return when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> throw IllegalArgumentException("Invalid custom theme boolean")
        }
    }

    private fun decodeComponent(value: String): String {
        return URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
    }
}
