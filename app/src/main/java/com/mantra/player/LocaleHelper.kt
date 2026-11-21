package com.mantra.player

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LocaleHelper {
    private const val PREFS = "app_locale"
    private const val KEY_TAG = "language_tag"

    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val HINDI = "hi"
    const val MARATHI = "mr"

    fun languageTag(context: Context): String {
        return prefs(context).getString(KEY_TAG, SYSTEM) ?: SYSTEM
    }

    fun applyStoredLocale(context: Context) {
        applyLanguageTag(languageTag(context))
    }

    fun setLanguageTag(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_TAG, tag).apply()
        applyLanguageTag(tag)
    }

    fun displayName(context: Context, tag: String): String {
        return when (tag) {
            ENGLISH -> context.getString(R.string.language_english)
            HINDI -> context.getString(R.string.language_hindi)
            MARATHI -> context.getString(R.string.language_marathi)
            else -> context.getString(R.string.language_system)
        }
    }

    private fun applyLanguageTag(tag: String) {
        val locales = if (tag == SYSTEM || tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
