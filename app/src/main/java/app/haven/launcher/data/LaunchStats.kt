package app.haven.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.math.pow

/**
 * Which apps get opened from Haven, kept only on this phone. Powers "Suggested apps" and the
 * "new app" dot. Uses a decaying score (frecency) so recent habits matter more than old ones.
 */
class LaunchStats(context: Context) {
    private val prefs = context.getSharedPreferences("haven_launch_stats", Context.MODE_PRIVATE)

    data class Entry(val score: Double, val last: Long)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()

    private fun load(): Map<String, Entry> = try {
        val json = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        json.keys().asSequence().associateWith { k ->
            val o = json.getJSONObject(k)
            Entry(o.optDouble("s", 0.0), o.optLong("t", 0L))
        }
    } catch (e: Exception) {
        emptyMap()
    }

    @Synchronized
    fun recordLaunch(appKey: String, now: Long = System.currentTimeMillis()) {
        val current = _entries.value
        val old = current[appKey]
        val updated = Entry(decayed(old, now) + 1.0, now)
        // Keep the store small: drop the weakest entries beyond the cap.
        val next = (current + (appKey to updated)).entries
            .sortedByDescending { decayed(it.value, now) }
            .take(MAX_ENTRIES)
            .associate { it.key to it.value }
        _entries.value = next
        val json = JSONObject()
        next.forEach { (k, e) -> json.put(k, JSONObject().put("s", e.score).put("t", e.last)) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    fun clear() {
        _entries.value = emptyMap()
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY = "stats"
        private const val MAX_ENTRIES = 200
        private const val HALF_LIFE_MS = 3.0 * 24 * 60 * 60 * 1000

        fun decayed(e: Entry?, now: Long): Double {
            if (e == null) return 0.0
            val age = (now - e.last).coerceAtLeast(0)
            return e.score * 0.5.pow(age / HALF_LIFE_MS)
        }
    }
}
