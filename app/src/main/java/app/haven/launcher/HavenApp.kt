package app.haven.launcher

import android.app.Application
import app.haven.launcher.data.AppRepository
import app.haven.launcher.data.IconCache
import app.haven.launcher.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Holds the long-lived repositories so the home screen and settings always agree. */
class HavenApp : Application() {
    val appScope = CoroutineScope(SupervisorJob())
    lateinit var settings: SettingsRepository
        private set
    lateinit var icons: IconCache
        private set
    lateinit var apps: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        icons = IconCache(this)
        apps = AppRepository(this, appScope, icons)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Icons are cheap to re-render; give memory back only when the system is really short,
        // otherwise returning home would flash empty icons.
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_MODERATE || level == TRIM_MEMORY_RUNNING_CRITICAL) icons.clear()
    }
}

val android.content.Context.haven: HavenApp get() = applicationContext as HavenApp
