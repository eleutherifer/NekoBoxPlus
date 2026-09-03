package moe.matsuri.nb4a.utils

import android.content.Context
import android.provider.Settings
import io.nekohasekai.sagernet.SpoofApp
import java.security.MessageDigest
import java.util.Locale

object HwidGenerator {
    fun generate(context: Context): String {
        return generate(context, SpoofApp.NONE)
    }

    fun generate(context: Context, spoofApp: Int): String {
        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        return generate(androidId, spoofApp)
    }

    internal fun generate(androidId: String, spoofApp: Int): String {
        val input = androidId + "NekoBoxPlus"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return when (spoofApp) {
            SpoofApp.V2RAY_TUN -> hex.take(16).uppercase(Locale.ROOT)
            SpoofApp.INCY -> {
                val value = hex.take(32).uppercase(Locale.ROOT)
                "${value.take(8)}-${value.substring(8, 12)}-${value.substring(12, 16)}-" +
                    "${value.substring(16, 20)}-${value.substring(20, 32)}"
            }
            else -> hex.take(16)
        }
    }
}
