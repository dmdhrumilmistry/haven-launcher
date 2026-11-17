package app.haven.launcher.focus

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A recurring pause: these apps are paused on these days between these times.
 * [endMinute] earlier than [startMinute] means it runs overnight (e.g. 22:00 to 07:00), and the
 * days refer to the day it starts.
 */
data class PauseSchedule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** ISO days, 1 = Monday ... 7 = Sunday. */
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 17 * 60,
    val apps: Set<String> = emptySet(),
    val enabled: Boolean = true,
) {
    /** When the current pause ends, or null if the schedule isn't active at [now]. */
    fun activeUntil(now: ZonedDateTime): ZonedDateTime? {
        if (!enabled || apps.isEmpty() || days.isEmpty() || startMinute == endMinute) return null
        val midnight = now.truncatedTo(ChronoUnit.DAYS)
        val minute = now.hour * 60 + now.minute
        val today = now.dayOfWeek.value
        if (startMinute < endMinute) {
            return if (today in days && minute in startMinute until endMinute) midnight.plusMinutes(endMinute.toLong()) else null
        }
        // Overnight: either it started this evening, or it started yesterday and runs until this morning.
        if (today in days && minute >= startMinute) return midnight.plusDays(1).plusMinutes(endMinute.toLong())
        val yesterday = now.minusDays(1).dayOfWeek.value
        if (yesterday in days && minute < endMinute) return midnight.plusMinutes(endMinute.toLong())
        return null
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("days", JSONArray(days.sorted()))
        .put("start", startMinute)
        .put("end", endMinute)
        .put("apps", JSONArray(apps.sorted()))
        .put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject): PauseSchedule? {
            val id = o.optString("id").ifBlank { return null }
            fun ints(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
            fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
            return PauseSchedule(
                id = id,
                name = o.optString("name").take(40),
                days = ints("days").filter { it in 1..7 }.toSet(),
                startMinute = o.optInt("start", 9 * 60).coerceIn(0, 24 * 60 - 1),
                endMinute = o.optInt("end", 17 * 60).coerceIn(0, 24 * 60 - 1),
                apps = strings("apps").toSet(),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

/** Why an app is paused right now, and until when. */
sealed interface PauseState {
    /** Epoch millis; [Long.MAX_VALUE] means until unpaused. */
    val until: Long

    data class Manual(override val until: Long) : PauseState
    data class Scheduled(val scheduleId: String, val scheduleName: String, override val until: Long) : PauseState
}

object Pauses {
    const val FOREVER = Long.MAX_VALUE

    /** Every app paused at [nowMillis], from manual pauses and active schedules. */
    fun compute(manual: Map<String, Long>, schedules: List<PauseSchedule>, nowMillis: Long): Map<String, PauseState> {
        val result = HashMap<String, PauseState>()
        manual.forEach { (key, until) -> if (until > nowMillis) result[key] = PauseState.Manual(until) }
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneId.systemDefault())
        for (schedule in schedules) {
            val end = schedule.activeUntil(now)?.toInstant()?.toEpochMilli() ?: continue
            for (key in schedule.apps) {
                // A manual pause wins: it's the more deliberate, more recent choice.
                if (key !in result) result[key] = PauseState.Scheduled(schedule.id, schedule.name, end)
            }
        }
        return result
    }

    /** End of today (local midnight), for "Pause until tomorrow". */
    fun endOfToday(nowMillis: Long): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneId.systemDefault())
            .truncatedTo(ChronoUnit.DAYS).plusDays(1).toInstant().toEpochMilli()
}

/**
 * Holding back notifications from paused apps (full build, with notification access).
 *
 * Android doesn't let a home screen stop another app from posting, but a notification listener
 * can snooze what it posts. Snoozed notifications leave the shade and come back on their own when
 * the snooze ends, so they're timed to end with the pause and nothing is lost.
 */
object NotificationHold {
    /**
     * A pause "until I unpause" holds notifications a day at a time: Android has no way to bring a
     * snoozed notification back early, so an open-ended snooze could hide them for good.
     */
    const val MAX_HOLD_MS = 24L * 60 * 60 * 1000

    /** Brought back a moment after the pause ends, so they don't land in its last second. */
    private const val GRACE_MS = 3_000L

    /** Notification key ("package#userSerial", as for badges) of an app key ("pkg/cls#serial"). */
    fun packageKey(appKey: String): String? {
        val hash = appKey.lastIndexOf('#')
        val slash = appKey.indexOf('/')
        if (hash <= 0 || slash <= 0 || slash > hash) return null
        val serial = appKey.substring(hash + 1).toLongOrNull() ?: return null
        return "${appKey.substring(0, slash)}#$serial"
    }

    /**
     * Packages paused right now, with when their pause ends. Pausing any of a package's launcher
     * entries holds its notifications; if several are paused, the latest end wins.
     */
    fun pausedPackages(pauses: Map<String, PauseState>): Map<String, Long> {
        val result = HashMap<String, Long>()
        for ((appKey, state) in pauses) {
            val key = packageKey(appKey) ?: continue
            result[key] = maxOf(result[key] ?: 0L, state.until)
        }
        return result
    }

    /** How long to hold a notification posted at [now] for a pause ending at [until]; null if not worth it. */
    fun holdDuration(until: Long, now: Long): Long? {
        if (until == Pauses.FOREVER) return MAX_HOLD_MS
        val left = until - now
        if (left <= GRACE_MS) return null
        return (left + GRACE_MS).coerceAtMost(MAX_HOLD_MS)
    }
}

/** What the home screen says about paused apps: how many, and when the next pause ends. */
data class PauseSummary(val count: Int, val nextEnd: Long) {
    companion object {
        fun of(pauses: Map<String, PauseState>): PauseSummary? {
            if (pauses.isEmpty()) return null
            return PauseSummary(pauses.size, pauses.values.minOf { it.until })
        }
    }
}
