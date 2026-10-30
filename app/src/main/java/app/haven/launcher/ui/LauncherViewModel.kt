package app.haven.launcher.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.icu.text.AlphabeticIndex
import android.provider.MediaStore
import android.view.View
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.haven.launcher.R
import app.haven.launcher.data.AppItem
import app.haven.launcher.data.AppsSnapshot
import app.haven.launcher.data.AppCategory
import app.haven.launcher.data.DrawerFolder
import app.haven.launcher.data.DrawerSort
import app.haven.launcher.focus.PauseState
import app.haven.launcher.focus.Pauses
import app.haven.launcher.data.Calculator
import app.haven.launcher.data.LaunchStats
import app.haven.launcher.data.HomeItem
import app.haven.launcher.data.InstalledApp
import app.haven.launcher.data.LauncherSettings
import app.haven.launcher.data.Profile
import app.haven.launcher.data.ProfileType
import app.haven.launcher.data.Search
import app.haven.launcher.haven
import app.haven.launcher.system.LauncherAccessibilityService
import app.haven.launcher.system.SystemActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/** A page in the app drawer: a profile, the hidden apps, or one of the user's categories. */
sealed interface DrawerTab {
    /**
     * Stable string identity. Lazy layouts (the swipeable pager) save their keys in a Bundle, which
     * only accepts simple types: using the tab object itself as a key crashes on Android.
     */
    val key: String

    data object Personal : DrawerTab {
        override val key get() = "personal"
    }

    data object Work : DrawerTab {
        override val key get() = "work"
    }

    data object Private : DrawerTab {
        override val key get() = "private"
    }

    data object Hidden : DrawerTab {
        override val key get() = "hidden"
    }

    data class Category(val id: String) : DrawerTab {
        override val key get() = "category:$id"
    }

    companion object {
        val PERSONAL: DrawerTab = Personal
        val WORK: DrawerTab = Work
        val PRIVATE: DrawerTab = Private
        val HIDDEN: DrawerTab = Hidden
    }
}

/** One cell in the drawer: an app, or a folder of apps. */
sealed interface DrawerEntry {
    val key: String
    val label: String
    val section: String

    data class App(val item: AppItem) : DrawerEntry {
        override val key get() = item.key
        override val label get() = item.label
        override val section get() = item.section
    }

    data class Folder(
        val folder: DrawerFolder,
        val apps: List<AppItem>,
        override val label: String,
        override val section: String,
    ) : DrawerEntry {
        override val key get() = "folder:${folder.id}"
    }
}

sealed interface HomeEntry {
    val id: String

    data class App(val item: AppItem) : HomeEntry {
        override val id get() = "app:${item.key}"
    }

    data class Shortcut(val item: HomeItem.Shortcut, val info: ShortcutInfo) : HomeEntry {
        override val id get() = item.id
    }

    data class Folder(val item: HomeItem.Folder, val apps: List<AppItem>, val label: String) : HomeEntry {
        override val id get() = item.id
    }
}

data class DrawerModel(
    val tabs: List<DrawerTab> = listOf(DrawerTab.PERSONAL),
    val entriesByTab: Map<DrawerTab, List<DrawerEntry>> = emptyMap(),
    val categoryNames: Map<String, String> = emptyMap(),
    /** Alphabetical order: the A-Z scroller makes sense. */
    val alphabetical: Boolean = true,
    val profileByTab: Map<DrawerTab, Profile> = emptyMap(),
    /** Work profile when it's merged into the main list (tabs turned off), for the paused banner. */
    val mergedWorkProfile: Profile? = null,
)

data class SearchState(
    val query: String,
    val results: List<AppItem>,
    /** Answer if the query is arithmetic, e.g. "12*7" -> "84". */
    val calculation: String? = null,
    /** Hidden apps are searchable but locked: offer to unlock instead of silently leaving them out. */
    val canUnlockHidden: Boolean = false,
)

