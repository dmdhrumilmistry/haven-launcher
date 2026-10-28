package app.haven.launcher.ui.home

import android.app.AlarmManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import app.haven.launcher.data.ClockFormat
import java.util.Date
import java.util.Locale

/** Current time, updated every minute and whenever the clock or time zone changes. */
@Composable
fun rememberCurrentTime(): State<Long> {
    val context = LocalContext.current
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                now.longValue = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        now.longValue = System.currentTimeMillis()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return now
}

/** Next alarm time if one is set, so the date line can show it. */
@Composable
fun rememberNextAlarm(): State<Long?> {
    val context = LocalContext.current
    val alarm = remember { mutableStateOf<Long?>(null) }
    DisposableEffect(context) {
        val am = context.getSystemService(AlarmManager::class.java)
        fun refresh() {
            alarm.value = try {
                am?.nextAlarmClock?.triggerTime
            } catch (e: Exception) {
                null
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refresh()
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        refresh()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return alarm
}

data class ClockText(val time: String, val date: String, val alarm: String?)

@Composable
fun formatClock(now: Long, alarm: Long?, format: ClockFormat): ClockText {
    val context = LocalContext.current
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val use24 = when (format) {
        ClockFormat.SYSTEM -> DateFormat.is24HourFormat(context)
        ClockFormat.H12 -> false
        ClockFormat.H24 -> true
    }
    // Drop AM/PM for a cleaner clock; the date line and the light/dark of day carry that context.
    val timePattern = remember(locale, use24) {
        DateFormat.getBestDateTimePattern(locale, if (use24) "Hmm" else "hmm")
            .replace("a", "").replace(" ", " ").trim()
    }
    val datePattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd") }
    val date = Date(now)
    val alarmText = alarm?.takeIf { it - now in 0..ALARM_WINDOW_MS }?.let {
        DateFormat.format(timePattern, it).toString()
    }
    return ClockText(
        time = DateFormat.format(timePattern, date).toString(),
        date = DateFormat.format(datePattern, date).toString(),
        alarm = alarmText,
    )
}

private const val ALARM_WINDOW_MS = 18L * 60 * 60 * 1000

/**
 * Whether the wallpaper is light enough that dark text reads better. Follows the system's own
 * hint where available so we match the status bar and Quick Settings.
 */
@Composable
fun rememberWallpaperPrefersDarkText(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(false) }
    DisposableEffect(context) {
        if (Build.VERSION.SDK_INT < 27) return@DisposableEffect onDispose { }
        val wm = WallpaperManager.getInstance(context)
        fun evaluate() {
            state.value = try {
                val colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                when {
                    colors == null -> false
                    Build.VERSION.SDK_INT >= 31 -> colors.colorHints and HINT_SUPPORTS_DARK_TEXT != 0
                    else -> luminance(colors.primaryColor.toArgb()) > 0.6
                }
            } catch (e: Exception) {
                false
            }
        }
        val listener = WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) evaluate()
        }
        evaluate()
        wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { wm.removeOnColorsChangedListener(listener) }
    }
    return state
}

private const val HINT_SUPPORTS_DARK_TEXT = 1

private fun luminance(argb: Int): Double {
    fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(AndroidColor.red(argb)) + 0.7152 * channel(AndroidColor.green(argb)) +
        0.0722 * channel(AndroidColor.blue(argb))
}
