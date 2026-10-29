package app.haven.launcher.data

import android.content.Context
import android.util.Log
import app.haven.launcher.focus.PauseSchedule
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

    fun pauseApp(appKey: String, until: Long) = update { s ->
        val now = System.currentTimeMillis()
        // Drop expired pauses while we're here so the store doesn't grow forever.
        s.copy(pausedApps = s.pausedApps.filterValues { it > now } + (appKey to until))
    }

    fun unpauseApp(appKey: String) = update { s -> s.copy(pausedApps = s.pausedApps - appKey) }

    fun upsertSchedule(schedule: PauseSchedule) = update { s ->
        val exists = s.pauseSchedules.any { it.id == schedule.id }
        s.copy(
            pauseSchedules = if (exists) s.pauseSchedules.map { if (it.id == schedule.id) schedule else it }
            else s.pauseSchedules + schedule,
        )
    }

    fun deleteSchedule(id: String) = update { s -> s.copy(pauseSchedules = s.pauseSchedules.filterNot { it.id == id }) }

    // Convenience edits used across the UI.

    fun toggleFavorite(appKey: String) = update { s ->
        if (s.isFavorite(appKey)) s.copy(favorites = s.favorites.withoutApp(appKey))
        else s.copy(favorites = s.favorites + HomeItem.App(appKey))
    }

    // ---- Home folders ----

    /**
     * Drag-and-drop on the home screen: dropping an app onto another app makes a folder in the
     * target's place; dropping it onto a folder adds it there.
     */
    fun mergeOnHome(draggedId: String, targetId: String, newFolderName: String) = update { s ->
        val dragged = s.favorites.firstOrNull { it.id == draggedId } as? HomeItem.App ?: return@update s
        val target = s.favorites.firstOrNull { it.id == targetId } ?: return@update s
        val merged: HomeItem = when (target) {
            is HomeItem.App -> HomeItem.Folder(newId(), newFolderName, listOf(target.appKey, dragged.appKey))
            is HomeItem.Folder -> target.copy(apps = (target.apps + dragged.appKey).distinct())
            else -> return@update s
        }
        s.copy(favorites = s.favorites.filterNot { it.id == draggedId }.map { if (it.id == targetId) merged else it })
    }

    /** Takes an app out of a home folder and puts it right after the folder. A folder of one dissolves. */
    fun removeFromHomeFolder(folderId: String, appKey: String) = update { s ->
        val list = s.favorites.toMutableList()
        val index = list.indexOfFirst { it is HomeItem.Folder && it.folderId == folderId }
        if (index < 0) return@update s
        val folder = list[index] as HomeItem.Folder
        val remaining = folder.apps - appKey
        when (remaining.size) {
            0 -> list.removeAt(index)
            1 -> list[index] = HomeItem.App(remaining.first())
            else -> list[index] = folder.copy(apps = remaining)
        }
        list.add((index + 1).coerceAtMost(list.size), HomeItem.App(appKey))
        s.copy(favorites = list.distinctBy { it.id })
    }

    fun renameHomeFolder(folderId: String, name: String) = update { s ->
        s.copy(favorites = s.favorites.map { if (it is HomeItem.Folder && it.folderId == folderId) it.copy(name = name.trim().take(40)) else it })
    }

    // ---- Drawer folders ----

    /** Drag-and-drop in the drawer: onto an app makes a folder, onto a folder adds to it. */
    fun mergeInDrawer(draggedKey: String, targetAppKey: String?, targetFolderId: String?, newFolderName: String) = update { s ->
        if (draggedKey == targetAppKey) return@update s
        // An app lives in at most one drawer folder.
        val cleaned = s.drawerFolders.map { it.copy(apps = it.apps - draggedKey) }
        val folders = when {
            targetFolderId != null -> cleaned.map { if (it.id == targetFolderId) it.copy(apps = it.apps + draggedKey) else it }
            targetAppKey != null -> {
                val existing = cleaned.firstOrNull { targetAppKey in it.apps }
                if (existing != null) cleaned.map { if (it.id == existing.id) it.copy(apps = it.apps + draggedKey) else it }
                else cleaned + DrawerFolder(newId(), newFolderName, listOf(targetAppKey, draggedKey))
            }
            else -> return@update s
        }
        s.copy(drawerFolders = folders.filter { it.apps.size >= 2 })
    }

    fun removeFromDrawerFolder(folderId: String, appKey: String) = update { s ->
        s.copy(drawerFolders = s.drawerFolders.map { if (it.id == folderId) it.copy(apps = it.apps - appKey) else it }.filter { it.apps.size >= 2 })
    }

    fun renameDrawerFolder(folderId: String, name: String) = update { s ->
        s.copy(drawerFolders = s.drawerFolders.map { if (it.id == folderId) it.copy(name = name.trim().take(40)) else it })
    }

    // ---- Categories ----

    fun createCategory(name: String, apps: Set<String> = emptySet()): String {
        val id = newId()
        update { s -> s.copy(categories = s.categories + AppCategory(id, name.trim().take(40), apps)) }
        return id
    }

    /** Adds suggested categories, merging into existing ones with the same name. */
    fun addCategories(suggested: List<Pair<String, Set<String>>>) = update { s ->
        var list = s.categories
        for ((name, apps) in suggested) {
            val existing = list.firstOrNull { it.name.equals(name, ignoreCase = true) }
            list = if (existing != null) list.map { if (it.id == existing.id) it.copy(apps = it.apps + apps) else it }
            else list + AppCategory(newId(), name, apps)
        }
        s.copy(categories = list)
    }

    fun setAppInCategory(categoryId: String, appKey: String, inCategory: Boolean) = update { s ->
        s.copy(categories = s.categories.map {
            if (it.id != categoryId) it else it.copy(apps = if (inCategory) it.apps + appKey else it.apps - appKey)
        })
    }

    fun renameCategory(id: String, name: String) = update { s ->
        s.copy(categories = s.categories.map { if (it.id == id) it.copy(name = name.trim().take(40)) else it })
    }

    fun deleteCategory(id: String) = update { s -> s.copy(categories = s.categories.filterNot { it.id == id }) }

    fun moveCategory(id: String, delta: Int) = update { s ->
        val list = s.categories.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return@update s
        list.add(to, list.removeAt(from))
        s.copy(categories = list)
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
                favorites = s.favorites.withoutApp(appKey),
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

    /** Removes an app from the home screen, including from inside home folders. */
    private fun List<HomeItem>.withoutApp(appKey: String): List<HomeItem> = mapNotNull { item ->
        when {
            item is HomeItem.App && item.appKey == appKey -> null
            item is HomeItem.Folder && appKey in item.apps -> {
                val rest = item.apps - appKey
                when (rest.size) {
                    0 -> null
                    1 -> HomeItem.App(rest.first())
                    else -> item.copy(apps = rest)
                }
            }
            else -> item
        }
    }.distinctBy { it.id }

    companion object {
        private const val TAG = "HavenSettings"
        private const val KEY = "settings_json"
        const val MAX_LABEL = 40
    }
}
