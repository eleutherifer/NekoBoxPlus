package io.nekohasekai.sagernet.utils

import android.content.Context
import io.nekohasekai.sagernet.database.DataStore
import org.json.JSONObject
import java.util.UUID
import kotlin.math.ceil

object CustomThemePreview {

    const val DURATION_MILLIS = 5_000L

    data class Pending(
        val id: String,
        val previousTheme: Int,
        val previousState: CustomTheme.State,
        val candidateState: CustomTheme.State,
        val expiresAt: Long,
    )

    fun begin(
        context: Context,
        candidate: CustomTheme.State,
        now: Long = System.currentTimeMillis(),
    ): Pending {
        check(pending() == null) { "A custom theme preview is already active" }
        return Pending(
            id = UUID.randomUUID().toString(),
            previousTheme = DataStore.appTheme,
            previousState = CustomTheme.capture(context),
            candidateState = candidate,
            expiresAt = now + DURATION_MILLIS,
        ).also { DataStore.customThemePendingPreview = encode(it).toString() }
    }

    fun reconcile(now: Long = System.currentTimeMillis()): Pending? {
        val pending = pending() ?: return null
        if (now >= pending.expiresAt) {
            restore(pending)
            return null
        }
        CustomTheme.save(pending.candidateState)
        DataStore.appTheme = CustomTheme.CUSTOM_THEME_ID
        return pending
    }

    fun pending(): Pending? {
        val value = DataStore.customThemePendingPreview
        if (value.isBlank()) return null
        return runCatching { decode(JSONObject(value)) }.getOrElse {
            DataStore.customThemePendingPreview = ""
            null
        }
    }

    fun confirm(id: String): Boolean {
        val pending = pending()?.takeIf { it.id == id } ?: return false
        CustomTheme.save(pending.candidateState)
        DataStore.appTheme = CustomTheme.CUSTOM_THEME_ID
        DataStore.customThemePendingPreview = ""
        return true
    }

    fun rollback(id: String): Boolean {
        val pending = pending()?.takeIf { it.id == id } ?: return false
        restore(pending)
        return true
    }

    fun remainingMillis(pending: Pending, now: Long = System.currentTimeMillis()): Long {
        return (pending.expiresAt - now).coerceIn(0L, DURATION_MILLIS)
    }

    fun remainingSeconds(pending: Pending, now: Long = System.currentTimeMillis()): Int {
        return ceil(remainingMillis(pending, now) / 1_000.0).toInt()
    }

    internal fun encode(pending: Pending): JSONObject {
        return JSONObject().apply {
            put("id", pending.id)
            put("previousTheme", pending.previousTheme)
            put("previousState", CustomTheme.stateToJson(pending.previousState))
            put("candidateState", CustomTheme.stateToJson(pending.candidateState))
            put("expiresAt", pending.expiresAt)
        }
    }

    internal fun decode(json: JSONObject): Pending {
        return Pending(
            id = json.getString("id"),
            previousTheme = json.getInt("previousTheme"),
            previousState = CustomTheme.stateFromJson(json.getJSONObject("previousState")),
            candidateState = CustomTheme.stateFromJson(json.getJSONObject("candidateState")),
            expiresAt = json.getLong("expiresAt"),
        )
    }

    private fun restore(pending: Pending) {
        CustomTheme.save(pending.previousState)
        DataStore.appTheme = pending.previousTheme
        DataStore.customThemePendingPreview = ""
    }
}
