package app.haven.launcher.ui

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.haven.launcher.data.LauncherSettings
import app.haven.launcher.data.SettingResult
import org.junit.Assert.assertEquals
import app.haven.launcher.ui.drawer.AppDrawer
import app.haven.launcher.ui.drawer.DrawerCallbacks
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opens the app drawer for real (on Robolectric) with several tabs, so crashes in composition,
 * such as an unsaveable pager key, fail CI instead of reaching phones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DrawerSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    private val noCallbacks = DrawerCallbacks(
        onQueryChange = {},
        onSelectTab = {},
        onLaunch = { _, _ -> },
        onLongPress = {},
        onSetQuiet = { _, _ -> },
        onOpenSettings = {},
        onUnlockHidden = {},
        onOpenFolder = {},
        onDropApp = { _, _ -> },
    )

    private fun drawer(
        tabs: List<DrawerTab>,
        selected: DrawerTab = DrawerTab.PERSONAL,
        query: String = "",
        search: SearchState = SearchState("", emptyList()),
        callbacks: DrawerCallbacks = noCallbacks,
    ) {
        compose.setContent {
            AppDrawer(
                settings = LauncherSettings(),
                model = DrawerModel(tabs = tabs, categoryNames = mapOf("games" to "Games")),
                loaded = true,
                selectedTab = selected,
                query = query,
                search = search,
                suggestions = emptyList(),
                newApps = emptySet(),
                hiddenUnlocked = false,
                searchFocus = remember { FocusRequester() },
                callbacks = callbacks,
            )
        }
    }

    @Test
    fun opensWithPersonalAndWorkTabs() {
        drawer(listOf(DrawerTab.PERSONAL, DrawerTab.WORK))
        compose.onNodeWithText("Work").assertExists()
    }

    @Test
    fun opensWithCategoriesAndHiddenTabs() {
        drawer(listOf(DrawerTab.PERSONAL, DrawerTab.WORK, DrawerTab.Category("games"), DrawerTab.HIDDEN))
        compose.onNodeWithText("Games").assertExists().performClick()
        compose.onNodeWithText("Hidden").assertExists()
    }

    @Test
    fun opensOnACategoryTab() {
        drawer(listOf(DrawerTab.PERSONAL, DrawerTab.Category("games")), selected = DrawerTab.Category("games"))
        compose.onNodeWithText("Games").assertExists()
    }

    @Test
    fun opensWithSingleTab() {
        drawer(listOf(DrawerTab.PERSONAL))
        compose.onNodeWithText("Search apps").assertExists()
    }

    @Test
    fun searchShowsPhoneSettingsAndOpensThem() {
        var opened: SettingResult? = null
        val wifi = SettingResult("wifi", "Wi-Fi", "android.settings.WIFI_SETTINGS")
        drawer(
            listOf(DrawerTab.PERSONAL),
            query = "wifi",
            search = SearchState("wifi", emptyList(), settings = listOf(wifi)),
            callbacks = DrawerCallbacks(
                onQueryChange = {},
                onSelectTab = {},
                onLaunch = { _, _ -> },
                onLongPress = {},
                onSetQuiet = { _, _ -> },
                onOpenSettings = {},
                onUnlockHidden = {},
                onOpenFolder = {},
                onDropApp = { _, _ -> },
                onOpenSetting = { opened = it },
            ),
        )
        compose.onNodeWithText("Settings").assertExists()
        // A settings match isn't "no results".
        compose.onNodeWithText("No apps match \"wifi\"", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Wi-Fi").performClick()
        assertEquals(wifi, opened)
    }
}
