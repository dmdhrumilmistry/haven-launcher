package app.haven.launcher.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.core.net.toUri
import android.os.ParcelFileDescriptor
import android.os.UserManager
import android.util.Log
import app.haven.launcher.data.LauncherSettings
import app.haven.launcher.focus.NotificationHold
import app.haven.launcher.focus.Pauses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/** Whether Haven can freeze apps right now, and if not, the next step for the person. */
enum class FreezeAvailability { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

/**
 * Really stops paused apps: suspends them (they can't open, and Android hides their notifications)
 * and force-stops them (no background work, no pushes or alarms until they're opened again).
 *
 * Android only allows that with shell or root rights, so it goes through Shizuku, which the person
 * installs and starts themselves. Without it, pausing stays the gentle Haven-only speed bump.
 *
 * Haven remembers exactly which packages it froze (by "package#userSerial") and only ever
 * unfreezes those, so it never undoes a suspension made by Digital Wellbeing or an admin.
 */
class AppFreezer(
    private val context: Context,
    private val scope: CoroutineScope,
    settings: StateFlow<LauncherSettings>,
) {
    private val prefs = context.getSharedPreferences("freezer", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _availability = MutableStateFlow(FreezeAvailability.NOT_INSTALLED)
    val availability: StateFlow<FreezeAvailability> = _availability.asStateFlow()

    private val _frozen = MutableStateFlow(prefs.getStringSet(KEY_FROZEN, null).orEmpty().toSet())
    /** Packages Haven has frozen, as "package#userSerial" (the same keys as notification badges). */
    val frozen: StateFlow<Set<String>> = _frozen.asStateFlow()

    /** Opened with "Open anyway": left alone until the person is back on the home screen. */
    private val thawed = MutableStateFlow<Set<String>>(emptySet())

    /** Packages the system refused to freeze, so we don't retry (and fail) every minute. */
    private val refused = HashSet<String>()

    init {
        Shizuku.addBinderReceivedListenerSticky { refreshAvailability() }
        Shizuku.addBinderDeadListener { refreshAvailability() }
        Shizuku.addRequestPermissionResultListener { _, _ -> refreshAvailability() }
        refreshAvailability()

        val clock = flow {
            while (true) {
                val now = System.currentTimeMillis()
                emit(now)
                delay(60_000 - now % 60_000 + 50)
            }
        }
        scope.launch {
            combine(settings, clock, _availability, thawed) { s, now, available, thawedNow ->
                val wanted = if (!s.freezePausedApps) emptySet()
                else NotificationHold.pausedPackages(Pauses.compute(s.pausedApps, s.pauseSchedules, now)).keys
                    .filterTo(HashSet()) { it !in thawedNow && !isExcluded(it) }
                available to wanted
            }.distinctUntilChanged().collect { (available, wanted) ->
                if (available == FreezeAvailability.READY) reconcile(wanted)
            }
        }
    }

    // ---- Shizuku setup ----

    fun refreshAvailability() {
        _availability.value = try {
            when {
                // Sui (Shizuku inside Magisk) has no manager app but still answers.
                !Shizuku.pingBinder() -> if (isShizukuInstalled()) FreezeAvailability.NOT_RUNNING else FreezeAvailability.NOT_INSTALLED
                Shizuku.isPreV11() -> FreezeAvailability.NOT_RUNNING
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> FreezeAvailability.NEEDS_PERMISSION
                else -> FreezeAvailability.READY
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't check Shizuku", e)
            FreezeAvailability.NOT_RUNNING
        }
    }

    private fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Asks Shizuku to allow Haven. If the person said "never", opens Shizuku to change it there. */
    fun requestPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) openShizuku() else Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't ask Shizuku", e)
            openShizuku()
        }
    }

    fun openShizuku() {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) SystemActions.safeStart(context, intent) else openDownloadPage()
    }

    fun openDownloadPage() {
        SystemActions.safeStart(context, Intent(Intent.ACTION_VIEW, SHIZUKU_DOWNLOAD.toUri()))
    }

    // ---- Freezing ----

    /** "Open anyway" on a frozen app: unfreeze it now; it's frozen again once you're back home. */
    suspend fun thawForLaunch(packageKey: String) {
        thawed.value = thawed.value + packageKey
        if (packageKey !in _frozen.value) return
        withContext(Dispatchers.IO) {
            mutex.withLock { if (thaw(packageKey)) record(_frozen.value - packageKey) }
        }
    }

    /** Back on the home screen: apps opened anyway are frozen again if still paused. */
    fun onHomeResumed() {
        refreshAvailability()
        if (thawed.value.isNotEmpty()) thawed.value = emptySet()
    }

    private suspend fun reconcile(wanted: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            refused.retainAll(wanted)
            var frozenNow = _frozen.value
            for (key in frozenNow - wanted) if (thaw(key)) frozenNow = frozenNow - key
            for (key in wanted - frozenNow - refused) {
                if (freeze(key)) frozenNow = frozenNow + key else refused += key
            }
            record(frozenNow)
        }
    }

    private fun record(keys: Set<String>) {
        if (keys == _frozen.value) return
        _frozen.value = keys
        prefs.edit { putStringSet(KEY_FROZEN, keys) }
    }

    private fun freeze(packageKey: String): Boolean {
        val (pkg, user) = target(packageKey) ?: return false
        // Suspend first so nothing can restart it in between, then stop everything it runs.
        return shell("pm suspend --user $user $pkg && am force-stop --user $user $pkg")
    }

    private fun thaw(packageKey: String): Boolean {
        val (pkg, user) = target(packageKey) ?: return true // Profile gone: nothing left to unfreeze.
        if (shell("pm unsuspend --user $user $pkg")) return true
        // Uninstalled while frozen: there's nothing to undo, so forget it.
        return !isInstalled(pkg, user)
    }

    private fun isInstalled(pkg: String, userId: Int): Boolean =
        shell("pm path --user $userId $pkg")

    /** Package and Android user id for a "package#serial" key, validated so it's safe in a command. */
    private fun target(packageKey: String): Pair<String, Int>? {
        val hash = packageKey.lastIndexOf('#')
        if (hash <= 0) return null
        val pkg = packageKey.substring(0, hash)
        val serial = packageKey.substring(hash + 1).toLongOrNull() ?: return null
        if (!PACKAGE_NAME.matches(pkg)) return null
        val user = context.getSystemService(UserManager::class.java).getUserForSerialNumber(serial) ?: return null
        // UserHandle.getIdentifier() isn't public API; its hashCode() is that same id.
        return pkg to user.hashCode()
    }

    private fun isExcluded(packageKey: String): Boolean {
        val pkg = packageKey.substringBeforeLast('#')
        return pkg == context.packageName || pkg == SHIZUKU_PACKAGE
    }

    /** Runs a command as Shizuku's user (shell or root). True when it exits successfully. */
    private fun shell(command: String): Boolean {
        val binder = Shizuku.getBinder() ?: return false
        return try {
            val process = IShizukuService.Stub.asInterface(binder).newProcess(arrayOf("sh", "-c", command), null, null)
            val error = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream).use { String(it.readBytes()) }
            ParcelFileDescriptor.AutoCloseInputStream(process.inputStream).use { it.readBytes() }
            val code = process.waitFor()
            process.destroy()
            if (code != 0) Log.w(TAG, "\"$command\" failed ($code): ${error.take(200)}")
            code == 0
        } catch (e: Exception) {
            Log.w(TAG, "\"$command\" failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "HavenFreezer"
        private const val KEY_FROZEN = "frozen"
        private const val REQUEST_CODE = 41
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
        private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$")
    }
}
