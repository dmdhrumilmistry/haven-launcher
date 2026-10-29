package app.haven.launcher.data

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.os.UserHandle

/** Stable, persistable identity of a launchable activity in a given profile. */
object AppKey {
    fun of(component: ComponentName, userSerial: Long) = "${component.flattenToString()}#$userSerial"

    fun parse(key: String): Pair<ComponentName, Long>? {
        val hash = key.lastIndexOf('#')
        if (hash <= 0) return null
        val component = ComponentName.unflattenFromString(key.substring(0, hash)) ?: return null
        val serial = key.substring(hash + 1).toLongOrNull() ?: return null
        return component to serial
    }
}

enum class ProfileType { PERSONAL, WORK, PRIVATE, CLONE }

data class Profile(
    val user: UserHandle,
    val serial: Long,
    val type: ProfileType,
    /** Work apps paused, or private space locked. */
    val isQuiet: Boolean,
    /** Android 15 setting "Hide private space when it's locked". */
    val hiddenWhenLocked: Boolean = false,
) {
    /** Whether this profile should appear at all in the UI right now. */
    val isVisible get() = !(type == ProfileType.PRIVATE && isQuiet && hiddenWhenLocked)
}

/** An app exactly as the system reports it. Customisations (labels, hiding) are applied later. */
data class InstalledApp(
    val key: String,
    val component: ComponentName,
    val user: UserHandle,
    val profile: Profile,
    val systemLabel: String,
    val isSystemApp: Boolean,
    val isSuspended: Boolean,
    /** When the app was first installed, for the "new" dot. */
    val installedAt: Long,
    val info: LauncherActivityInfo,
) {
    val packageName: String get() = component.packageName
}

/** An app ready to be displayed: with the user's label, search keys and alphabet section. */
data class AppItem(
    val app: InstalledApp,
    val label: String,
    val isHidden: Boolean,
    val section: String,
    val search: SearchKey,
) {
    val key get() = app.key
    val profileType get() = app.profile.type
    /** Paused work app: shown greyed out, tapping offers to turn work apps back on. */
    val isPaused get() = app.profile.isQuiet
}

/** Everything the system tells us about installed apps, in one snapshot. */
data class AppsSnapshot(
    val loaded: Boolean = false,
    val profiles: List<Profile> = emptyList(),
    val apps: List<InstalledApp> = emptyList(),
    /** Pinned shortcuts keyed by [HomeItem.Shortcut.id]. Empty if we're not the default launcher. */
    val pinnedShortcuts: Map<String, android.content.pm.ShortcutInfo> = emptyMap(),
    val canUseShortcuts: Boolean = false,
)
