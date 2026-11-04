package com.mantra.player.ads

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mantra.player.BuildConfig
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.Executors

/** One of your own ads. Every scheduling field is optional; see [HouseAdDefaults]. */
data class HouseAd(
    val id: String,
    val live: Boolean,
    val type: String,
    val mediaUrl: String,
    val clickUrl: String?,
    val headline: String?,
    val cta: String?,
    val priority: Int,
    val durationSeconds: Int,
    val repeatDelaySeconds: Int,
    /** 0 = unlimited. Counted per device. */
    val maxPlays: Int,
    /** 0 = unlimited. Counted per device per calendar day. */
    val maxPlaysPerDay: Int,
    val startAtMs: Long?,
    val endAtMs: Long?,
    /** Date/time ranges the ad may run in; empty = no restriction beyond startAt/endAt. */
    val activePeriods: List<ActivePeriod>,
    /** Mantra ids this ad may show on; null = every mantra. */
    val mantras: Set<String>?,
    val excludeMantras: Set<String>,
    /** null = every day of the week. */
    val days: Set<DayOfWeek>?,
    /** Specific calendar dates only (e.g. festivals); null = any date. */
    val dates: Set<LocalDate>?,
    /** Allowed times of day; empty = all day. */
    val timeSlots: List<TimeSlot>
) {
    val isVideo: Boolean get() = type.equals("video", ignoreCase = true)
}

data class ActivePeriod(val startMs: Long?, val endMs: Long?) {
    fun contains(nowMs: Long): Boolean =
        (startMs == null || nowMs >= startMs) && (endMs == null || nowMs <= endMs)

    fun isOver(nowMs: Long): Boolean = endMs != null && nowMs > endMs
}

data class TimeSlot(val start: LocalTime, val end: LocalTime) {
    fun contains(time: LocalTime): Boolean =
        if (start <= end) {
            time >= start && time < end
        } else {
            // Crosses midnight, e.g. 22:00–02:00.
            time >= start || time < end
        }
}

data class HouseAdDefaults(
    val live: Boolean = true,
    val type: String = "image",
    val priority: Int = 0,
    val durationSeconds: Int = 30,
    val repeatDelaySeconds: Int = 60,
    val maxPlays: Int = 0,
    val maxPlaysPerDay: Int = 0,
    val mantras: Set<String>? = null,
    val excludeMantras: Set<String> = emptySet(),
    val days: Set<DayOfWeek>? = null,
    val timeSlots: List<TimeSlot> = emptyList()
)

/**
 * Remotely controlled ad slot settings. See ads/remote_ad_config.example.json for the format.
 * mode = "admob" (default), "house" (your own scheduled ads), or "off".
 */
