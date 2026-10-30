package app.haven.launcher.system

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Unread notification counts per app, keyed by "package#userSerial". Only counts, never content. */
object NotificationBadges {
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    fun key(packageName: String, userSerial: Long) = "$packageName#$userSerial"

    internal fun update(counts: Map<String, Int>) {
        _counts.value = counts
    }

    /** Whether the user granted Haven notification access in system settings. */
    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun listenerComponent(context: Context) = ComponentName(context, NotificationBadgeService::class.java)
}

/**
 * Counts notifications per app for the icon badges. Declared only in the "full" build's manifest.
 * It reads the package, profile and count of each notification; it never reads, stores or sends
 * their text.
 */
class NotificationBadgeService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationBadges.update(emptyMap())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) = refresh()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = refresh()

    private fun refresh() {
        val active = try {
            activeNotifications
        } catch (e: Exception) {
            // Not connected (yet), or access just revoked.
            Log.w(TAG, "Couldn't read notifications", e)
            null
        } ?: return
        val ranking = try {
            currentRanking
        } catch (e: Exception) {
            null
        }
        val userManager = getSystemService(UserManager::class.java)
        val reusable = Ranking()
        val counts = HashMap<String, Int>()
        for (sbn in active) {
            val n = sbn.notification
            // Same rules as stock launchers: no group summaries (they'd double count), nothing
            // ongoing (music, navigation, downloads), and respect apps/channels with badges off.
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            if (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) continue
            if (ranking != null && ranking.getRanking(sbn.key, reusable) && !reusable.canShowBadge()) continue
            val serial = userManager.getSerialNumberForUser(sbn.user)
            if (serial < 0) continue
            val key = NotificationBadges.key(sbn.packageName, serial)
            counts[key] = (counts[key] ?: 0) + if (n.number > 0) n.number else 1
        }
        NotificationBadges.update(counts)
    }

    companion object {
        private const val TAG = "HavenBadges"
    }
}
