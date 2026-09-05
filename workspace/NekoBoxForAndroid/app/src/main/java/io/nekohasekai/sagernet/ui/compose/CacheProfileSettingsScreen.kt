package io.nekohasekai.sagernet.ui.compose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.input.KeyboardType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

internal sealed interface CachePreferenceItem

internal data class CachePreferenceCategory(@param:StringRes val title: Int) : CachePreferenceItem

internal data class CacheTextPreference(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    val key: String,
    @param:StringRes val fixedSummary: Int? = null,
    val showValue: Boolean = true,
    val secret: Boolean = false,
    val number: Boolean = false,
    val maxLength: Int = Int.MAX_VALUE,
    val decimal: Boolean = false,
) : CachePreferenceItem

internal data class CacheSwitchPreference(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    val key: String,
    val dependency: String? = null,
    @param:StringRes val summary: Int? = null,
) : CachePreferenceItem

internal data class CacheListPreference(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    val key: String,
    val entries: Int,
    val entryValues: Int,
) : CachePreferenceItem

internal data class CacheActionPreference(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    val key: String,
    @param:StringRes val summary: Int? = null,
) : CachePreferenceItem

@Composable
internal fun CacheProfileSettingsScreen(
    preferences: List<CachePreferenceItem>,
    includeDialOptions: Boolean = true,
    includeTlsOptions: Boolean = false,
    includeQuicOptions: Boolean = false,
    stateRevision: Int = 0,
    onAction: (String) -> Unit = {},
    onValueChanged: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val store = DataStore.profileCacheStore
    val notSet = stringResource(R.string.not_set)
    val textPreferences = remember(preferences) { preferences.filterIsInstance<CacheTextPreference>() }
    val switchPreferences = remember(preferences) { preferences.filterIsInstance<CacheSwitchPreference>() }
    val listPreferences = remember(preferences) { preferences.filterIsInstance<CacheListPreference>() }
    val textValues = remember(textPreferences, stateRevision) {
        mutableStateMapOf<String, String>().apply {
            textPreferences.forEach { put(it.key, store.getString(it.key).orEmpty()) }
        }
    }
    val switchValues = remember(switchPreferences, stateRevision) {
        mutableStateMapOf<String, Boolean>().apply {
            switchPreferences.forEach { put(it.key, store.getBoolean(it.key, false)) }
        }
    }
    val listValues = remember(listPreferences, stateRevision) {
        mutableStateMapOf<String, String>().apply {
            listPreferences.forEach { put(it.key, store.getString(it.key).orEmpty()) }
        }
    }

    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        items(preferences, key = {
            when (it) {
                is CachePreferenceCategory -> "category:${it.title}"
                is CacheTextPreference -> "text:${it.key}"
                is CacheSwitchPreference -> "switch:${it.key}"
                is CacheListPreference -> "list:${it.key}"
                is CacheActionPreference -> "action:${it.key}"
            }
        }) { preference ->
            when (preference) {
                is CachePreferenceCategory -> ProfileCategory(preference.title)
                is CacheTextPreference -> {
                    val value = textValues[preference.key].orEmpty()
                    val summary = when {
                        preference.fixedSummary != null -> stringResource(preference.fixedSummary)
                        !preference.showValue -> null
                        preference.secret && value.isNotBlank() -> "\u2022".repeat(value.length)
                        else -> value.ifBlank { notSet }
                    }
                    val title = stringResource(preference.title)
                    ProfileActionRow(preference.icon, preference.title, summary,
                        dynamicSummary = preference.fixedSummary == null) {
                        context.showComposeTextInputDialog(
                            title = title,
                            initialValue = value,
                            keyboardType = when {
                                preference.secret -> KeyboardType.Password
                                preference.decimal -> KeyboardType.Decimal
                                preference.number -> KeyboardType.Number
                                else -> KeyboardType.Text
                            },
                            maxLength = preference.maxLength,
                            password = preference.secret,
                            onPositive = {
                                textValues[preference.key] = it
                                store.putString(preference.key, it)
                                onValueChanged(preference.key, it)
                            },
                        )
                    }
                }
                is CacheSwitchPreference -> {
                    val checked = switchValues[preference.key] == true
                    val enabled = preference.dependency?.let { switchValues[it] == true } ?: true
                    ProfileSwitchRow(
                        preference.icon,
                        preference.title,
                        checked,
                        summary = preference.summary?.let { stringResource(it) },
                        enabled = enabled,
                        dynamicSummary = false,
                    ) {
                        switchValues[preference.key] = it
                        store.putBoolean(preference.key, it)
                        onValueChanged(preference.key, it.toString())
                    }
                }
                is CacheListPreference -> {
                    val entries = stringArrayResource(preference.entries).toList()
                    val entryValues = stringArrayResource(preference.entryValues).toList()
                    val value = listValues[preference.key].orEmpty()
                    val selected = entryValues.indexOf(value).coerceAtLeast(0)
                    val title = stringResource(preference.title)
                    val cancel = stringResource(android.R.string.cancel)
                    ProfileActionRow(
                        preference.icon,
                        preference.title,
                        entries.getOrElse(selected) { value.ifBlank { notSet } },
                    ) {
                        context.showComposeSingleChoiceDialog(
                            title = title,
                            items = entries,
                            selectedIndex = selected,
                            negativeButton = cancel,
                            onItemSelected = {
                                val newValue = entryValues[it]
                                listValues[preference.key] = newValue
                                store.putString(preference.key, newValue)
                                onValueChanged(preference.key, newValue)
                            },
                        )
                    }
                }
                is CacheActionPreference -> ProfileActionRow(
                    preference.icon,
                    preference.title,
                    preference.summary?.let { stringResource(it) },
                    dynamicSummary = false,
                ) { onAction(preference.key) }
            }
        }
        if (includeDialOptions) item { SharedDialOptions() }
        if (includeTlsOptions) item { SharedTlsOptions() }
        if (includeQuicOptions) item { SharedQuicOptions() }
    }
}
