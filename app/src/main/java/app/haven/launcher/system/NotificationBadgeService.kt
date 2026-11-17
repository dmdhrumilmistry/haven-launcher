package app.haven.launcher.system

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import app.haven.launcher.focus.NotificationHold
import app.haven.launcher.focus.Pauses
import app.haven.launcher.haven
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Unread notification counts per app, keyed by "package#userSerial". Only counts, never content. */
object NotificationBadges {
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    /** Notifications held back because their app is paused, by the same key. */
    private val _held = MutableStateFlow<Map<String, HeldNotifications>>(emptyMap())
    val held: StateFlow<Map<String, HeldNotifications>> = _held.asStateFlow()

    fun key(packageName: String, userSerial: Long) = "$packageName#$userSerial"

    internal fun update(counts: Map<String, Int>) {
        _counts.value = counts
    }

    internal fun updateHeld(held: Map<String, HeldNotifications>) {
        _held.value = held
    }

    /** Whether the user granted Haven notification access in system settings. */
    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun listenerComponent(context: Context) = ComponentName(context, NotificationBadgeService::class.java)
}

/** How many of an app's notifications are held, and when the last of them comes back. */
data class HeldNotifications(val count: Int, val returnsAt: Long)

/**
 * Declared only in the "full" build's manifest. Two jobs, both without reading any text:
 *  - counts notifications per app for the icon badges;
 *  - snoozes notifications from paused apps so they come back when the pause ends.
 * It looks at the package, profile, flags and category of each notification, never its content.
 *
 * The class keeps its original name: renaming it would silently revoke the access people granted.
 */
class NotificationBadgeService : NotificationListenerService() {

    private var scope: CoroutineScope? = null

    /** Paused packages ("package#serial") and when their pause ends. Main thread only. */
    private var paused: Map<String, Long> = emptyMap()

    /** Notifications we snoozed: notification key -> what to report. Main thread only. */
    private val held = HashMap<String, Held>()

    private data class Held(val packageKey: String, val returnsAt: Long, val countable: Boolean)

    override fun onListenerConnected() {
        super.onListenerConnected()
        refresh()
        watchPauses()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        stopWatching()
        NotificationBadges.update(emptyMap())
    }

    override fun onDestroy() {
        stopWatching()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn != null) {
            // A held notification coming back (its snooze ended) is no longer held...
            if (held.remove(sbn.key) != null) publishHeld()
            // ...unless its app is still paused, e.g. a pause "until I unpause".
            hold(sbn)
        }
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) = refresh()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = refresh()

    // ---- Holding notifications from paused apps ----

    private fun watchPauses() {
        stopWatching()
        val settings = applicationContext.haven.settings.settings
        // Ticks at each minute boundary, like the home screen, so scheduled pauses start on time.
        val clock = flow {
            while (true) {
                val now = System.currentTimeMillis()
                emit(now)
                delay(60_000 - now % 60_000 + 50)
            }
        }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        newScope.launch {
            combine(settings, clock) { s, now ->
                if (!s.holdPausedNotifications) emptyMap()
                else NotificationHold.pausedPackages(Pauses.compute(s.pausedApps, s.pauseSchedules, now))
            }.distinctUntilChanged().collect { next ->
                val newlyPaused = next.keys - paused.keys
                paused = next
                // Whatever was already in the shade when the pause began goes away too.
                if (newlyPaused.isNotEmpty()) activeOrNull()?.forEach { if (packageKey(it) in newlyPaused) hold(it) }
                pruneHeld()
            }
        }
    }

    private fun stopWatching() {
        scope?.cancel()
        scope = null
        paused = emptyMap()
        held.clear()
        NotificationBadges.updateHeld(emptyMap())
    }

    private fun hold(sbn: StatusBarNotification) {
        if (paused.isEmpty() || !canHold(sbn)) return
        val key = packageKey(sbn) ?: return
        val until = paused[key] ?: return
        val now = System.currentTimeMillis()
        val duration = NotificationHold.holdDuration(until, now) ?: return
        try {
            snoozeNotification(sbn.key, duration)
        } catch (e: Exception) {
            // Not connected any more, or the system refused (e.g. too many snoozed).
            Log.w(TAG, "Couldn't hold a notification", e)
            return
        }
        val summary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        held[sbn.key] = Held(key, now + duration, countable = !summary)
        publishHeld()
    }

    /**
     * Never held: Haven's own, anything ongoing or that can't be swiped away (calls in progress,
     * music, navigation, downloads), and calls and alarms, which must always get through.
     */
    private fun canHold(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val n = sbn.notification
        if (sbn.isOngoing || !sbn.isClearable) return false
        if (n.flags and (Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_NO_CLEAR) != 0) return false
        return n.category !in ALWAYS_THROUGH
    }

    private fun pruneHeld() {
        val now = System.currentTimeMillis()
        if (held.values.removeAll { it.returnsAt <= now }) publishHeld()
    }

    private fun publishHeld() {
        NotificationBadges.updateHeld(
            held.values.filter { it.countable }
                .groupBy { it.packageKey }
                .mapValues { (_, list) -> HeldNotifications(list.size, list.maxOf { it.returnsAt }) },
        )
    }

    // ---- Badges ----

    private fun activeOrNull(): Array<StatusBarNotification>? = try {
        activeNotifications
    } catch (e: Exception) {
        // Not connected (yet), or access just revoked.
        Log.w(TAG, "Couldn't read notifications", e)
        null
    }

    private fun packageKey(sbn: StatusBarNotification): String? {
        val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(sbn.user)
        return if (serial < 0) null else NotificationBadges.key(sbn.packageName, serial)
    }

    private fun refresh() {
        val active = activeOrNull() ?: return
        val ranking = try {
            currentRanking
        } catch (e: Exception) {
            null
        }
        val reusable = Ranking()
        val counts = HashMap<String, Int>()
        for (sbn in active) {
            val n = sbn.notification
            // Same rules as stock launchers: no group summaries (they'd double count), nothing
            // ongoing (music, navigation, downloads), and respect apps/channels with badges off.
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            if (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) continue
            if (ranking != null && ranking.getRanking(sbn.key, reusable) && !reusable.canShowBadge()) continue
            val key = packageKey(sbn) ?: continue
            counts[key] = (counts[key] ?: 0) + if (n.number > 0) n.number else 1
        }
        NotificationBadges.update(counts)
    }

    companion object {
        private const val TAG = "HavenBadges"
        private val ALWAYS_THROUGH = setOf(
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_ALARM,
            "navigation", // Notification.CATEGORY_NAVIGATION, a constant from API 28
            Notification.CATEGORY_TRANSPORT,
        )
    }
}
