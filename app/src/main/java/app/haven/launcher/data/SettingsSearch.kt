package app.haven.launcher.data

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.haven.launcher.R

/** A phone settings screen that search can open, e.g. "wifi" -> Wi-Fi settings. */
data class SettingResult(val id: String, val label: String, val action: String)

/**
 * Phone settings found by name or by everyday words for them. Only screens this phone can
 * actually open are offered, so nothing in the list leads nowhere.
 */
object SettingsSearch {
    /** Opens Haven's own settings rather than a system screen. */
    const val HAVEN = "haven"

    internal class Entry(
        val id: String,
        val labelRes: Int,
        /** Tried in order; the first one the phone can open wins. */
        val actions: List<String>,
        /** Extra English words people type for it. The translated label always matches too. */
        val keywords: List<String>,
    )

    private val entries = listOf(
        Entry("wifi", R.string.qs_wifi, listOf(Settings.ACTION_WIFI_SETTINGS), listOf("wifi", "wlan", "wireless", "internet", "hotspot")),
        Entry("bluetooth", R.string.qs_bluetooth, listOf(Settings.ACTION_BLUETOOTH_SETTINGS), listOf("bluetooth", "bt", "pair", "headphones", "earbuds")),
        Entry("network", R.string.qs_network, listOf(Settings.ACTION_WIRELESS_SETTINGS), listOf("network", "internet", "sim", "mobile", "vpn", "hotspot", "tethering")),
        Entry("data", R.string.qs_mobile_data, listOf("android.settings.DATA_USAGE_SETTINGS"), listOf("data", "usage", "cellular", "mobile")),
        Entry("airplane", R.string.qs_airplane, listOf(Settings.ACTION_AIRPLANE_MODE_SETTINGS), listOf("airplane", "aeroplane", "flight", "plane")),
        Entry("display", R.string.qs_display, listOf(Settings.ACTION_DISPLAY_SETTINGS), listOf("display", "brightness", "screen", "dark", "font", "timeout")),
        Entry("sound", R.string.qs_sound, listOf(Settings.ACTION_SOUND_SETTINGS), listOf("sound", "volume", "ringtone", "vibration", "vibrate", "silent")),
        Entry(
            "dnd",
            R.string.qs_dnd,
            listOf("android.settings.ZEN_MODE_SETTINGS", "android.settings.ZEN_MODE_PRIORITY_SETTINGS"),
            listOf("dnd", "disturb", "quiet", "focus", "modes", "silent"),
        ),
        Entry(
            "battery",
            R.string.qs_battery,
            listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS),
            listOf("battery", "power", "charge", "charging", "saver"),
        ),
        Entry("location", R.string.qs_location, listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS), listOf("location", "gps")),
        Entry(
            "apps",
            R.string.qs_apps,
            listOf(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS),
            listOf("apps", "applications", "permissions"),
        ),
        Entry("default_apps", R.string.qs_default_apps, listOf(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), listOf("default", "browser", "launcher", "home")),
        Entry(
            "notifications",
            R.string.qs_notifications,
            listOf("android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "android.settings.NOTIFICATION_SETTINGS"),
            listOf("notifications", "alerts"),
        ),
        Entry("storage", R.string.qs_storage, listOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), listOf("storage", "space", "memory", "free")),
        Entry("security", R.string.qs_security, listOf(Settings.ACTION_SECURITY_SETTINGS), listOf("security", "lock", "fingerprint", "face", "password", "pin")),
        Entry("accessibility", R.string.qs_accessibility, listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS), listOf("accessibility", "talkback", "a11y")),
        Entry("date", R.string.qs_date_time, listOf(Settings.ACTION_DATE_SETTINGS), listOf("date", "time", "timezone", "clock")),
        Entry("language", R.string.qs_language, listOf(Settings.ACTION_LOCALE_SETTINGS), listOf("language", "locale", "keyboard")),
        Entry("nfc", R.string.qs_nfc, listOf(Settings.ACTION_NFC_SETTINGS), listOf("nfc", "tap", "pay", "contactless")),
        Entry("cast", R.string.qs_cast, listOf(Settings.ACTION_CAST_SETTINGS), listOf("cast", "chromecast", "mirror", "projection")),
        Entry("developer", R.string.qs_developer, listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), listOf("developer", "adb", "debugging")),
        Entry("all", R.string.qs_all_settings, listOf(Settings.ACTION_SETTINGS), listOf("settings", "preferences", "system")),
        Entry(HAVEN, R.string.qs_haven, listOf(HAVEN), listOf("haven", "launcher", "home", "wallpaper", "widgets", "pause")),
    )

    /** A searchable entry with the action this phone supports. Labels are looked up per query. */
    class Available internal constructor(internal val entry: Entry, val action: String)

    /** Works out once which screens this phone can open. Call off the main thread. */
    fun available(context: Context): List<Available> {
        val devOptions = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }
        return entries.mapNotNull { e ->
            if (e.id == "developer" && !devOptions) return@mapNotNull null
            val action = e.actions.firstOrNull { it == HAVEN || canOpen(context, it) } ?: return@mapNotNull null
            Available(e, action)
        }
    }

    private fun canOpen(context: Context, action: String): Boolean = try {
        Intent(action).resolveActivity(context.packageManager) != null
    } catch (e: Exception) {
        false
    }

    /** Best matches for [query], at most [limit]. Short queries match nothing: too noisy. */
    fun search(context: Context, available: List<Available>, query: String, limit: Int = 3): List<SettingResult> {
        val q = Search.normalize(query)
        if (q.replace(" ", "").length < 2) return emptyList()
        return available.asSequence()
            .map { a ->
                val label = context.getString(a.entry.labelRes)
                a to score(label, a.entry.keywords, q)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (a, _) -> SettingResult(a.entry.id, context.getString(a.entry.labelRes), a.action) }
            .toList()
    }

    /** Name matches beat keyword matches; fuzzy matching is left to apps, where it's expected. */
    fun score(label: String, keywords: List<String>, normalizedQuery: String): Int {
        val qc = normalizedQuery.replace(" ", "")
        val key = Search.keyFor(label, label, "")
        val byName = Search.score(key, normalizedQuery).takeIf { it >= 500 } ?: 0
        val byKeyword = if (keywords.any { it.startsWith(qc) || (qc.length >= 4 && qc.startsWith(it) && it.length >= 3) }) 450 else 0
        return maxOf(byName, byKeyword)
    }

    fun intentFor(result: SettingResult): Intent = Intent(result.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
