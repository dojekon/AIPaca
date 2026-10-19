package com.aipaca.android.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.aipaca.android.data.settings.LANGUAGE_ENGLISH
import com.aipaca.android.data.settings.LANGUAGE_RUSSIAN

fun localeListFor(language: String): LocaleListCompat = when (language) {
    LANGUAGE_RUSSIAN -> LocaleListCompat.forLanguageTags("ru")
    LANGUAGE_ENGLISH -> LocaleListCompat.forLanguageTags("en")
    else -> LocaleListCompat.getEmptyLocaleList()
}

fun applyLanguage(language: String) {
    val desired = localeListFor(language)
    if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != desired.toLanguageTags()) {
        AppCompatDelegate.setApplicationLocales(desired)
    }
}
