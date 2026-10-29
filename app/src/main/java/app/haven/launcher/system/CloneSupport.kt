package app.haven.launcher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Android has no public API for a launcher to clone apps; cloning is built into the phone by its
 * maker (Android 14+ "Cloned apps", Samsung Dual Messenger, Xiaomi Dual apps, Motorola and others
 * under various names). This finds that screen if the phone has one, so "Clone app" can take
 * people straight there.
 */
object CloneSupport {
    private const val TAG = "HavenClone"

    // Known screens, tried first.
    private val known: List<Intent> = listOf(
        Intent("android.settings.MANAGE_CLONED_APPS_SETTINGS"),
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$ClonedAppsListActivity")),
        Intent().setComponent(ComponentName("com.samsung.android.da.daagent", "com.samsung.android.da.daagent.activity.DualAppActivity")),
        Intent().setComponent(ComponentName("com.miui.securitycore", "com.miui.xspace.ui.activity.XSpaceSettingActivity")),
    )

    // Other phone makers name it differently: "Dual apps", "Parallel apps", "App twin", "Clone apps".
    private val namePattern = Regex("(?i)(clon|dual.?app|parallel|twin|multi.?app|xspace)")
    private val excluded = Regex("(?i)(dialog|receiver|service|provider|confirm|guide|tutorial)")
    private val settingsPackages = listOf("com.android.settings", "com.motorola.settings")

    @Volatile private var cached: Intent? = null
    @Volatile private var checked = false

    /** The phone's cloning screen, or null if it doesn't have one. */
    fun cloneSettingsIntent(context: Context): Intent? {
        if (!checked) {
            cached = findKnown(context) ?: discover(context)
            checked = true
        }
        return cached?.let { Intent(it) }
    }

    private fun findKnown(context: Context): Intent? = known.firstOrNull {
        context.packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null
    }

    /** Looks through the Settings app for an exported screen whose name says it's about cloning. */
    private fun discover(context: Context): Intent? {
        val pm = context.packageManager
        for (pkg in settingsPackages) {
            val activities = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())).activities
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
                }
            } catch (e: Exception) {
                null
            } ?: continue
            val match = activities
                .filter { it.exported && it.enabled && it.permission == null }
                .filter { namePattern.containsMatchIn(it.name.substringAfterLast('.')) && !excluded.containsMatchIn(it.name) }
                // Prefer list/settings screens over detail screens.
                .sortedBy { a -> if (Regex("(?i)(list|settings|manage|main)").containsMatchIn(a.name)) 0 else 1 }
                .firstOrNull() ?: continue
            Log.i(TAG, "Using clone screen ${match.name}")
            return Intent().setComponent(ComponentName(match.packageName, match.name))
        }
        return null
    }

    fun openCloneSettings(context: Context): Boolean {
        val intent = cloneSettingsIntent(context) ?: return false
        if (SystemActions.safeStart(context, intent, showError = false)) return true
        // The screen exists but refused to open (some need extras): forget it and try discovery.
        if (cached != null) {
            cached = discover(context)?.takeIf { it.component != intent.component }
            cached?.let { return SystemActions.safeStart(context, Intent(it)) }
        }
        SystemActions.toast(context, context.getString(app.haven.launcher.R.string.error_clone))
        return false
    }
}
