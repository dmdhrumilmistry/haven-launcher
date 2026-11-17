package app.haven.launcher.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NotificationHoldTest {
    private val hour = 60 * 60_000L

    @Test
    fun packageKeyComesFromTheAppKey() {
        assertEquals("com.whatsapp#0", NotificationHold.packageKey("com.whatsapp/com.whatsapp.Main#0"))
        assertEquals("org.example#10", NotificationHold.packageKey("org.example/.Launcher#10"))
        assertNull(NotificationHold.packageKey("garbage"))
        assertNull(NotificationHold.packageKey("com.x/.A#notanumber"))
        assertNull(NotificationHold.packageKey("#0"))
    }

    @Test
    fun pausingAnyEntryOfAPackageHoldsItsNotificationsUntilTheLatestEnd() {
        val paused = NotificationHold.pausedPackages(
            mapOf(
                "com.chat/.Main#0" to PauseState.Manual(1_000L),
                "com.chat/.Other#0" to PauseState.Scheduled("s", "Work", 5_000L),
                "com.chat/.Main#10" to PauseState.Manual(2_000L),
            ),
        )
        assertEquals(mapOf("com.chat#0" to 5_000L, "com.chat#10" to 2_000L), paused)
    }

    @Test
    fun holdLastsUntilThePauseEnds() {
        val now = 1_000_000L
        val d = NotificationHold.holdDuration(now + hour, now)!!
        assertTrue("ends with the pause, plus a moment", d in hour..hour + 10_000)
    }

    @Test
    fun openEndedPausesHoldADayAtATime() {
        assertEquals(NotificationHold.MAX_HOLD_MS, NotificationHold.holdDuration(Pauses.FOREVER, 0L))
        assertEquals(NotificationHold.MAX_HOLD_MS, NotificationHold.holdDuration(10 * 24 * hour, 0L))
    }

    @Test
    fun aPauseAboutToEndHoldsNothing() {
        assertNull(NotificationHold.holdDuration(1_000L, 0L))
        assertNull(NotificationHold.holdDuration(0L, 5_000L))
    }

    @Test
    fun summaryCountsAppsAndFindsTheNextEnd() {
        assertNull(PauseSummary.of(emptyMap()))
        val s = PauseSummary.of(
            mapOf("a/.A#0" to PauseState.Manual(Pauses.FOREVER), "b/.B#0" to PauseState.Manual(3_000L)),
        )!!
        assertEquals(2, s.count)
        assertEquals(3_000L, s.nextEnd)
    }

    @Test
    fun untilATimeMeansTodayOrTomorrow() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2026, 3, 10, 14, 30, 0, 0, zone)
        val nowMs = now.toInstant().toEpochMilli()
        // 18:00 is still ahead today.
        assertEquals(now.withHour(18).withMinute(0).toInstant().toEpochMilli(), nextOccurrence(18 * 60, nowMs))
        // 09:00 has passed: tomorrow morning.
        assertEquals(now.plusDays(1).withHour(9).withMinute(0).toInstant().toEpochMilli(), nextOccurrence(9 * 60, nowMs))
        // Exactly now: tomorrow, never a pause that's already over.
        assertEquals(now.plusDays(1).toInstant().toEpochMilli(), nextOccurrence(14 * 60 + 30, nowMs))
    }
}
