package app.haven.launcher.data

import app.haven.launcher.focus.PauseSchedule
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class TextColorMode { AUTO, LIGHT, DARK }
enum class ClockFormat { SYSTEM, H12, H24 }
enum class ClockSize { SMALL, MEDIUM, LARGE }
enum class HomeAlignment { START, CENTER, END }
enum class HomeStyle { TEXT, ICONS_AND_TEXT, DOCK }
enum class HomePosition { CENTER, BOTTOM }
enum class DrawerLayout { LIST, GRID }
enum class IconSize(val dp: Int) { SMALL(40), MEDIUM(48), LARGE(56) }
enum class IconShape { SYSTEM, CIRCLE, SQUIRCLE, ROUNDED_SQUARE }
enum class SwipeDownAction { NOTIFICATIONS, SEARCH, NONE }
enum class DoubleTapAction { LOCK, NONE }
enum class DrawerSort { ALPHABETICAL, MOST_USED }
enum class BadgeStyle { OFF, DOT, COUNT }

/** A folder in the app drawer. Its apps leave the main list and live inside it. */
data class DrawerFolder(val id: String, val name: String, val apps: List<String>)

/** A user category: a drawer tab. Apps can be in several categories and stay in the main list too. */
data class AppCategory(val id: String, val name: String, val apps: Set<String>)

fun newId(): String = java.util.UUID.randomUUID().toString()

/**
 * Something pinned to the home screen: either an app or a pinned shortcut (e.g. a website a browser
 * added via "Add to home screen").
 *
 * Users are stored by serial number because [android.os.UserHandle] isn't stable across reboots
 * in a persistable form, while the serial number is.
 */
sealed interface HomeItem {
    val id: String

    data class App(val appKey: String) : HomeItem {
        override val id get() = "app:$appKey"
    }

    /** A folder of apps on the home screen. An empty name shows as "Folder". */
    data class Folder(val folderId: String, val name: String, val apps: List<String>) : HomeItem {
        override val id get() = "folder:$folderId"
    }

    data class Shortcut(
        val packageName: String,
        val shortcutId: String,
        val userSerial: Long,
        val label: String,
    ) : HomeItem {
        override val id get() = "sc:$packageName#$userSerial#$shortcutId"
    }
}

/** A widget on the home screen. The id belongs to our AppWidgetHost and is only valid on this device. */
data class HomeWidget(val id: Int, val rows: Int)

/**
 * Every user-facing preference in one immutable object. Defaults are chosen to be calm and
 * familiar out of the box; everything else is opt-in.
 */
