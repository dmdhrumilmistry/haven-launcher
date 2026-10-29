package app.haven.launcher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Android has no public API for a launcher to clone apps; cloning is built into the phone by its
 * maker (Samsung Dual Messenger, Xiaomi Dual apps, Android 14+ "Cloned apps", ...). This finds
 * that screen if the phone has one, so "Clone app" can take people straight there.
 */
object CloneSupport {
    private val candidates: List<Intent> = listOf(
        // Android 14+ (AOSP) "Cloned apps".
        Intent("android.settings.MANAGE_CLONED_APPS_SETTINGS"),
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$ClonedAppsListActivity")),
        // Samsung: Dual Messenger.
        Intent().setComponent(ComponentName("com.samsung.android.da.daagent", "com.samsung.android.da.daagent.activity.DualAppActivity")),
        // Xiaomi / Redmi / POCO: Dual apps.
        Intent().setComponent(ComponentName("com.miui.securitycore", "com.miui.xspace.ui.activity.XSpaceSettingActivity")),
    )

    private var cached: Intent? = null
    private var checked = false

    /** The phone's cloning screen, or null if it doesn't have one. */
    fun cloneSettingsIntent(context: Context): Intent? {
        if (!checked) {
            cached = candidates.firstOrNull {
                context.packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null
            }
            checked = true
        }
        return cached?.let { Intent(it) }
    }

    fun openCloneSettings(context: Context): Boolean {
        val intent = cloneSettingsIntent(context) ?: return false
        return SystemActions.safeStart(context, intent)
    }
}
