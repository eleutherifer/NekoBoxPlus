package io.nekohasekai.sagernet.backup.throne

import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Throne's Qt_6_0 little-endian QDataStream, format 2. The only payload is a closed SQLite database. */
internal object ThrBackup {
    fun write(output: OutputStream, database: File, parts: ThrSelection, version: String) {
        val meta = JSONObject().apply {
            put("android", JSONObject().put("app_version", version).put("icons", false))
            put("app_version", version)
            put("backup_version", 2)
            put("created_at", SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", Locale.US).format(Date()))
            put("platform", "android")
            put("parts", JSONObject().put("profiles", parts.profiles).put("routes", parts.routes)
                .put("settings", parts.settings).put("otp", false).put("icons", false))
        }
        require(database.length() <= 0xFFFFFFFDL)
        // Do not close the caller's stream (SAF and FileProvider own its lifetime).
        val out = output.buffered(64 * 1024)
        out.write("THRN".toByteArray(Charsets.US_ASCII))
        u32(out, 2)
        qstring(out, meta.toString())
        u32(out, 1)
        qstring(out, "database")
        u32(out, database.length())
        database.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    private fun qstring(out: OutputStream, value: String) {
        u32(out, value.length * 2L)
        for (char in value) {
            out.write(char.code and 255)
            out.write(char.code ushr 8)
        }
    }

    private fun u32(out: OutputStream, value: Long) {
        repeat(4) { out.write((value ushr (it * 8)).toInt() and 255) }
    }
}

data class ThrSelection(val profiles: Boolean, val routes: Boolean, val settings: Boolean) {
    fun any(): Boolean = profiles || routes || settings
}

/** Only display names, identifiers and resource keys belong here, never raw JSON or credentials. */
data class ThrOmission(
    val kind: String,
    val id: Long?,
    val reason: String,
    val key: String? = null,
    val entityName: String? = null,
)

data class ThrExportReport(
    val profiles: Int,
    val routes: Int,
    val settings: Int,
    val omissions: List<ThrOmission>,
)
