package app.haven.launcher.ui.home

import android.graphics.Rect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haven.launcher.R
import app.haven.launcher.data.ClockSize
import app.haven.launcher.data.HomeAlignment
import app.haven.launcher.data.HomePosition
import app.haven.launcher.data.HomeStyle
import app.haven.launcher.data.LauncherSettings
import app.haven.launcher.system.SystemActions
import app.haven.launcher.ui.HomeEntry
import app.haven.launcher.ui.components.AppIcon
import app.haven.launcher.ui.components.ShortcutIcon
import app.haven.launcher.ui.components.windowRect

/** Callbacks the home screen needs; kept in one place so the screen stays a pure layout. */
class HomeCallbacks(
    val onOpen: (HomeEntry, Rect?) -> Unit,
    val onLongPress: (HomeEntry) -> Unit,
    val onOpenDrawer: () -> Unit,
    val onDrag: (dy: Float) -> Unit,
    val onDragEnd: (velocityY: Float, totalDy: Float) -> Unit,
    val onDoubleTap: (() -> Unit)?,
    val onBackgroundLongPress: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    settings: LauncherSettings,
    entries: List<HomeEntry>,
    contentColor: Color,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val cb by rememberUpdatedState(callbacks)
    // The favorites list scrolls when it overflows. Whatever it can't use (the usual case) still
    // drives the swipe-up / swipe-down gestures, so they work anywhere on the screen.
    val leftoverDrag = remember {
        object : NestedScrollConnection {
            var total = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                total += available.y
                cb.onDrag(available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (total == 0f) return Velocity.Zero
                cb.onDragEnd(available.y, total)
                total = 0f
                return available
            }
        }
    }
    val horizontal = when (settings.homeAlignment) {
        HomeAlignment.START -> Alignment.Start
        HomeAlignment.CENTER -> Alignment.CenterHorizontally
        HomeAlignment.END -> Alignment.End
    }
    val textAlign = when (settings.homeAlignment) {
        HomeAlignment.START -> TextAlign.Start
        HomeAlignment.CENTER -> TextAlign.Center
        HomeAlignment.END -> TextAlign.End
    }
    // A soft shadow keeps text legible on busy wallpapers without needing a dim.
    val shadow = Shadow(
        color = if (contentColor.luminanceIsLight()) Color.Black.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.25f),
        blurRadius = 8f,
    )

    Box(
        modifier
            .fillMaxSize()
            .nestedScroll(leftoverDrag)
            .pointerInput(callbacks.onDoubleTap != null) {
                detectTapGestures(
                    onDoubleTap = if (callbacks.onDoubleTap != null) ({ cb.onDoubleTap?.invoke() }) else null,
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        cb.onBackgroundLongPress()
                    },
                )
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        total = 0f
                    },
                    onDragEnd = { cb.onDragEnd(tracker.calculateVelocity().y, total) },
                    onDragCancel = { cb.onDragEnd(0f, 0f) },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        total += dy
                        cb.onDrag(dy)
                    },
                )
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalAlignment = horizontal,
        ) {
            if (settings.showClock || settings.showDate) {
                Spacer(Modifier.height(32.dp))
                ClockBlock(settings, contentColor, shadow, textAlign, horizontal)
            }

            // Favorites take the remaining space, centred or anchored low for one-handed reach.
            // They scroll if someone pins more than fits.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 16.dp),
                contentAlignment = if (settings.homePosition == HomePosition.CENTER) Alignment.Center else Alignment.BottomCenter,
            ) {
                val favoritesModifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                if (entries.isEmpty()) {
                    Text(
                        stringResource(R.string.home_empty_hint),
                        color = contentColor.copy(alpha = 0.7f),
                        style = TextStyle(fontSize = 15.sp, shadow = shadow, textAlign = textAlign),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else when (settings.homeStyle) {
                    HomeStyle.DOCK -> FlowRow(
                        favoritesModifier,
                        horizontalArrangement = Arrangement.spacedBy(20.dp, horizontal),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        entries.forEach { entry -> DockItem(entry, settings, contentColor, shadow, cb) }
                    }
                    else -> Column(favoritesModifier, horizontalAlignment = horizontal) {
                        entries.forEach { entry -> ListItem(entry, settings, contentColor, shadow, cb) }
                    }
                }
            }

            // A visible, tappable way into the app drawer. Gestures alone aren't discoverable and
            // aren't usable with TalkBack or switch access.
            if (settings.showDrawerButton) {
                IconButton(
                    onClick = { cb.onOpenDrawer() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.open_app_drawer),
                        tint = contentColor.copy(alpha = 0.6f),
                    )
                }
            } else {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ClockBlock(
    settings: LauncherSettings,
    color: Color,
    shadow: Shadow,
    textAlign: TextAlign,
    horizontal: Alignment.Horizontal,
) {
    val context = LocalContext.current
    val now by rememberCurrentTime()
    val alarm by rememberNextAlarm()
    val text = formatClock(now, alarm, settings.clockFormat)
    val timeSize = when (settings.clockSize) {
        ClockSize.SMALL -> 44.sp
        ClockSize.MEDIUM -> 64.sp
        ClockSize.LARGE -> 88.sp
    }
    Column(horizontalAlignment = horizontal) {
        if (settings.showClock) {
            Text(
                text.time,
                color = color,
                style = TextStyle(fontSize = timeSize, fontWeight = FontWeight.Light, shadow = shadow, textAlign = textAlign),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.open_alarms),
                        onClick = { SystemActions.openAlarms(context) },
                    )
                    .semantics { heading() },
            )
        }
        if (settings.showDate) {
            val dateLine = if (text.alarm != null) stringResource(R.string.date_with_alarm, text.date, text.alarm) else text.date
            Text(
                dateLine,
                color = color.copy(alpha = 0.85f),
                style = TextStyle(fontSize = 17.sp, shadow = shadow, textAlign = textAlign),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.open_calendar),
                        onClick = { SystemActions.openCalendar(context) },
                    )
                    .padding(vertical = 2.dp),
            )
        }
    }
}

