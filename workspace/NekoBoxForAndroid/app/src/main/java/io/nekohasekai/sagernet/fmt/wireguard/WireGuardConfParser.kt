package io.nekohasekai.sagernet.fmt.wireguard

import java.util.Locale

internal data class WireGuardConfDocument(
    val interfaceOptions: WireGuardConfOptions,
    val peers: List<WireGuardConfOptions>,
) {
    val isAmneziaWG: Boolean
        get() =
            interfaceOptions.keys.any(WireGuardConfParser.amneziaWGKeys::contains) ||
                peers.any { peer ->
                    (peer["PersistentKeepalive"] ?: peer["PersistentKeepAlive"])
                        ?.contains('-') == true
                }
}

internal class WireGuardConfOptions {
    private val values = linkedMapOf<String, MutableList<String>>()

    val keys: Set<String>
        get() = values.keys

    operator fun get(key: String): String? = values[normalizeKey(key)]?.lastOrNull()

    fun getAll(key: String): List<String> = values[normalizeKey(key)].orEmpty()

    internal fun add(key: String, value: String) {
        values.getOrPut(normalizeKey(key), ::mutableListOf).add(value)
    }

    private fun normalizeKey(key: String): String = key.lowercase(Locale.ROOT)
}

internal object WireGuardConfParser {
    internal val amneziaWGKeys =
        setOf(
            "jc",
            "jmin",
            "jmax",
            "s1",
            "s2",
            "s3",
            "s4",
            "h1",
            "h2",
            "h3",
            "h4",
            "i1",
            "i2",
            "i3",
            "i4",
            "i5",
            "headerprotectionkey",
            "contentpaddingaddition",
            "rekeyaftertime",
            "rekeytimeout",
            "rejectaftertime",
            "keepalivetimeout",
            "maxhandshakeattempts",
            "randomtrailers",
            "disablecookies",
        )

    private val multilineKeys = setOf("i1", "i2", "i3", "i4", "i5")

    private enum class Section {
        NONE,
        INTERFACE,
        PEER,
    }

    fun looksLikeWireGuardConf(conf: String): Boolean =
        conf.lineSequence().any { rawLine ->
            rawLine
                .removePrefix("\uFEFF")
                .substringBefore('#')
                .trim()
                .equals("[Interface]", ignoreCase = true)
        }

    fun parse(conf: String): WireGuardConfDocument {
        val interfaceOptions = WireGuardConfOptions()
        val peers = mutableListOf<WireGuardConfOptions>()
        var currentPeer: WireGuardConfOptions? = null
        var section = Section.NONE
        var interfaceSeen = false
        var pendingKey: String? = null
        var pendingValue: StringBuilder? = null

        fun currentOptions(): WireGuardConfOptions =
            when (section) {
                Section.INTERFACE -> interfaceOptions
                Section.PEER -> currentPeer ?: error("WireGuard peer option outside a [Peer] section")
                Section.NONE -> error("WireGuard option before any section")
            }

        fun flushPending() {
            val key = pendingKey ?: return
            currentOptions().add(key, requireNotNull(pendingValue).toString())
            pendingKey = null
            pendingValue = null
        }

        fun startSection(line: String) {
            flushPending()
            when {
                line.equals("[Interface]", ignoreCase = true) -> {
                    interfaceSeen = true
                    section = Section.INTERFACE
                    currentPeer = null
                }

                line.equals("[Peer]", ignoreCase = true) -> {
                    section = Section.PEER
                    currentPeer = WireGuardConfOptions().also(peers::add)
                }

                else -> error("Unknown WireGuard section: $line")
            }
        }

        conf.lineSequence().forEachIndexed { index, rawLine ->
            val withoutBom = if (index == 0) rawLine.removePrefix("\uFEFF") else rawLine
            val trimmedStart = withoutBom.trimStart()
            if (trimmedStart.startsWith(';')) {
                flushPending()
                return@forEachIndexed
            }

            val line = withoutBom.substringBefore('#').trim()
            if (line.isEmpty()) {
                flushPending()
                return@forEachIndexed
            }
            if (line.startsWith('[')) {
                startSection(line)
                return@forEachIndexed
            }

            val separator = line.indexOf('=')
            if (separator < 0) {
                val value = pendingValue
                    ?: error("Malformed WireGuard option at line ${index + 1}: $line")
                value.append(line.removeSuffix("\\").trim())
                return@forEachIndexed
            }

            flushPending()
            if (section == Section.NONE) {
                error("WireGuard option before any section at line ${index + 1}")
            }
            val key = line.substring(0, separator).trim()
            require(key.isNotEmpty()) {
                "Empty WireGuard option name at line ${index + 1}"
            }
            val value = line.substring(separator + 1).trim()
            if (section == Section.INTERFACE && key.lowercase(Locale.ROOT) in multilineKeys) {
                pendingKey = key
                pendingValue = StringBuilder(value.removeSuffix("\\").trim())
            } else {
                currentOptions().add(key, value)
            }
        }
        flushPending()

        require(interfaceSeen) { "Missing [Interface] section" }
        return WireGuardConfDocument(interfaceOptions, peers)
    }
}
