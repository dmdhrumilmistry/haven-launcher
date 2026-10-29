package app.haven.launcher.widgets

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import app.haven.launcher.data.HomeWidget
import kotlin.math.abs
import kotlin.math.ceil

/** Height of one widget row on the home screen, roughly one icon cell like stock Android. */
const val WIDGET_ROW_DP = 88
const val MAX_WIDGET_ROWS = 6

/**
 * The app-wide widget host. One host for the whole process: widget ids belong to it, so the home
 * screen and the "add widget" confirmation share it.
 */
class HavenWidgetHost(context: Context) : AppWidgetHost(context.applicationContext, HOST_ID) {
    private val appContext = context.applicationContext
    val manager: AppWidgetManager = AppWidgetManager.getInstance(appContext)

    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        HavenWidgetHostView(context)

    fun infoFor(id: Int): AppWidgetProviderInfo? = try {
        manager.getAppWidgetInfo(id)
    } catch (e: Exception) {
        null
    }

    /** Every home-screen widget from every profile (personal, work, private space). */
    fun allProviders(): List<AppWidgetProviderInfo> {
        val launcherApps = appContext.getSystemService(LauncherApps::class.java)
        return launcherApps.profiles.flatMap { user ->
            try {
                manager.getInstalledProvidersForProfile(user)
            } catch (e: Exception) {
                emptyList()
            }
        }.filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
    }

    fun safeStartListening() = try {
        startListening()
    } catch (e: Exception) {
        // Seen on some OEM builds when the system is still booting; widgets just won't update yet.
        Log.w(TAG, "startListening failed", e)
    }

    fun safeStopListening() = try {
        stopListening()
    } catch (e: Exception) {
        Log.w(TAG, "stopListening failed", e)
    }

    /**
     * Frees widget ids we hold but no longer show, e.g. left over from a crash mid-setup or a
     * settings reset. Otherwise they'd keep receiving updates and draining battery forever.
     */
    fun deleteOrphans(keep: Collection<HomeWidget>) {
        if (Build.VERSION.SDK_INT < 26) return
        val wanted = keep.map { it.id }.toSet()
        try {
            appWidgetIds.filter { it !in wanted }.forEach(::deleteAppWidgetId)
        } catch (e: Exception) {
            Log.w(TAG, "Orphan cleanup failed", e)
        }
    }

    companion object {
        private const val TAG = "HavenWidgets"
        const val HOST_ID = 0x4856
    }
}

/** Default rows for a new widget, from what the provider asks for. */
fun defaultRows(context: Context, info: AppWidgetProviderInfo): Int {
    val density = context.resources.displayMetrics.density
    val cells = if (Build.VERSION.SDK_INT >= 31 && info.targetCellHeight > 0) info.targetCellHeight
    else ceil((info.minHeight / density + 16) / WIDGET_ROW_DP).toInt()
    return cells.coerceIn(1, MAX_WIDGET_ROWS)
}

fun minRows(context: Context, info: AppWidgetProviderInfo): Int {
    val density = context.resources.displayMetrics.density
    val minPx = if (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0 && info.minResizeHeight > 0) {
        info.minResizeHeight
    } else info.minHeight
    return ceil((minPx / density) / WIDGET_ROW_DP).toInt().coerceIn(1, MAX_WIDGET_ROWS)
}

fun canResizeVertically(info: AppWidgetProviderInfo) = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0

fun widgetCellSize(context: Context, info: AppWidgetProviderInfo): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
        return info.targetCellWidth to info.targetCellHeight
    }
    val density = context.resources.displayMetrics.density
    // The classic formula from the Android widget design guidelines: 70n - 30 dp.
    fun cells(px: Int) = ((px / density + 30) / 70).toInt().coerceAtLeast(1)
    return cells(info.minWidth) to cells(info.minHeight)
}

/**
 * Widget view that recognises a long-press anywhere on it (even over buttons inside the widget)
 * so people can always reach "Remove" and "Resize", exactly like stock launchers.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class HavenWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null
        set(value) {
            field = value
            ViewCompat.replaceAccessibilityAction(
                this, AccessibilityActionCompat.ACTION_LONG_CLICK, null,
            ) { _, _ -> value?.invoke(); value != null }
        }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val longPressRunnable = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                longPressed = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPressRunnable)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN ->
                removeCallbacks(longPressRunnable)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // After a long-press, take over the gesture so the widget's button doesn't also fire.
        if (longPressed) return true
        track(ev)
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) longPressed = false
        // Keep receiving the gesture even over non-clickable parts, so we can see the finger lift.
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        longPressed = false
        super.onDetachedFromWindow()
    }
}
