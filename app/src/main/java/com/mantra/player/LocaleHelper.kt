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

    fun languageTag(context: Context): String =
        normalize(prefs(context).getString(KEY_TAG, SYSTEM) ?: SYSTEM)

    fun applyStoredLocale(context: Context) {
        applyLanguageTag(languageTag(context))
    }

    fun setLanguageTag(context: Context, tag: String) {
        val normalized = normalize(tag)
        prefs(context).edit().putString(KEY_TAG, normalized).commit()
        applyLanguageTag(normalized)
    }

    fun displayName(context: Context, tag: String): String {
        return when (normalize(tag)) {
            ENGLISH -> context.getString(R.string.language_english)
            HINDI -> context.getString(R.string.language_hindi)
            MARATHI -> context.getString(R.string.language_marathi)
            else -> context.getString(R.string.language_system)
        }
    }

    /**
     * Exact language tags matching resource folders:
     * values-hi → "hi", values-mr → "mr", values → "en".
     */
    private fun applyLanguageTag(tag: String) {
        val locales = when (normalize(tag)) {
            ENGLISH -> LocaleListCompat.forLanguageTags(ENGLISH)
            HINDI -> LocaleListCompat.forLanguageTags(HINDI)
            MARATHI -> LocaleListCompat.forLanguageTags(MARATHI)
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun normalize(tag: String): String {
        val t = tag.trim().lowercase()
        return when {
            t.isEmpty() || t == SYSTEM -> SYSTEM
            t == ENGLISH || t.startsWith("en-") || t.startsWith("en_") -> ENGLISH
            t == HINDI || t.startsWith("hi-") || t.startsWith("hi_") -> HINDI
            t == MARATHI || t.startsWith("mr-") || t.startsWith("mr_") -> MARATHI
            // Legacy mistaken region tags still map correctly
            t == "hi-in" -> HINDI
            t == "mr-in" -> MARATHI
            else -> SYSTEM
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
