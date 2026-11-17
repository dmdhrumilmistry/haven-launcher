package app.haven.launcher.data

import android.app.Application
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsSearchTest {
    private fun score(label: String, keywords: List<String>, q: String) =
        SettingsSearch.score(label, keywords, Search.normalize(q))

    @Test
    fun findsByNameAndByEverydayWords() {
        assertTrue(score("Wi-Fi", listOf("wifi", "wlan"), "wi") > 0)
        assertTrue(score("Wi-Fi", listOf("wifi", "wlan"), "wifi") > 0)
        assertTrue(score("Display and brightness", listOf("brightness"), "bright") > 0)
        assertTrue(score("Battery", listOf("battery", "power"), "power") > 0)
    }

    @Test
    fun ignoresLooseMatches() {
        // Subsequence-style fuzzy matching would make "bt" or "ty" pull up unrelated screens.
        assertEquals(0, score("Battery", listOf("battery"), "ty"))
        assertEquals(0, score("Storage", listOf("storage"), "zzz"))
    }

    @Test
    fun nameBeatsKeyword() {
        assertTrue(score("Bluetooth", listOf("bluetooth"), "blue") > score("Network and internet", listOf("bluetooth"), "blue"))
    }

    @Test
    fun shortQueriesFindNothingAndHavenIsAlwaysThere() {
        val context = RuntimeEnvironment.getApplication()
        val available = SettingsSearch.available(context)
        assertTrue(available.any { it.action == SettingsSearch.HAVEN })
        assertTrue(SettingsSearch.search(context, available, "h").isEmpty())
        assertEquals(SettingsSearch.HAVEN, SettingsSearch.search(context, available, "haven").first().id)
    }

    @Test
    fun newSettingsSurviveARoundTripAndOldBackupsGetDefaults() {
        val custom = LauncherSettings(
            holdPausedNotifications = false,
            showPauseStatus = false,
            searchSettings = false,
            freezePausedApps = true,
            swipeLeftApp = "com.example.camera/.Main#0",
            swipeRightApp = null,
            showBattery = true,
        )
        assertEquals(custom, LauncherSettings.fromJson(custom.toJson()))
        val old = LauncherSettings.fromJson(org.json.JSONObject().put("version", 2))
        assertTrue(old.holdPausedNotifications)
        assertTrue(old.showPauseStatus)
        assertTrue(old.searchSettings)
        assertFalse(old.freezePausedApps)
        // A junk app key in a hand-edited backup is dropped, not kept as a dead gesture.
        val junk = LauncherSettings.fromJson(org.json.JSONObject().put("swipeLeftApp", "not a key"))
        assertEquals(null, junk.swipeLeftApp)
        assertFalse(LauncherSettings.fromJson(custom.toJson()).holdPausedNotifications)
    }
}
