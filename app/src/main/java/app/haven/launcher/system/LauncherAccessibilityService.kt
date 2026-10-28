package app.haven.launcher.system

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Deliberately minimal: it never reads screen content and listens to no events. It only exists
 * because Android offers no other way for a launcher to lock the screen without disabling
 * fingerprint/face unlock (which the device-admin approach would do).
 */
class LauncherAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: LauncherAccessibilityService? = null

        val isRunning: Boolean get() = instance != null

        /** True if the user switched the service on, even if the system hasn't bound it yet. */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, LauncherAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }

        fun lockScreen(): Boolean {
            if (Build.VERSION.SDK_INT < 28) return false
            return instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
        }

        fun openNotifications(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false
    }
}
