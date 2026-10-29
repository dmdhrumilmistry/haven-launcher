package app.haven.launcher.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Settings live in a single JSON document inside SharedPreferences. One document means every
 * change is atomic, backups are a straight copy, and adding a setting never needs a migration.
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("haven_settings", Context.MODE_PRIVATE)
    private val lock = Any()
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<LauncherSettings> = _settings.asStateFlow()

    private fun load(): LauncherSettings {
        val raw = prefs.getString(KEY, null) ?: return LauncherSettings()
        return try {
            LauncherSettings.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            // A corrupted file must never leave someone without a working home screen.
            Log.w(TAG, "Settings unreadable, using defaults", e)
            LauncherSettings()
        }
    }

    fun update(transform: (LauncherSettings) -> LauncherSettings) {
        synchronized(lock) {
            val old = _settings.value
            val new = transform(old)
            if (new == old) return
            _settings.value = new
            prefs.edit().putString(KEY, new.toJson().toString()).apply()
        }
    }

    fun exportJson(): String = settings.value.toJson().toString(2)

    /** @return false if the text isn't a Haven backup. Existing settings are untouched in that case. */
    fun importJson(text: String): Boolean {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return false
        }
        if (!json.has("version")) return false
        // Widget ids only mean something on the device that created them, so keep the current ones.
        val imported = LauncherSettings.fromJson(json).copy(favoritesSeeded = true)
        update { imported.copy(widgets = it.widgets) }
        return true
    }

    /** Widgets stay: removing them silently would be surprising, and they're removed one tap away. */
    fun resetAll() = update { LauncherSettings(favoritesSeeded = true, widgets = it.widgets) }

    fun addWidget(widget: HomeWidget) = update { s ->
        if (s.widgets.any { it.id == widget.id }) s else s.copy(widgets = s.widgets + widget)
    }

    fun removeWidget(id: Int) = update { s -> s.copy(widgets = s.widgets.filterNot { it.id == id }) }

    fun resizeWidget(id: Int, rows: Int) = update { s ->
        s.copy(widgets = s.widgets.map { if (it.id == id) it.copy(rows = rows.coerceIn(1, 6)) else it })
    }

    fun moveWidget(id: Int, delta: Int) = update { s ->
        val list = s.widgets.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return@update s
        list.add(to, list.removeAt(from))
        s.copy(widgets = list)
    }

    // Convenience edits used across the UI.

    fun toggleFavorite(appKey: String) = update { s ->
        if (s.isFavorite(appKey)) s.copy(favorites = s.favorites.filterNot { it is HomeItem.App && it.appKey == appKey })
        else s.copy(favorites = s.favorites + HomeItem.App(appKey))
    }

    fun addHomeItem(item: HomeItem) = update { s ->
        if (s.favorites.any { it.id == item.id }) s else s.copy(favorites = s.favorites + item)
    }

    fun removeHomeItem(id: String) = update { s -> s.copy(favorites = s.favorites.filterNot { it.id == id }) }

    fun moveHomeItem(id: String, delta: Int) = update { s ->
        val list = s.favorites.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return@update s
        list.add(to, list.removeAt(from))
        s.copy(favorites = list)
    }

    /** Applies a new home order from drag and drop. Ids not in [orderedIds] keep their relative order at the end. */
    fun reorderHome(orderedIds: List<String>) = update { s ->
        val byId = s.favorites.associateBy { it.id }
        val ordered = orderedIds.mapNotNull { byId[it] }
        s.copy(favorites = ordered + s.favorites.filterNot { it.id in orderedIds })
    }

    fun setHidden(appKey: String, hidden: Boolean) = update { s ->
        if (hidden) {
            // A hidden app shouldn't keep a home-screen slot either; that would defeat the point.
            s.copy(
                hiddenApps = s.hiddenApps + appKey,
                favorites = s.favorites.filterNot { it is HomeItem.App && it.appKey == appKey },
            )
        } else {
            s.copy(hiddenApps = s.hiddenApps - appKey)
        }
    }

    fun setCustomLabel(appKey: String, label: String?) = update { s ->
        val clean = label?.trim()?.take(MAX_LABEL)
        if (clean.isNullOrEmpty()) s.copy(customLabels = s.customLabels - appKey)
        else s.copy(customLabels = s.customLabels + (appKey to clean))
    }

    companion object {
        private const val TAG = "HavenSettings"
        private const val KEY = "settings_json"
        const val MAX_LABEL = 40
    }
}
