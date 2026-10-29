package app.haven.launcher.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.haven.launcher.R
import app.haven.launcher.data.HomeWidget
import app.haven.launcher.haven
import app.haven.launcher.system.SystemActions

/**
 * Everything the home screen needs to add, show and manage widgets. Owned by the home activity
 * because binding and configuring widgets go through activity results.
 *
 * Adding a widget is a small state machine, any step of which the user can cancel:
 * allocate id -> bind (system permission dialog if needed) -> configure (if the widget has a
 * setup screen) -> show. A cancelled step frees the id so nothing is left running in the background.
 */
class HomeWidgets(private val activity: ComponentActivity) {
    private val app = activity.haven
    val host: HavenWidgetHost = app.widgetHost
    private val views = HashMap<Int, HavenWidgetHostView>()

    private var pendingId = NO_ID
    private var pendingInfo: AppWidgetProviderInfo? = null

    private val bindLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingId
        val info = pendingInfo ?: host.infoFor(id)
        if (id == NO_ID) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK && info != null) configureOrAdd(id, info) else cancelPending()
    }

    // ---- Showing ----

    /** The view for a widget, reused across drawer open/close so widgets don't flicker or reload. */
    fun viewFor(id: Int): HavenWidgetHostView? {
        views[id]?.let { cached ->
            (cached.parent as? ViewGroup)?.removeView(cached)
            return cached
        }
        val info = host.infoFor(id) ?: return null
        val view = try {
            host.createView(activity, id, info) as? HavenWidgetHostView
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't create widget view $id", e)
            null
        } ?: return null
        views[id] = view
        return view
    }

    /** Tells the widget how much room it has so it can pick its best layout. */
    fun applySize(view: HavenWidgetHostView, widthPx: Int, heightPx: Int) {
        val density = activity.resources.displayMetrics.density
        val w = widthPx / density
        val h = heightPx / density
        if (w <= 0 || h <= 0) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
            } else {
                @Suppress("DEPRECATION")
                view.updateAppWidgetSize(null, w.toInt(), h.toInt(), w.toInt(), h.toInt())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't resize widget", e)
        }
    }

    // ---- Adding ----

    fun add(info: AppWidgetProviderInfo) {
        cancelPending()
        val id = try {
            host.allocateAppWidgetId()
        } catch (e: Exception) {
            SystemActions.toast(activity, activity.getString(R.string.error_widget))
            return
        }
        pendingId = id
        pendingInfo = info
        val bound = try {
            host.manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (e: Exception) {
            false
        }
        if (bound) {
            configureOrAdd(id, info)
        } else {
            // First time for this app: Android asks the user to allow Haven to show its widgets.
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            try {
                bindLauncher.launch(intent)
            } catch (e: Exception) {
                cancelPending()
                SystemActions.toast(activity, activity.getString(R.string.error_widget))
            }
        }
    }

    private fun configureOrAdd(id: Int, info: AppWidgetProviderInfo) {
        val optional = Build.VERSION.SDK_INT >= 28 &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure != null && !optional) {
            try {
                host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Configure screen failed to open", e)
                cancelPending()
                SystemActions.toast(activity, activity.getString(R.string.error_widget))
                return
            }
        }
        finishAdd(id, info)
    }

    private fun finishAdd(id: Int, info: AppWidgetProviderInfo) {
        app.settings.addWidget(HomeWidget(id, defaultRows(activity, info)))
        pendingId = NO_ID
        pendingInfo = null
    }

    private fun cancelPending() {
        if (pendingId != NO_ID) {
            try {
                host.deleteAppWidgetId(pendingId)
            } catch (e: Exception) {
                // Already gone.
            }
        }
        pendingId = NO_ID
        pendingInfo = null
    }

    /** Forwarded from the activity. Returns true if it was ours. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        when (requestCode) {
            REQUEST_CONFIGURE -> {
                val id = pendingId
                val info = pendingInfo ?: host.infoFor(id)
                if (resultCode == Activity.RESULT_OK && id != NO_ID && info != null) finishAdd(id, info) else cancelPending()
                return true
            }
            REQUEST_RECONFIGURE -> return true // The widget updates itself either way.
        }
        return false
    }

    fun saveState(out: Bundle) {
        out.putInt(STATE_PENDING, pendingId)
    }

    fun restoreState(saved: Bundle?) {
        pendingId = saved?.getInt(STATE_PENDING, NO_ID) ?: NO_ID
    }

    // ---- Managing ----

    fun remove(id: Int) {
        app.settings.removeWidget(id)
        views.remove(id)
        try {
            host.deleteAppWidgetId(id)
        } catch (e: Exception) {
            // Already gone.
        }
    }

    fun canReconfigure(id: Int): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val info = host.infoFor(id) ?: return false
        return info.configure != null && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
    }

    fun reconfigure(id: Int) {
        try {
            host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_RECONFIGURE, null)
        } catch (e: Exception) {
            SystemActions.toast(activity, activity.getString(R.string.error_widget))
        }
    }

    fun onStart() = host.safeStartListening()

    fun onStop() = host.safeStopListening()

    companion object {
        private const val TAG = "HavenWidgets"
        private const val NO_ID = -1
        private const val REQUEST_CONFIGURE = 0x5701
        private const val REQUEST_RECONFIGURE = 0x5702
        private const val STATE_PENDING = "pending_widget"
    }
}
