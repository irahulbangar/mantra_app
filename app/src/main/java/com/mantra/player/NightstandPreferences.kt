package com.mantra.player

import android.content.Context

class NightstandPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** Sleep timer duration in minutes; 0 means off. */
    var sleepMinutes: Int
        get() = prefs.getInt(KEY_SLEEP_MINUTES, 0)
        set(value) {
            prefs.edit().putInt(KEY_SLEEP_MINUTES, value.coerceAtLeast(0)).apply()
        }

    var nightMode: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NIGHT_MODE, value).apply()
        }

    companion object {
        private const val PREFS = "nightstand_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SLEEP_MINUTES = "sleep_minutes"
        private const val KEY_NIGHT_MODE = "night_mode"

        val SLEEP_OPTIONS_MINUTES = listOf(0, 15, 30, 45, 60, 90)
    }
}