/** One-off UI requests that need a dialog. */
sealed interface LauncherPrompt {
    data class TurnOnWorkApps(val profile: Profile) : LauncherPrompt
    data object EnableLockService : LauncherPrompt
    data object NeedDefaultLauncher : LauncherPrompt
    data class AppPaused(val item: AppItem, val state: PauseState) : LauncherPrompt
}

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val settingsRepo = application.haven.settings
    private val appsRepo = application.haven.apps
    private val launchStats = application.haven.launchStats

    val settings: StateFlow<LauncherSettings> = settingsRepo.settings
    val snapshot: StateFlow<AppsSnapshot> = appsRepo.snapshot

    /** All apps with the user's names applied, sorted the way people expect in their language. */
    val allApps: StateFlow<List<AppItem>> =
        combine(
            snapshot,
            settings.map { it.customLabels to it.hiddenApps }.distinctUntilChanged(),
        ) { snap, (labels, hidden) -> buildItems(snap, labels, hidden) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val drawer: StateFlow<DrawerModel> =
        combine(
            allApps,
            snapshot,
            settings.map {
                DrawerPrefs(it.separateProfileTabs, it.showHiddenTab, it.drawerFolders, it.categories, it.drawerSort)
            }.distinctUntilChanged(),
            launchStats.entries,
        ) { apps, snap, prefs, stats ->
            buildDrawer(apps, snap, prefs, stats)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, DrawerModel())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val stats = application.haven.launchStats

    /** Ticks at each minute boundary (and on resume) so pauses start and end on time. */
    private val clock = MutableStateFlow(System.currentTimeMillis())

    /** Apps paused right now, by manual pause or schedule. */
    val pauses: StateFlow<Map<String, PauseState>> =
        combine(clock, settings.map { it.pausedApps to it.pauseSchedules }.distinctUntilChanged()) { now, (manual, schedules) ->
            Pauses.compute(manual, schedules, now)
        }.distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Unlocked with the device's screen lock; relocks whenever the launcher leaves the screen. */
    private val _hiddenUnlocked = MutableStateFlow(false)
    val hiddenUnlocked: StateFlow<Boolean> = _hiddenUnlocked.asStateFlow()

    fun setHiddenUnlocked(unlocked: Boolean) {
        _hiddenUnlocked.value = unlocked
    }

    /** Results tagged with the query that produced them, so nothing acts on stale results. */
    val search: StateFlow<SearchState> =
        combine(
            _query,
            allApps,
            settings.map { it.searchHiddenApps to it.lockHiddenApps }.distinctUntilChanged(),
            _hiddenUnlocked,
        ) { q, apps, (searchHidden, locked), unlocked ->
            val includeHidden = searchHidden && (!locked || unlocked)
            SearchState(
                query = q,
                calculation = if (q.isBlank()) null else Calculator.evaluate(q),
                canUnlockHidden = q.isNotBlank() && searchHidden && locked && !unlocked,
                results = if (q.isBlank()) emptyList() else apps.asSequence()
                .filter { (includeHidden || !it.isHidden) && it.app.profile.isVisible }
                .map { it to Search.score(it.search, q) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second } // Stable: equal scores keep alphabetical order.
                .take(MAX_RESULTS)
                .map { it.first }
                .toList(),
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, SearchState("", emptyList()))

    /** Most-used apps (by recent habit), shown atop the drawer. Empty until there's some history. */
    val suggestions: StateFlow<List<AppItem>> =
        combine(allApps, stats.entries, settings.map { it.showSuggestions }.distinctUntilChanged(), pauses) { apps, entries, show, paused ->
            if (!show || entries.isEmpty()) return@combine emptyList()
            val now = System.currentTimeMillis()
            apps.asSequence()
                .filter { !it.isHidden && !it.isPaused && it.key !in paused && it.app.profile.isVisible && it.profileType != ProfileType.PRIVATE }
                .map { it to LaunchStats.decayed(entries[it.key], now) }
                .filter { it.second >= MIN_SUGGESTION_SCORE }
                .sortedByDescending { it.second }
                .take(MAX_SUGGESTIONS)
                .map { it.first }
                .toList()
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Apps installed in the last few days that haven't been opened yet get a small dot. */
    val newApps: StateFlow<Set<String>> =
        combine(allApps, stats.entries) { apps, entries ->
            val cutoff = System.currentTimeMillis() - NEW_APP_WINDOW_MS
            apps.filter { it.app.installedAt > cutoff && it.key !in entries }.map { it.key }.toSet()
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val homeEntries: StateFlow<List<HomeEntry>> =
        combine(allApps, snapshot, settings.map { it.favorites }.distinctUntilChanged()) { apps, snap, favorites ->
            val byKey = apps.associateBy { it.key }
            favorites.mapNotNull { fav ->
                when (fav) {
                    // Missing = uninstalled, or its profile is gone. Keep the entry stored in case it
                    // comes back (e.g. SD card remounted) but don't show a dead slot.
                    is HomeItem.App -> byKey[fav.appKey]?.takeIf { it.app.profile.isVisible }?.let { HomeEntry.App(it) }
                    is HomeItem.Shortcut -> snap.pinnedShortcuts[fav.id]?.let { HomeEntry.Shortcut(fav, it) }
                    is HomeItem.Folder -> fav.apps
                        .mapNotNull { k -> byKey[k]?.takeIf { !it.isHidden && it.app.profile.isVisible } }
                        .takeIf { it.isNotEmpty() }
                        ?.let { HomeEntry.Folder(fav, it, fav.name.ifBlank { app.getString(R.string.folder_default) }) }
                }
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isDefaultLauncher = MutableStateFlow(true)
    val isDefaultLauncher: StateFlow<Boolean> = _isDefaultLauncher.asStateFlow()

    private val _prompt = MutableStateFlow<LauncherPrompt?>(null)
    val prompt: StateFlow<LauncherPrompt?> = _prompt.asStateFlow()

    private val _homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires when the user presses Home while already on the launcher: reset everything. */
    val homePressed: SharedFlow<Unit> = _homePressed.asSharedFlow()

    init {
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                delay(60_000 - now % 60_000 + 50)
                clock.value = System.currentTimeMillis()
            }
        }
        viewModelScope.launch { snapshot.collect { if (it.loaded) seedFavoritesOnce() } }
    }

    // ---- Lifecycle hooks from the activity ----

    fun onResume() {
        clock.value = System.currentTimeMillis()
        val isDefault = SystemActions.isDefaultLauncher(app)
        // Becoming (or losing) the home app changes what we may do: shortcuts, pinned items, profiles.
        if (isDefault != _isDefaultLauncher.value || isDefault != snapshot.value.canUseShortcuts) appsRepo.reload()
        _isDefaultLauncher.value = isDefault
    }

    fun onHomePressed() {
        _homePressed.tryEmit(Unit)
    }

    // ---- Search ----

    fun setQuery(value: String) {
        _query.value = value.take(MAX_QUERY)
    }

    fun clearQuery() = setQuery("")

    // ---- Launching ----

    fun launch(item: AppItem, view: View? = null, bounds: Rect? = null): Boolean {
        if (item.isPaused) {
            _prompt.value = LauncherPrompt.TurnOnWorkApps(item.app.profile)
            return false
        }
        pauses.value[item.key]?.let { state ->
            _prompt.value = LauncherPrompt.AppPaused(item, state)
            return false
        }
        return launchNow(item, view, bounds)
    }

    /** Skips the pause check: for "Open anyway". */
    fun launchNow(item: AppItem, view: View? = null, bounds: Rect? = null): Boolean {
        val ok = SystemActions.launchApp(app, item.app, view, bounds)
        if (ok) stats.recordLaunch(item.key) else appsRepo.reload()
        return ok
    }

    fun launch(entry: HomeEntry.Shortcut, view: View? = null, bounds: Rect? = null): Boolean =
        SystemActions.launchShortcut(app, entry.info, view, bounds)

    fun launchShortcut(info: ShortcutInfo, view: View? = null, bounds: Rect? = null): Boolean =
        SystemActions.launchShortcut(app, info, view, bounds)

    suspend fun shortcutsFor(item: AppItem): List<ShortcutInfo> =
        withContext(Dispatchers.IO) { appsRepo.shortcutsFor(item.app) }

    // ---- Customisation ----

    fun toggleFavorite(item: AppItem) = settingsRepo.toggleFavorite(item.key)

    fun setHidden(item: AppItem, hidden: Boolean) = settingsRepo.setHidden(item.key, hidden)

    fun rename(item: AppItem, newLabel: String?) {
        val clean = newLabel?.trim()
        settingsRepo.setCustomLabel(item.key, clean?.takeIf { it.isNotEmpty() && it != item.app.systemLabel })
    }

    fun removeHomeEntry(entry: HomeEntry) {
        settingsRepo.removeHomeItem(entry.id)
        if (entry is HomeEntry.Shortcut) unpin(entry.item)
    }

    fun pinShortcutToHome(info: ShortcutInfo) {
        val serial = appsRepo.serialFor(info.userHandle)
        val label = (info.shortLabel ?: info.longLabel ?: "").toString()
        val item = HomeItem.Shortcut(info.`package`, info.id, serial, label)
        // Add to the system's pinned set first (keeping existing pins of that package).
        val existing = snapshot.value.pinnedShortcuts.values
            .filter { it.`package` == info.`package` && it.userHandle == info.userHandle }
            .map { it.id }
        appsRepo.syncPinnedShortcuts(info.`package`, info.userHandle, (existing + info.id).distinct())
        settingsRepo.addHomeItem(item)
    }

    private fun unpin(item: HomeItem.Shortcut) {
        val user = appsRepo.userForSerial(item.userSerial) ?: return
        val stillOnHome = settings.value.favorites
            .filterIsInstance<HomeItem.Shortcut>()
            .filter { it.packageName == item.packageName && it.userSerial == item.userSerial && it.id != item.id }
            .map { it.shortcutId }
        appsRepo.syncPinnedShortcuts(item.packageName, user, stillOnHome)
    }

    // ---- Profiles ----

    fun setQuietMode(profile: Profile, quiet: Boolean) {
        if (!appsRepo.requestQuietMode(profile, quiet)) {
            _prompt.value = LauncherPrompt.NeedDefaultLauncher
        }
    }

    fun showPrompt(prompt: LauncherPrompt) {
        _prompt.value = prompt
    }

    fun dismissPrompt() {
        _prompt.value = null
    }

    // ---- Gestures ----

    /** @return false if the lock service isn't on, so the UI can explain how to enable it. */
    fun lockScreen(): Boolean {
        if (LauncherAccessibilityService.lockScreen()) return true
        _prompt.value = LauncherPrompt.EnableLockService
        return false
    }

    fun expandNotifications() {
        if (!SystemActions.expandNotifications(app)) {
            SystemActions.toast(app, app.getString(R.string.error_notifications))
        }
    }

    // ---- Building UI models ----

    private fun buildItems(snap: AppsSnapshot, labels: Map<String, String>, hidden: Set<String>): List<AppItem> {
        val locale = Locale.getDefault()
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        // ICU buckets handle every script properly (e.g. kana rows in Japanese, pinyin in Chinese).
        val index = try {
            AlphabeticIndex<Any>(locale).addLabels(Locale.ENGLISH).buildImmutableIndex()
        } catch (e: Exception) {
            null
        }
        return snap.apps.map { installed ->
            val label = labels[installed.key] ?: installed.systemLabel
            AppItem(
                app = installed,
                label = label,
                isHidden = installed.key in hidden,
                section = sectionOf(index, label),
                search = Search.keyFor(label, installed.systemLabel, installed.packageName),
            )
        }.sortedWith { a, b ->
            val byLabel = collator.compare(a.label, b.label)
            when {
                byLabel != 0 -> byLabel
                a.profileType != b.profileType -> a.profileType.ordinal - b.profileType.ordinal
                else -> a.key.compareTo(b.key)
            }
        }
    }

    private fun sectionOf(index: AlphabeticIndex.ImmutableIndex<Any>?, label: String): String {
        if (index != null) {
            val bucket = index.getBucket(index.getBucketIndex(label))
            if (bucket != null && bucket.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL) return bucket.label
            return "#"
        }
        val first = label.firstOrNull { !it.isWhitespace() } ?: return "#"
        return if (first.isLetter()) first.uppercase() else "#"
    }

    private data class DrawerPrefs(
        val separate: Boolean,
        val hiddenTab: Boolean,
        val folders: List<DrawerFolder>,
        val categories: List<AppCategory>,
        val sort: DrawerSort,
    )

    private fun buildDrawer(apps: List<AppItem>, snap: AppsSnapshot, prefs: DrawerPrefs, stats: Map<String, LaunchStats.Entry>): DrawerModel {
        val visibleProfiles = snap.profiles.filter { it.isVisible }
        val work = visibleProfiles.firstOrNull { it.type == ProfileType.WORK }
        val private = visibleProfiles.firstOrNull { it.type == ProfileType.PRIVATE }
        val personal = visibleProfiles.firstOrNull { it.type == ProfileType.PERSONAL }
        val separate = prefs.separate

        fun tabOf(type: ProfileType): DrawerTab = when (type) {
            ProfileType.PERSONAL, ProfileType.CLONE -> DrawerTab.PERSONAL
            ProfileType.WORK -> if (separate) DrawerTab.WORK else DrawerTab.PERSONAL
            // Private space never mixes into other lists, even with tabs off. That's its point.
            ProfileType.PRIVATE -> DrawerTab.PRIVATE
        }

        val shown = apps.filter { !it.isHidden && it.app.profile.isVisible }
        val byKey = shown.associateBy { it.key }
        // Hidden apps from any visible profile except private space (which already has its own lock).
        val hidden = apps.filter { it.isHidden && it.app.profile.isVisible && it.profileType != ProfileType.PRIVATE }

        // Ordering: alphabetical (ICU collation) or most used first.
        val locale = Locale.getDefault()
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        val index = try {
            AlphabeticIndex<Any>(locale).addLabels(Locale.ENGLISH).buildImmutableIndex()
        } catch (e: Exception) {
            null
        }
        val now = System.currentTimeMillis()
        fun score(entry: DrawerEntry): Double = when (entry) {
            is DrawerEntry.App -> LaunchStats.decayed(stats[entry.key], now)
            is DrawerEntry.Folder -> entry.apps.maxOfOrNull { LaunchStats.decayed(stats[it.key], now) } ?: 0.0
        }
        val alphabetical = prefs.sort == DrawerSort.ALPHABETICAL
        fun List<DrawerEntry>.ordered(): List<DrawerEntry> {
            val byName = Comparator<DrawerEntry> { a, b -> collator.compare(a.label, b.label) }
            return if (alphabetical) sortedWith(byName) else sortedWith(compareByDescending<DrawerEntry> { score(it) }.then(byName))
        }

        // Folders: valid with at least two apps we can show; their apps leave the main lists.
        val folderLabel = app.getString(R.string.folder_default)
        val folders = prefs.folders.mapNotNull { f ->
            val members = f.apps.mapNotNull { byKey[it] }
            if (members.size < 2) return@mapNotNull null
            val label = f.name.ifBlank { folderLabel }
            DrawerEntry.Folder(f, members, label, sectionOf(index, label))
        }
        val inFolders = folders.flatMap { f -> f.apps.map { it.key } }.toSet()

        val categoryTabs = prefs.categories.map { DrawerTab.Category(it.id) }
        val tabs = buildList {
            add(DrawerTab.PERSONAL)
            if (work != null && separate) add(DrawerTab.WORK)
            if (private != null) add(DrawerTab.PRIVATE)
            addAll(categoryTabs)
            if (prefs.hiddenTab && hidden.isNotEmpty()) add(DrawerTab.HIDDEN)
        }

        val profileEntries: Map<DrawerTab, List<DrawerEntry>> = shown
            .filter { it.key !in inFolders }
            .groupBy { tabOf(it.profileType) }
            .mapValues { (_, list) -> list.map { DrawerEntry.App(it) } }
        // A folder lives in the tab of its first app.
        val folderEntries = folders.groupBy { tabOf(it.apps.first().profileType) }

        val entries = tabs.associateWith { tab ->
            when (tab) {
                DrawerTab.Hidden -> hidden.map { DrawerEntry.App(it) }.ordered()
                is DrawerTab.Category -> {
                    val members = prefs.categories.firstOrNull { it.id == tab.id }?.apps.orEmpty()
                    shown.filter { it.key in members }.map { DrawerEntry.App(it) }.ordered()
                }
                else -> (profileEntries[tab].orEmpty() + folderEntries[tab].orEmpty()).ordered()
            }
        }
        return DrawerModel(
            tabs = tabs,
            entriesByTab = entries,
            categoryNames = prefs.categories.associate { it.id to it.name },
            alphabetical = alphabetical,
            profileByTab = buildMap {
                personal?.let { put(DrawerTab.PERSONAL, it) }
                if (separate) work?.let { put(DrawerTab.WORK, it) }
                private?.let { put(DrawerTab.PRIVATE, it) }
            },
            mergedWorkProfile = work.takeIf { !separate },
        )
    }

    // ---- Folders and categories ----

    /** A name for a new folder: the app type both apps share (e.g. "Social"), otherwise blank. */
    fun folderNameFor(keys: List<String>): String {
        val apps = keys.mapNotNull { k -> allApps.value.firstOrNull { it.key == k } }
        val category = apps.map { it.app.appCategory }.distinct().singleOrNull() ?: return ""
        if (category < 0) return ""
        return ApplicationInfo.getCategoryTitle(app, category)?.toString().orEmpty()
    }

    fun dropOnHome(draggedId: String, targetId: String) {
        val target = settings.value.favorites.firstOrNull { it.id == targetId }
        val dragged = settings.value.favorites.firstOrNull { it.id == draggedId } as? HomeItem.App ?: return
        val name = if (target is HomeItem.App) folderNameFor(listOf(target.appKey, dragged.appKey)) else ""
        settingsRepo.mergeOnHome(draggedId, targetId, name)
    }

    fun dropInDrawer(dragged: AppItem, target: DrawerEntry) {
        when (target) {
            is DrawerEntry.App -> settingsRepo.mergeInDrawer(dragged.key, target.key, null, folderNameFor(listOf(target.key, dragged.key)))
            is DrawerEntry.Folder -> settingsRepo.mergeInDrawer(dragged.key, null, target.folder.id, "")
        }
    }

    /**
     * Creates categories from the app types apps declare to Android (Games, Social, Maps, ...).
     * @return how many categories were created or extended.
     */
    fun suggestCategories(): Int {
        val groups = allApps.value
            .filter { !it.isHidden && it.app.profile.isVisible && it.app.appCategory >= 0 }
            .groupBy { it.app.appCategory }
            .filterValues { it.size >= 2 }
            .mapNotNull { (cat, list) ->
                val name = ApplicationInfo.getCategoryTitle(app, cat)?.toString() ?: return@mapNotNull null
                name to list.map { it.key }.toSet()
            }
        if (groups.isNotEmpty()) settingsRepo.addCategories(groups)
        return groups.size
    }

    /** First run: put the phone, messages, browser and camera on the home screen so it isn't empty. */
    private fun seedFavoritesOnce() {
        val current = settings.value
        if (current.favoritesSeeded) return
        if (current.favorites.isNotEmpty()) {
            settingsRepo.update { it.copy(favoritesSeeded = true) }
            return
        }
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        val seeded = intents.mapNotNull { intent ->
            SystemActions.defaultHandlerPackage(app, intent)?.let { pkg -> appsRepo.findAppByPackage(pkg) }
        }.map(InstalledApp::key).distinct().map { HomeItem.App(it) }
        settingsRepo.update { s ->
            if (s.favoritesSeeded) s else s.copy(favorites = s.favorites.ifEmpty { seeded }, favoritesSeeded = true)
        }
    }

    fun reorderHome(orderedIds: List<String>) = settingsRepo.reorderHome(orderedIds)

    fun pauseApp(item: AppItem, until: Long) {
        settingsRepo.pauseApp(item.key, until)
        clock.value = System.currentTimeMillis()
    }

    fun unpauseApp(item: AppItem) {
        settingsRepo.unpauseApp(item.key)
        clock.value = System.currentTimeMillis()
    }

    companion object {
        private const val MAX_SUGGESTIONS = 5
        private const val MIN_SUGGESTION_SCORE = 0.9
        private const val NEW_APP_WINDOW_MS = 3L * 24 * 60 * 60 * 1000
        private const val MAX_RESULTS = 60
        private const val MAX_QUERY = 100
    }
}
