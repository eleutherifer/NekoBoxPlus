package moe.matsuri.nb4a.utils

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object HwidGenerator {
    fun generate(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val input = androidId + "NekoBoxPlus"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