data class RemoteAdConfig(
    val mode: Mode,
    val houseAds: List<HouseAd>,
    val fallbackToAdmob: Boolean,
    val refreshSeconds: Int
) {
    enum class Mode { ADMOB, HOUSE, OFF }

    companion object {
        val DEFAULT = RemoteAdConfig(Mode.ADMOB, emptyList(), fallbackToAdmob = true, refreshSeconds = 60)

        fun parse(json: String): RemoteAdConfig? = try {
            val root = JSONObject(json)
            val mode = when (root.optString("mode", "admob").lowercase()) {
                "house" -> Mode.HOUSE
                "off" -> Mode.OFF
                else -> Mode.ADMOB
            }
            val defaults = parseDefaults(root.optJSONObject("defaults"))
            val ads = mutableListOf<HouseAd>()
            root.optJSONArray("ads")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { parseAd(it, defaults)?.let(ads::add) }
                }
            }
            // Older single-ad format.
            if (ads.isEmpty()) {
                root.optJSONObject("house")?.let { parseAd(it, defaults)?.let(ads::add) }
            }
            RemoteAdConfig(
                mode = mode,
                houseAds = ads,
                fallbackToAdmob = root.optBoolean("fallbackToAdmob", true),
                refreshSeconds = root.optInt("refreshSeconds", 60)
            )
        } catch (_: JSONException) {
            null
        }

        private fun parseDefaults(json: JSONObject?): HouseAdDefaults {
            val base = HouseAdDefaults()
            if (json == null) return base
            return HouseAdDefaults(
                live = json.status(base.live),
                type = json.text("type") ?: base.type,
                priority = json.int("priority") ?: base.priority,
                durationSeconds = json.int("durationSeconds") ?: base.durationSeconds,
                repeatDelaySeconds = json.int("repeatDelaySeconds") ?: base.repeatDelaySeconds,
                maxPlays = json.int("maxPlays") ?: base.maxPlays,
                maxPlaysPerDay = json.int("maxPlaysPerDay") ?: base.maxPlaysPerDay,
                mantras = json.idSet("mantras") ?: base.mantras,
                excludeMantras = json.idSet("excludeMantras") ?: base.excludeMantras,
                days = json.daySet() ?: base.days,
                timeSlots = json.timeSlots() ?: base.timeSlots
            )
        }

        private fun parseAd(json: JSONObject, d: HouseAdDefaults): HouseAd? {
            val mediaUrl = json.text("mediaUrl") ?: return null
            return HouseAd(
                id = json.text("id") ?: mediaUrl,
                live = json.status(d.live),
                type = json.text("type") ?: d.type,
                mediaUrl = mediaUrl,
                clickUrl = json.text("clickUrl"),
                headline = json.text("headline"),
                cta = json.text("cta"),
                priority = json.int("priority") ?: d.priority,
                durationSeconds = (json.int("durationSeconds") ?: d.durationSeconds).coerceAtLeast(5),
                repeatDelaySeconds = (json.int("repeatDelaySeconds") ?: d.repeatDelaySeconds).coerceAtLeast(0),
                maxPlays = (json.int("maxPlays") ?: d.maxPlays).coerceAtLeast(0),
                maxPlaysPerDay = (json.int("maxPlaysPerDay") ?: d.maxPlaysPerDay).coerceAtLeast(0),
                startAtMs = json.text("startAt")?.let { parseDateTime(it, endOfDay = false) },
                endAtMs = json.text("endAt")?.let { parseDateTime(it, endOfDay = true) },
                activePeriods = json.activePeriods(),
                mantras = json.idSet("mantras") ?: d.mantras,
                excludeMantras = json.idSet("excludeMantras") ?: d.excludeMantras,
                days = json.daySet() ?: d.days,
                dates = json.stringList("dates")
                    ?.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?.toSet()
                    ?.ifEmpty { null },
                timeSlots = json.timeSlots() ?: d.timeSlots
            )
        }

        private fun JSONObject.text(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key).trim().ifEmpty { null }

        private fun JSONObject.int(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null

        private fun JSONObject.stringList(key: String): List<String>? {
            val array = optJSONArray(key) ?: return text(key)?.let { listOf(it) }
            return (0 until array.length()).mapNotNull { array.optString(it).trim().ifEmpty { null } }
        }

        /** "live" plays, "skip" never plays, missing uses the default. */
        private fun JSONObject.status(default: Boolean): Boolean =
            when (text("status")?.lowercase()) {
                "skip" -> false
                "live" -> true
                else -> default
            }

        /** Mantra ids; an empty list or ["all"] means every mantra. */
        private fun JSONObject.idSet(key: String): Set<String>? {
            val ids = stringList(key)?.map { it.lowercase() } ?: return null
            return if (ids.isEmpty() || "all" in ids) null else ids.toSet()
        }

        /** "days": ["MON", "sat", "Sunday"]. */
        private fun JSONObject.daySet(): Set<DayOfWeek>? =
            stringList("days")
                ?.mapNotNull { name -> DayOfWeek.values().firstOrNull { it.name.startsWith(name.take(3).uppercase()) } }
                ?.toSet()
                ?.ifEmpty { null }

        /** "activePeriods": [{"start": "2026-10-20", "end": "2026-10-25 21:00"}, ...] */
        private fun JSONObject.activePeriods(): List<ActivePeriod> {
            val array = optJSONArray("activePeriods") ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val period = array.optJSONObject(i) ?: return@mapNotNull null
                val start = period.text("start")?.let { parseDateTime(it, endOfDay = false) }
                val end = period.text("end")?.let { parseDateTime(it, endOfDay = true) }
                if (start == null && end == null) null else ActivePeriod(start, end)
            }
        }

        /**
         * "timeSlots": [{"start": "05:00", "end": "08:00"}, ...]; the older
         * "dailyStart"/"dailyEnd" pair is read as a single slot.
         */
        private fun JSONObject.timeSlots(): List<TimeSlot>? {
            optJSONArray("timeSlots")?.let { array ->
                return (0 until array.length()).mapNotNull { i ->
                    val slot = array.optJSONObject(i) ?: return@mapNotNull null
                    TimeSlot(
                        slot.text("start")?.let(::parseTime) ?: LocalTime.MIN,
                        slot.text("end")?.let(::parseTime) ?: LocalTime.MAX
                    )
                }
            }
            val start = text("dailyStart")?.let(::parseTime)
            val end = text("dailyEnd")?.let(::parseTime)
            if (start == null && end == null) return null
            return listOf(TimeSlot(start ?: LocalTime.MIN, end ?: LocalTime.MAX))
        }

        /** Accepts "2026-10-06T18:00:00+05:30", "2026-10-06 18:00" (phone time zone) or "2026-10-06". */
        private fun parseDateTime(value: String, endOfDay: Boolean): Long? {
            val zone = ZoneId.systemDefault()
            return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                .recoverCatching { LocalDateTime.parse(value.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }
                .recoverCatching {
                    val date = LocalDate.parse(value)
                    if (endOfDay) {
                        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    } else {
                        date.atStartOfDay(zone).toInstant().toEpochMilli()
                    }
                }
                .getOrNull()
        }

        private fun parseTime(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()
    }
}

object RemoteAdConfigRepository {
    private const val PREFS = "remote_ad_config"
    private const val KEY_JSON = "json"
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun current(context: Context): RemoteAdConfig {
        val json = prefs(context).getString(KEY_JSON, null) ?: return RemoteAdConfig.DEFAULT
        return RemoteAdConfig.parse(json) ?: RemoteAdConfig.DEFAULT
    }

    /** Downloads the latest config in the background; [onUpdated] runs on the main thread on success. */
    fun refresh(context: Context, onUpdated: ((RemoteAdConfig) -> Unit)? = null) {
        val url = BuildConfig.REMOTE_AD_CONFIG_URL
        if (url.isBlank()) return
        val app = context.applicationContext
        executor.execute {
            val body = download(url) ?: return@execute
            val config = RemoteAdConfig.parse(body) ?: return@execute
            prefs(app).edit().putString(KEY_JSON, body).apply()
            if (onUpdated != null) {
                main.post { onUpdated(config) }
            }
        }
    }

    private fun download(url: String): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
