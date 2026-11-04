package com.mantra.player.ads

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Picks which house ad to show now and tracks per-device play counts and cooldowns. */
class HouseAdScheduler(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("house_ad_stats", Context.MODE_PRIVATE)

    /** Highest priority eligible ad; ties go to the one shown least recently, then list order. */
    fun pick(ads: List<HouseAd>, mantraId: String?, nowMs: Long = System.currentTimeMillis()): HouseAd? =
        ads.withIndex()
            .filter { isEligible(it.value, mantraId, nowMs) }
            .sortedWith(
                compareByDescending<IndexedValue<HouseAd>> { it.value.priority }
                    .thenBy { lastEndMs(it.value.id) }
                    .thenBy { it.index }
            )
            .firstOrNull()?.value

    fun recordPlay(ad: HouseAd, nowMs: Long = System.currentTimeMillis()) {
        val today = today(nowMs)
        val dayPlays = if (prefs.getString("${ad.id}.day", null) == today) prefs.getInt("${ad.id}.dayPlays", 0) else 0
        prefs.edit()
            .putInt("${ad.id}.plays", prefs.getInt("${ad.id}.plays", 0) + 1)
            .putString("${ad.id}.day", today)
            .putInt("${ad.id}.dayPlays", dayPlays + 1)
            .putLong("${ad.id}.lastEnd", nowMs + ad.durationSeconds * 1000L)
            .apply()
    }

    /**
     * Seconds until some house ad for [mantraId] may become eligible again, or null if none of
     * them can ever play again (all skipped, expired, used up or for other mantras).
     */
    fun secondsUntilNextCheck(ads: List<HouseAd>, mantraId: String?, nowMs: Long = System.currentTimeMillis()): Int? {
        val candidates = ads.filter { ad ->
            ad.live &&
                matchesMantra(ad, mantraId) &&
                (ad.endAtMs == null || nowMs <= ad.endAtMs) &&
                (ad.activePeriods.isEmpty() || ad.activePeriods.any { !it.isOver(nowMs) }) &&
                (ad.maxPlays == 0 || plays(ad.id) < ad.maxPlays)
        }
        if (candidates.isEmpty()) return null
        val soonest = candidates.minOf { ad ->
            val cooldownEnd = lastEndMs(ad.id) + ad.repeatDelaySeconds * 1000L
            val start = ad.startAtMs ?: 0L
            maxOf(cooldownEnd, start) - nowMs
        }
        // Periods, days, dates, time slots and daily caps are re-checked at most once a minute.
        return (soonest / 1000L).toInt().coerceIn(MIN_CHECK_SECONDS, MAX_CHECK_SECONDS)
    }

    private fun isEligible(ad: HouseAd, mantraId: String?, nowMs: Long): Boolean {
        if (!ad.live) return false
        if (!matchesMantra(ad, mantraId)) return false
        if (ad.startAtMs != null && nowMs < ad.startAtMs) return false
        if (ad.endAtMs != null && nowMs > ad.endAtMs) return false
        if (ad.activePeriods.isNotEmpty() && ad.activePeriods.none { it.contains(nowMs) }) return false
        if (!matchesCalendar(ad, nowMs)) return false
        if (ad.maxPlays > 0 && plays(ad.id) >= ad.maxPlays) return false
        if (ad.maxPlaysPerDay > 0 && playsToday(ad.id, nowMs) >= ad.maxPlaysPerDay) return false
        val lastEnd = lastEndMs(ad.id)
        return lastEnd == 0L || nowMs >= lastEnd + ad.repeatDelaySeconds * 1000L
    }

    fun matchesMantra(ad: HouseAd, mantraId: String?): Boolean {
        val id = mantraId?.lowercase()
        if (id != null && id in ad.excludeMantras) return false
        return ad.mantras == null || (id != null && id in ad.mantras)
    }

    private fun matchesCalendar(ad: HouseAd, nowMs: Long): Boolean {
        val now = local(nowMs)
        if (ad.days != null && now.dayOfWeek !in ad.days) return false
        if (ad.dates != null && now.toLocalDate() !in ad.dates) return false
        return ad.timeSlots.isEmpty() || ad.timeSlots.any { it.contains(now.toLocalTime()) }
    }

    private fun plays(id: String) = prefs.getInt("$id.plays", 0)

    private fun playsToday(id: String, nowMs: Long) =
        if (prefs.getString("$id.day", null) == today(nowMs)) prefs.getInt("$id.dayPlays", 0) else 0

    private fun lastEndMs(id: String) = prefs.getLong("$id.lastEnd", 0L)

    private fun local(nowMs: Long): ZonedDateTime = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())

    private fun today(nowMs: Long): String = local(nowMs).toLocalDate().toString()

    companion object {
        private const val MIN_CHECK_SECONDS = 5
        private const val MAX_CHECK_SECONDS = 60
    }
}
