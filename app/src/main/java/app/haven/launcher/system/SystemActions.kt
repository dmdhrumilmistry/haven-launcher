package app.haven.launcher.system

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.SearchManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import app.haven.launcher.R
import app.haven.launcher.data.InstalledApp

/**
 * Every outward action the launcher performs. Each one fails soft: a toast instead of a crash,
 * because a home screen that crashes is a phone that's unusable.
 */
object SystemActions {
    private const val TAG = "HavenActions"

    /** Zoom-from-icon animation when we know where the tap happened. */
    private fun launchOptions(view: View?, bounds: Rect?): Bundle? {
        if (view == null || bounds == null || bounds.isEmpty) return null
        return ActivityOptions.makeClipRevealAnimation(view, bounds.left, bounds.top, bounds.width(), bounds.height())
            .toBundle()
    }

    fun launchApp(context: Context, app: InstalledApp, view: View? = null, bounds: Rect? = null): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        return try {
            launcherApps.startMainActivity(app.component, app.user, bounds, launchOptions(view, bounds))
            true
        } catch (e: Exception) {
            // ActivityNotFound (app just uninstalled/disabled), SecurityException (admin policy), etc.
            Log.w(TAG, "Launch failed for ${app.component}", e)
            toast(context, context.getString(R.string.error_cant_open, app.systemLabel))
            false
        }
    }

    fun launchShortcut(context: Context, shortcut: ShortcutInfo, view: View? = null, bounds: Rect? = null): Boolean {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        return try {
            launcherApps.startShortcut(shortcut, bounds, launchOptions(view, bounds))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Shortcut failed: ${shortcut.id}", e)
            toast(context, context.getString(R.string.error_cant_open, shortcut.shortLabel ?: ""))
            false
        }
    }

    fun openAppInfo(context: Context, component: ComponentName, user: UserHandle) {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        try {
            launcherApps.startAppDetailsActivity(component, user, null, null)
        } catch (e: Exception) {
            safeStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", component.packageName, null)))
        }
    }

    fun uninstall(context: Context, packageName: String, user: UserHandle) {
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
            .putExtra(Intent.EXTRA_USER, user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        safeStart(context, intent)
    }

    /** Swipe-down gesture. Uses the accessibility service if on, otherwise the status bar service. */
    @SuppressLint("WrongConstant")
    fun expandNotifications(context: Context): Boolean {
        if (LauncherAccessibilityService.openNotifications()) return true
        return try {
            val service = context.getSystemService("statusbar") ?: return false
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't expand notifications", e)
            false
        }
    }

    fun webSearch(context: Context, query: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (!safeStart(context, intent, showError = false)) {
            safeStart(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(query))))
        }
    }

    fun storeSearch(context: Context, query: String) {
        safeStart(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query) + "&c=apps")))
    }

    fun canWebSearch(context: Context) = canResolve(context, Intent(Intent.ACTION_WEB_SEARCH))
    fun canStoreSearch(context: Context) =
        canResolve(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=x")))

    /** Digital Wellbeing's per-app screen (app timer), Android 10+. Real, system-enforced limits. */
    private fun appTimerIntent(app: InstalledApp): Intent? {
        if (Build.VERSION.SDK_INT < 29 || !isMyUser(app.user)) return null
        return Intent(Settings.ACTION_APP_USAGE_SETTINGS).putExtra(Intent.EXTRA_PACKAGE_NAME, app.packageName)
    }

    fun canOpenAppTimer(context: Context, app: InstalledApp): Boolean =
        appTimerIntent(app)?.let { canResolve(context, it) } ?: false

    fun openAppTimer(context: Context, app: InstalledApp) {
        appTimerIntent(app)?.let { safeStart(context, it) }
    }

    fun openAlarms(context: Context) {
        safeStart(context, Intent(AlarmClock.ACTION_SHOW_ALARMS))
    }

    fun openCalendar(context: Context) {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            .appendPath(System.currentTimeMillis().toString()).build()
        if (!safeStart(context, Intent(Intent.ACTION_VIEW, uri), showError = false)) {
            safeStart(context, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
        }
    }

    fun pickWallpaper(context: Context) {
        val chooser = Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), context.getString(R.string.action_wallpaper))
        safeStart(context, chooser)
    }

    fun openAccessibilitySettings(context: Context) {
        safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openLauncherSettings(context: Context) {
        safeStart(context, Intent(context, app.haven.launcher.ui.settings.SettingsActivity::class.java))
    }

    // ---- Default home app ----

    fun isDefaultLauncher(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /**
     * Ask to become the home app. The role dialog is the cleanest UX, but Android stops showing
     * it after the user declines twice, so fall back to the system's home-app settings.
     */
    fun requestDefaultLauncher(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = activity.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode)
                    return
                } catch (e: ActivityNotFoundException) {
                    // Fall through to settings.
                }
            }
        }
        openHomeSettings(activity)
    }

    fun openHomeSettings(context: Context) {
        if (!safeStart(context, Intent(Settings.ACTION_HOME_SETTINGS), showError = false)) {
            safeStart(context, Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---- Helpers ----

    fun canResolve(context: Context, intent: Intent): Boolean =
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null

    /** Package that handles an intent by default (used to pre-fill the home screen). */
    fun defaultHandlerPackage(context: Context, intent: Intent): String? {
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?: return null
        // "android" means the chooser: no default picked yet, so don't guess.
        return info.packageName.takeIf { it != "android" }
    }

    fun safeStart(context: Context, intent: Intent, showError: Boolean = true): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't start $intent", e)
        if (showError) toast(context, context.getString(R.string.error_no_app))
        false
    }

    fun toast(context: Context, message: CharSequence) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    fun isMyUser(user: UserHandle) = user == Process.myUserHandle()
}
