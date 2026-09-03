package io.nekohasekai.sagernet.utils

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.nekohasekai.sagernet.database.DataStore

object AppLocale {

    private fun localeList(tag: String?): LocaleListCompat {
        return if (tag.isNullOrEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
    }

    fun apply(tag: String? = DataStore.appLanguage) {
        AppCompatDelegate.setApplicationLocales(localeList(tag))
    }
}
