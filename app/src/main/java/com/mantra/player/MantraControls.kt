package com.mantra.player

import android.content.Context

object MantraControls {
    /** Starts [mantraId] unless it is already the one playing. Returns false if the service could not start. */
    fun play(context: Context, mantraId: String): Boolean {
        val prefs = MantraPreferences(context)
        if (prefs.armed && prefs.mantraId == mantraId) {
            return true
        }
        prefs.positionMs = 0L
        prefs.mantraId = mantraId
        prefs.armed = true
        return try {
            MantraPlaybackService.select(context, mantraId)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop(context: Context) {
        MantraPreferences(context).clear()
        try {
            MantraPlaybackService.stop(context)
        } catch (_: Exception) {
            // Service already gone.
        }
    }

    fun isPlaying(context: Context, mantraId: String?): Boolean {
        val prefs = MantraPreferences(context)
        return prefs.armed && mantraId != null && prefs.mantraId == mantraId
    }
}
