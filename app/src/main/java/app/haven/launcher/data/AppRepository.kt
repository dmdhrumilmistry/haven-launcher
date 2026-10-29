package app.haven.launcher.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for installed apps across every profile (personal, work, private space,
 * clones). Listens to the system for any change and reloads in the background, batching bursts
 * (e.g. an app store updating 20 apps) into one reload.
 */
class AppRepository(
    private val context: Context,
    scope: CoroutineScope,
    private val iconCache: IconCache,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val myUser = Process.myUserHandle()

    private val _snapshot = MutableStateFlow(AppsSnapshot())
    val snapshot: StateFlow<AppsSnapshot> = _snapshot.asStateFlow()

    private val reloadSignal = Channel<Unit>(Channel.CONFLATED)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            changed(*packageNames)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            changed(*packageNames)
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = changed()
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = changed()
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) =
            changed()
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Locale changes rename apps; profile changes add/remove/pause whole sets of apps.
            if (intent.action == Intent.ACTION_LOCALE_CHANGED) iconCache.clear()
            reload()
        }
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
            if (Build.VERSION.SDK_INT >= 34) {
                // Covers private space and clone profiles, which don't send MANAGED_PROFILE_*.
                addAction(Intent.ACTION_PROFILE_ADDED)
                addAction(Intent.ACTION_PROFILE_REMOVED)
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_PROFILE_ACCESSIBLE)
                addAction(Intent.ACTION_PROFILE_INACCESSIBLE)
            }
        }
        // These are protected system broadcasts; NOT_EXPORTED would block them.
        ContextCompat.registerReceiver(context, systemReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        scope.launch(Dispatchers.IO) {
            for (signal in reloadSignal) {
                delay(RELOAD_BATCH_MS)
                try {
                    _snapshot.value = loadSnapshot()
                } catch (e: Exception) {
                    // Never let a misbehaving package take down the home screen.
                    Log.e(TAG, "Failed to load apps", e)
                    _snapshot.value = _snapshot.value.copy(loaded = true)
                }
            }
        }
        reload()
    }

    fun reload() {
        reloadSignal.trySend(Unit)
    }

    private fun changed(vararg packages: String) {
        packages.forEach(iconCache::invalidatePackage)
        reload()
    }

    private fun loadSnapshot(): AppsSnapshot {
        val profiles = launcherApps.profiles.mapNotNull(::profileFor)
        val apps = profiles.flatMap { profile ->
            // Locked private space: the system returns nothing useful and launching would fail.
            if (profile.type == ProfileType.PRIVATE && profile.isQuiet) return@flatMap emptyList()
            val activities = try {
                launcherApps.getActivityList(null, profile.user)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't list apps for ${profile.type}", e)
                emptyList()
            }
            activities.mapNotNull { info ->
                val component = info.componentName
                // Our own settings entry is reachable from the drawer's gear button instead.
                if (component.packageName == context.packageName) return@mapNotNull null
                val appInfo: ApplicationInfo = info.applicationInfo
                val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                    appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                InstalledApp(
                    key = AppKey.of(component, profile.serial),
                    component = component,
                    user = profile.user,
                    profile = profile,
                    systemLabel = info.label?.toString()?.trim().orEmpty().ifEmpty { component.packageName },
                    isSystemApp = isSystem,
                    isSuspended = appInfo.flags and ApplicationInfo.FLAG_SUSPENDED != 0,
                    installedAt = info.firstInstallTime,
                    info = info,
                )
            }
        }.distinctBy { it.key }

        val canUseShortcuts = hasShortcutPermission()
        val pinned = if (canUseShortcuts) loadPinnedShortcuts(profiles) else emptyMap()
        return AppsSnapshot(
            loaded = true,
            profiles = profiles,
            apps = apps,
            pinnedShortcuts = pinned,
            canUseShortcuts = canUseShortcuts,
        )
    }

    private fun profileFor(user: UserHandle): Profile? {
        val serial = userManager.getSerialNumberForUser(user)
        if (serial < 0) return null // User is being removed.
        val type = profileTypeOf(user)
        val quiet = user != myUser && try {
            userManager.isQuietModeEnabled(user)
        } catch (e: Exception) {
            false
        }
        val hiddenWhenLocked = type == ProfileType.PRIVATE && try {
            Settings.Secure.getInt(context.contentResolver, "hide_privatespace_entry_point", 0) == 1
        } catch (e: Exception) {
            false // Not readable on this build; showing the locked entry is the safe default.
        }
        return Profile(user, serial, type, quiet, hiddenWhenLocked)
    }

    @SuppressLint("NewApi")
    private fun profileTypeOf(user: UserHandle): ProfileType {
        if (user == myUser) return ProfileType.PERSONAL
        if (Build.VERSION.SDK_INT >= 35) {
            val type = try {
                launcherApps.getLauncherUserInfo(user)?.userType
            } catch (e: Exception) {
                null
            }
            return when (type) {
                "android.os.usertype.profile.PRIVATE" -> ProfileType.PRIVATE
                "android.os.usertype.profile.CLONE" -> ProfileType.CLONE
                else -> ProfileType.WORK
            }
        }
        // Before Android 15 the only profiles a launcher can see are managed (work) profiles,
        // plus OEM "dual app" clones that behave like one.
        return ProfileType.WORK
    }

    private fun loadPinnedShortcuts(profiles: List<Profile>): Map<String, ShortcutInfo> {
        val result = HashMap<String, ShortcutInfo>()
        val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        for (profile in profiles) {
            if (profile.isQuiet) continue
            val shortcuts = try {
                launcherApps.getShortcuts(query, profile.user)
            } catch (e: Exception) {
                null // Profile locked or we lost the default-launcher role mid-flight.
            } ?: continue
            for (s in shortcuts) {
                result[HomeItem.Shortcut(s.`package`, s.id, profile.serial, "").id] = s
            }
        }
        return result
    }

    fun hasShortcutPermission(): Boolean = try {
        launcherApps.hasShortcutHostPermission()
    } catch (e: Exception) {
        false
    }

    // ---- Queries used by the UI ----

    fun userForSerial(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)

    fun serialFor(user: UserHandle): Long = userManager.getSerialNumberForUser(user)

    /** App shortcuts for the long-press menu, most relevant first. */
    fun shortcutsFor(app: InstalledApp): List<ShortcutInfo> {
        if (!hasShortcutPermission() || app.profile.isQuiet) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setActivity(app.component)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
            )
        val list = try {
            launcherApps.getShortcuts(query, app.user)
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return list
            .filter { it.isEnabled }
            .sortedWith(compareBy<ShortcutInfo>({ !it.isDeclaredInManifest }, { it.rank }))
            .take(MAX_SHORTCUTS)
    }

    /**
     * Keeps the system's pinned set for a package in sync with what's on our home screen, so the
     * owning app knows when a shortcut has been removed.
     */
    fun syncPinnedShortcuts(packageName: String, user: UserHandle, keepIds: List<String>) {
        if (!hasShortcutPermission()) return
        try {
            launcherApps.pinShortcuts(packageName, keepIds, user)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't update pinned shortcuts for $packageName", e)
        }
        reload()
    }

    /**
     * Pause/resume work apps or lock/unlock private space. Unlocking may show the system's
     * credential screen. Returns false if the system refused (e.g. we're not the default launcher).
     */
    fun requestQuietMode(profile: Profile, enableQuiet: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        return try {
            userManager.requestQuietModeEnabled(enableQuiet, profile.user)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Quiet mode change refused", e)
            false
        } finally {
            reload()
        }
    }

    fun findApp(key: String): InstalledApp? = snapshot.value.apps.firstOrNull { it.key == key }

    fun findAppByPackage(packageName: String, user: UserHandle = myUser): InstalledApp? =
        snapshot.value.apps.firstOrNull { it.packageName == packageName && it.user == user }

    fun componentFor(key: String): ComponentName? = AppKey.parse(key)?.first

    companion object {
        private const val TAG = "HavenApps"
        private const val RELOAD_BATCH_MS = 150L
        private const val MAX_SHORTCUTS = 5
    }
}
