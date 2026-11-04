package com.mantra.player

import android.content.Context
import android.content.SharedPreferences

class MantraPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var mantraId: String?
        get() = prefs.getString(KEY_ID, null)
        set(value) {
            prefs.edit().putString(KEY_ID, value).apply()
        }

    var armed: Boolean
        get() = prefs.getBoolean(KEY_ARMED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ARMED, value).apply()
        }

    var positionMs: Long
        get() = prefs.getLong(KEY_POSITION, 0L)
        set(value) {
            prefs.edit().putLong(KEY_POSITION, value).apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS = "mantra_playback"
        private const val KEY_ID = "mantra_id"
        private const val KEY_ARMED = "armed"
        private const val KEY_POSITION = "position_ms"
    }
}
