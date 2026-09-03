package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.ktx.toStringPretty
import org.json.JSONObject

internal object ConfigJsonFormatter {
    fun format(text: String): String {
        return if (text.isBlank()) "" else JSONObject(text).toStringPretty()
    }
}