private fun HomeEntry.label(): String = when (this) {
    is HomeEntry.App -> item.label
    is HomeEntry.Shortcut -> (info.shortLabel ?: item.label).toString()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListItem(entry: HomeEntry, settings: LauncherSettings, color: Color, shadow: Shadow, cb: HomeCallbacks) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val haptics = LocalHapticFeedback.current
    val label = entry.label()
    val dimmed = entry is HomeEntry.App && (entry.item.isPaused || entry.item.app.isSuspended)
    val moreLabel = stringResource(R.string.more_options)
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                role = Role.Button,
                onLongClickLabel = moreLabel,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    cb.onLongPress(entry)
                },
                onClick = { cb.onOpen(entry, bounds) },
            )
            .semantics { customActions = listOf(CustomAccessibilityAction(moreLabel) { cb.onLongPress(entry); true }) }
            .onGloballyPositioned { bounds = it.windowRect() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (settings.homeStyle == HomeStyle.ICONS_AND_TEXT) {
            EntryIcon(entry, 32.dp)
            Spacer(Modifier.width(14.dp))
        }
        Text(
            label,
            color = color.copy(alpha = if (dimmed) 0.5f else 1f),
            style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal, shadow = shadow),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockItem(entry: HomeEntry, settings: LauncherSettings, color: Color, shadow: Shadow, cb: HomeCallbacks) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val haptics = LocalHapticFeedback.current
    val label = entry.label()
    val moreLabel = stringResource(R.string.more_options)
    Column(
        Modifier
            .width(settings.iconSize.dp.dp + 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                role = Role.Button,
                onClickLabel = label,
                onLongClickLabel = moreLabel,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    cb.onLongPress(entry)
                },
                onClick = { cb.onOpen(entry, bounds) },
            )
            .semantics { customActions = listOf(CustomAccessibilityAction(moreLabel) { cb.onLongPress(entry); true }) }
            .onGloballyPositioned { bounds = it.windowRect() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EntryIcon(entry, settings.iconSize.dp.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = color,
            style = TextStyle(fontSize = 12.sp, shadow = shadow, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EntryIcon(entry: HomeEntry, size: androidx.compose.ui.unit.Dp) {
    when (entry) {
        is HomeEntry.App -> AppIcon(entry.item, size)
        is HomeEntry.Shortcut -> ShortcutIcon(entry.info, size, Modifier.size(size))
    }
}

private fun Color.luminanceIsLight(): Boolean = (0.2126f * red + 0.7152f * green + 0.0722f * blue) > 0.5f