data class LauncherSettings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val homeTextColor: TextColorMode = TextColorMode.AUTO,
    val wallpaperDim: Float = 0f,
    val textScale: Float = 1f,
    val hideStatusBar: Boolean = false,
    val drawerOpacity: Float = 0.92f,

    // Home
    val showClock: Boolean = true,
    val clockFormat: ClockFormat = ClockFormat.SYSTEM,
    val clockSize: ClockSize = ClockSize.MEDIUM,
    val showDate: Boolean = true,
    // Stock-Android feel by default: a centered grid of icons.
    val homeAlignment: HomeAlignment = HomeAlignment.CENTER,
    val homeStyle: HomeStyle = HomeStyle.DOCK,
    val homeColumns: Int = 4,
    val homePosition: HomePosition = HomePosition.CENTER,
    val showDrawerButton: Boolean = true,
    val lockHomeLayout: Boolean = false,
    val favorites: List<HomeItem> = emptyList(),
    val favoritesSeeded: Boolean = false,
    val widgets: List<HomeWidget> = emptyList(),

    // App drawer
    val drawerLayout: DrawerLayout = DrawerLayout.GRID,
    val gridColumns: Int = 0, // 0 = adapt to screen width
    val showGridLabels: Boolean = true,
    val showListIcons: Boolean = true,
    val iconSize: IconSize = IconSize.MEDIUM,
    val iconShape: IconShape = IconShape.SYSTEM,
    val themedIcons: Boolean = false,
    /** Package of the selected icon pack, or null for the apps' own icons. */
    val iconPack: String? = null,
    val badgeStyle: BadgeStyle = BadgeStyle.DOT,
    val separateProfileTabs: Boolean = true,
    val showSuggestions: Boolean = true,
    val drawerSort: DrawerSort = DrawerSort.ALPHABETICAL,
    val drawerFolders: List<DrawerFolder> = emptyList(),
    val categories: List<AppCategory> = emptyList(),

    // Search
    // Off: opening the drawer to browse shouldn't throw a keyboard over half the apps.
    val autoShowKeyboard: Boolean = false,
    val searchBarAtBottom: Boolean = false,
    val autoLaunchSingleResult: Boolean = false,
    val showWebSearch: Boolean = true,
    val searchHiddenApps: Boolean = false,
    /** Hidden apps need the device's fingerprint/face/PIN before they can be seen. */
    val lockHiddenApps: Boolean = false,
    /** A "Hidden" section in the app drawer, next to Personal and Work. */
    val showHiddenTab: Boolean = true,

    // Gestures
    val swipeDownAction: SwipeDownAction = SwipeDownAction.NOTIFICATIONS,
    val doubleTapAction: DoubleTapAction = DoubleTapAction.NONE,

    // Per-app customisation (keys are AppKey strings)
    val hiddenApps: Set<String> = emptySet(),
    val customLabels: Map<String, String> = emptyMap(),

    // Pausing (soft, Haven-level): manual pauses (app key -> until, epoch ms) and schedules.
    val pausedApps: Map<String, Long> = emptyMap(),
    val pauseSchedules: List<PauseSchedule> = emptyList(),
) {
    /** On the home screen, directly or inside a home folder. */
    fun isFavorite(appKey: String) = favorites.any {
        (it is HomeItem.App && it.appKey == appKey) || (it is HomeItem.Folder && appKey in it.apps)
    }

    fun drawerFolderOf(appKey: String): DrawerFolder? = drawerFolders.firstOrNull { appKey in it.apps }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", SCHEMA_VERSION)
        put("themeMode", themeMode.name)
        put("dynamicColor", dynamicColor)
        put("homeTextColor", homeTextColor.name)
        put("wallpaperDim", wallpaperDim.toDouble())
        put("textScale", textScale.toDouble())
        put("hideStatusBar", hideStatusBar)
        put("drawerOpacity", drawerOpacity.toDouble())
        put("showClock", showClock)
        put("clockFormat", clockFormat.name)
        put("clockSize", clockSize.name)
        put("showDate", showDate)
        put("homeAlignment", homeAlignment.name)
        put("homeStyle", homeStyle.name)
        put("homeColumns", homeColumns)
        put("homePosition", homePosition.name)
        put("showDrawerButton", showDrawerButton)
        put("lockHomeLayout", lockHomeLayout)
        put("showSuggestions", showSuggestions)
        put("drawerSort", drawerSort.name)
        put("drawerFolders", JSONArray().apply {
            drawerFolders.forEach { f -> put(JSONObject().put("id", f.id).put("name", f.name).put("apps", JSONArray(f.apps))) }
        })
        put("categories", JSONArray().apply {
            categories.forEach { c -> put(JSONObject().put("id", c.id).put("name", c.name).put("apps", JSONArray(c.apps.sorted()))) }
        })
        put("lockHiddenApps", lockHiddenApps)
        put("showHiddenTab", showHiddenTab)
        put("favorites", JSONArray().apply { favorites.forEach { put(it.toJson()) } })
        put("favoritesSeeded", favoritesSeeded)
        put("widgets", JSONArray().apply { widgets.forEach { put(JSONObject().put("id", it.id).put("rows", it.rows)) } })
        put("drawerLayout", drawerLayout.name)
        put("gridColumns", gridColumns)
        put("showGridLabels", showGridLabels)
        put("showListIcons", showListIcons)
        put("iconSize", iconSize.name)
        put("iconShape", iconShape.name)
        put("themedIcons", themedIcons)
        put("iconPack", iconPack ?: "")
        put("badgeStyle", badgeStyle.name)
        put("separateProfileTabs", separateProfileTabs)
        put("autoShowKeyboard", autoShowKeyboard)
        put("searchBarAtBottom", searchBarAtBottom)
        put("autoLaunchSingleResult", autoLaunchSingleResult)
        put("showWebSearch", showWebSearch)
        put("searchHiddenApps", searchHiddenApps)
        put("swipeDownAction", swipeDownAction.name)
        put("doubleTapAction", doubleTapAction.name)
        put("hiddenApps", JSONArray().apply { hiddenApps.sorted().forEach { put(it) } })
        put("customLabels", JSONObject().apply { customLabels.forEach { (k, v) -> put(k, v) } })
        put("pausedApps", JSONObject().apply { pausedApps.forEach { (k, v) -> put(k, v) } })
        put("pauseSchedules", JSONArray().apply { pauseSchedules.forEach { put(it.toJson()) } })
    }

    companion object {
        // 2: keyboard no longer opens with the drawer by default (it was on in 1).
        const val SCHEMA_VERSION = 2

        /**
         * Lenient parser: unknown keys are ignored and missing or malformed values fall back to
         * defaults, so old backups and hand-edited files never crash the launcher.
         */
        fun fromJson(json: JSONObject): LauncherSettings {
            val d = LauncherSettings()
            val version = json.optInt("version", 1)
            return LauncherSettings(
                themeMode = json.enum("themeMode", d.themeMode),
                dynamicColor = json.optBoolean("dynamicColor", d.dynamicColor),
                homeTextColor = json.enum("homeTextColor", d.homeTextColor),
                wallpaperDim = json.float("wallpaperDim", d.wallpaperDim, 0f, 0.8f),
                textScale = json.float("textScale", d.textScale, 0.8f, 1.4f),
                hideStatusBar = json.optBoolean("hideStatusBar", d.hideStatusBar),
                drawerOpacity = json.float("drawerOpacity", d.drawerOpacity, 0.5f, 1f),
                showClock = json.optBoolean("showClock", d.showClock),
                clockFormat = json.enum("clockFormat", d.clockFormat),
                clockSize = json.enum("clockSize", d.clockSize),
                showDate = json.optBoolean("showDate", d.showDate),
                homeAlignment = json.enum("homeAlignment", d.homeAlignment),
                homeStyle = json.enum("homeStyle", d.homeStyle),
                homeColumns = json.optInt("homeColumns", d.homeColumns).coerceIn(3, 6),
                homePosition = json.enum("homePosition", d.homePosition),
                showDrawerButton = json.optBoolean("showDrawerButton", d.showDrawerButton),
                lockHomeLayout = json.optBoolean("lockHomeLayout", d.lockHomeLayout),
                showSuggestions = json.optBoolean("showSuggestions", d.showSuggestions),
                drawerSort = json.enum("drawerSort", d.drawerSort),
                drawerFolders = json.optJSONArray("drawerFolders")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("id").ifBlank { return@mapNotNull null }
                        DrawerFolder(id, o.optString("name").take(40), o.strings("apps").distinct())
                    }.filter { it.apps.isNotEmpty() }.distinctBy { it.id }
                } ?: d.drawerFolders,
                categories = json.optJSONArray("categories")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("id").ifBlank { return@mapNotNull null }
                        AppCategory(id, o.optString("name").take(40), o.strings("apps").toSet())
                    }.distinctBy { it.id }
                } ?: d.categories,
                lockHiddenApps = json.optBoolean("lockHiddenApps", d.lockHiddenApps),
                showHiddenTab = json.optBoolean("showHiddenTab", d.showHiddenTab),
                favorites = json.optJSONArray("favorites")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::homeItemFromJson) }
                }?.distinctBy { it.id } ?: d.favorites,
                favoritesSeeded = json.optBoolean("favoritesSeeded", d.favoritesSeeded),
                widgets = json.optJSONArray("widgets")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optInt("id", -1)
                        if (id < 0) null else HomeWidget(id, o.optInt("rows", 2).coerceIn(1, 6))
                    }.distinctBy { it.id }
                } ?: d.widgets,
                drawerLayout = json.enum("drawerLayout", d.drawerLayout),
                gridColumns = json.optInt("gridColumns", d.gridColumns).let { if (it == 0 || it in 3..7) it else 0 },
                showGridLabels = json.optBoolean("showGridLabels", d.showGridLabels),
                showListIcons = json.optBoolean("showListIcons", d.showListIcons),
                iconSize = json.enum("iconSize", d.iconSize),
                iconShape = json.enum("iconShape", d.iconShape),
                themedIcons = json.optBoolean("themedIcons", d.themedIcons),
                iconPack = json.optString("iconPack").takeIf { it.isNotBlank() },
                badgeStyle = json.enum("badgeStyle", d.badgeStyle),
                separateProfileTabs = json.optBoolean("separateProfileTabs", d.separateProfileTabs),
                // Version 1 saved "on" for everyone because it was the default; reset it once.
                autoShowKeyboard = if (version < 2) false else json.optBoolean("autoShowKeyboard", d.autoShowKeyboard),
                searchBarAtBottom = json.optBoolean("searchBarAtBottom", d.searchBarAtBottom),
                autoLaunchSingleResult = json.optBoolean("autoLaunchSingleResult", d.autoLaunchSingleResult),
                showWebSearch = json.optBoolean("showWebSearch", d.showWebSearch),
                searchHiddenApps = json.optBoolean("searchHiddenApps", d.searchHiddenApps),
                swipeDownAction = json.enum("swipeDownAction", d.swipeDownAction),
                doubleTapAction = json.enum("doubleTapAction", d.doubleTapAction),
                hiddenApps = json.optJSONArray("hiddenApps")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
                } ?: d.hiddenApps,
                customLabels = json.optJSONObject("customLabels")?.let { obj ->
                    obj.keys().asSequence()
                        .mapNotNull { k -> obj.optString(k).trim().takeIf { it.isNotEmpty() }?.let { k to it } }
                        .toMap()
                } ?: d.customLabels,
                pausedApps = json.optJSONObject("pausedApps")?.let { obj ->
                    obj.keys().asSequence().associateWith { obj.optLong(it, 0L) }.filterValues { it > 0 }
                } ?: d.pausedApps,
                pauseSchedules = json.optJSONArray("pauseSchedules")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(PauseSchedule::fromJson) }.distinctBy { it.id }
                } ?: d.pauseSchedules,
            )
        }

        private fun HomeItem.toJson(): JSONObject = when (this) {
            is HomeItem.App -> JSONObject().put("type", "app").put("key", appKey)
            is HomeItem.Folder -> JSONObject()
                .put("type", "folder")
                .put("id", folderId)
                .put("name", name)
                .put("apps", JSONArray(apps))
            is HomeItem.Shortcut -> JSONObject()
                .put("type", "shortcut")
                .put("package", packageName)
                .put("id", shortcutId)
                .put("serial", userSerial)
                .put("label", label)
        }

        private fun homeItemFromJson(o: JSONObject): HomeItem? = when (o.optString("type")) {
            "app" -> o.optString("key").takeIf { AppKey.parse(it) != null }?.let { HomeItem.App(it) }
            "folder" -> {
                val id = o.optString("id")
                val apps = o.strings("apps").filter { AppKey.parse(it) != null }.distinct()
                if (id.isBlank() || apps.isEmpty()) null else HomeItem.Folder(id, o.optString("name").take(40), apps)
            }
            "shortcut" -> {
                val pkg = o.optString("package")
                val id = o.optString("id")
                if (pkg.isBlank() || id.isBlank()) null
                else HomeItem.Shortcut(pkg, id, o.optLong("serial", 0L), o.optString("label"))
            }
            else -> null
        }

        private fun JSONObject.strings(key: String): List<String> =
            optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()

        private inline fun <reified E : Enum<E>> JSONObject.enum(key: String, default: E): E =
            optString(key).let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

        private fun JSONObject.float(key: String, default: Float, min: Float, max: Float): Float {
            val v = optDouble(key, default.toDouble()).toFloat()
            return if (v.isNaN()) default else v.coerceIn(min, max)
        }
    }
}
