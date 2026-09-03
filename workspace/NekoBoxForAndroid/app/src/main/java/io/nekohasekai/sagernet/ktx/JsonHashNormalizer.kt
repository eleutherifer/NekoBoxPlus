package io.nekohasekai.sagernet.ktx

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object JsonHashNormalizer {

    @JvmStatic
    fun normalizeJsonStringOrRaw(raw: String?): String {
        val text = raw ?: return ""
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        return runCatching { canonicalize(JSONTokener(trimmed).nextValue()) }
            .getOrElse { text }
    }

    @JvmStatic
    fun normalizeJsonObjectOrRaw(raw: JSONObject?): JSONObject {
        val normalized = normalizeJsonStringOrRaw(raw?.toString())
        return runCatching { JSONObject(normalized) }.getOrElse { raw ?: JSONObject() }
    }

    private fun canonicalize(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> canonicalizeObject(value)
            is JSONArray -> canonicalizeArray(value)
            is String -> JSONObject.quote(value)
            is Number -> JSONObject.numberToString(value)
            is Boolean -> value.toString()
            else -> value.toString()
        }
    }

    private fun canonicalizeObject(value: JSONObject): String {
        val keys = mutableListOf<String>()
        value.keys().forEachRemaining { keys += it }
        keys.sort()
        return buildString {
            append('{')
            keys.forEachIndexed { index, key ->
                if (index > 0) append(',')
                append(JSONObject.quote(key))
                append(':')
                append(canonicalize(value.opt(key)))
            }
            append('}')
        }
    }

    private fun canonicalizeArray(value: JSONArray): String {
        return buildString {
            append('[')
            for (index in 0 until value.length()) {
                if (index > 0) append(',')
                append(canonicalize(value.opt(index)))
            }
            append(']')
        }
    }
}
