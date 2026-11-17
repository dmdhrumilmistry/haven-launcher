package app.haven.launcher.focus

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.haven.launcher.R
import app.haven.launcher.data.AppItem
import app.haven.launcher.data.Search
import app.haven.launcher.system.HeldNotifications
import app.haven.launcher.system.SystemActions
import app.haven.launcher.ui.components.AppIcon
import app.haven.launcher.ui.components.badgeKey
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import java.time.DayOfWeek
import java.time.format.TextStyle

/** "5:00 PM", or "Tue 5:00 PM" when it isn't today. */
@Composable
fun formatUntil(millis: Long): String {
    val context = LocalContext.current
    val sameDay = DateUtils.isToday(millis)
    val flags = DateUtils.FORMAT_SHOW_TIME or
        (if (sameDay) 0 else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
    return DateUtils.formatDateTime(context, millis, flags)
}

@Composable
fun formatMinuteOfDay(minute: Int): String {
    val context = LocalContext.current
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minute / 60)
        set(java.util.Calendar.MINUTE, minute % 60)
    }
    return DateFormat.getTimeFormat(context).format(cal.time)
}

/** One-line description of why and until when an app is paused. */
@Composable
fun pauseDescription(state: PauseState): String = when (state) {
    is PauseState.Manual ->
        if (state.until == Pauses.FOREVER) stringResource(R.string.paused_until_unpaused)
        else stringResource(R.string.paused_until, formatUntil(state.until))
    is PauseState.Scheduled -> stringResource(
        R.string.paused_by_schedule,
        state.scheduleName.ifBlank { stringResource(R.string.schedule_default_name) },
        formatUntil(state.until),
    )
}

/**
 * What a pause does beyond greying the app out: freeze it completely (Shizuku), hold its
 * notifications (notification access), or neither, and why not.
 */
enum class HoldStatus { FROZEN, HELD, OFF, NEEDS_ACCESS, UNAVAILABLE }

/** Next time the clock shows [minuteOfDay]: today if it's still ahead, otherwise tomorrow. */
fun nextOccurrence(minuteOfDay: Int, nowMillis: Long): Long {
    val now = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), java.time.ZoneId.systemDefault())
    var at = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS).plusMinutes(minuteOfDay.toLong())
    if (!at.isAfter(now)) at = at.plusDays(1)
    return at.toInstant().toEpochMilli()
}

/**
 * "Pause for an hour / for today / until I unpause / until a time". Says plainly what pausing does
 * to notifications, and where to go to stop the app for real (Android doesn't let us).
 */
@Composable
fun PauseChooserDialog(
    app: AppItem,
    holdStatus: HoldStatus,
    onPause: (until: Long) -> Unit,
    onDismiss: () -> Unit,
    onAllowAccess: () -> Unit = {},
) {
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    var pickTime by remember { mutableStateOf(false) }
    val options = listOf(
        stringResource(R.string.pause_15_min) to now + 15 * 60_000L,
        stringResource(R.string.pause_30_min) to now + 30 * 60_000L,
        stringResource(R.string.pause_1_hour) to now + 60 * 60_000L,
        stringResource(R.string.pause_2_hours) to now + 2 * 60 * 60_000L,
        stringResource(R.string.pause_today) to Pauses.endOfToday(now),
        stringResource(R.string.pause_forever) to Pauses.FOREVER,
    )
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppIcon(app, 40.dp) },
        title = { Text(stringResource(R.string.pause_title, app.label), textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        when (holdStatus) {
                            HoldStatus.FROZEN -> R.string.pause_explainer_frozen
                            HoldStatus.HELD -> R.string.pause_explainer_held
                            else -> R.string.pause_explainer
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                Spacer(Modifier.height(8.dp))
                options.forEach { (label, until) ->
                    PauseOption(label, if (until == Pauses.FOREVER) null else formatUntil(until)) {
                        onPause(until)
                        onDismiss()
                    }
                }
                PauseOption(stringResource(R.string.pause_until_time), null) { pickTime = true }
                when (holdStatus) {
                    HoldStatus.NEEDS_ACCESS -> Text(
                        stringResource(R.string.pause_hold_needs_access),
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = onAllowAccess)
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    HoldStatus.OFF -> Text(
                        stringResource(R.string.pause_hold_off),
                        Modifier.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                    else -> Unit
                }
                // Frozen already means stopped and silent: no need for the manual routes.
                if (holdStatus != HoldStatus.FROZEN) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(stringResource(R.string.pause_background_hint), style = MaterialTheme.typography.bodySmall, color = muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        // The system's per-app notification screen only covers Haven's own profile.
                        if (SystemActions.isMyUser(app.app.user)) {
                            TextButton(onClick = { SystemActions.openAppNotificationSettings(context, app.app.packageName) }) {
                                Text(stringResource(R.string.action_notification_settings))
                            }
                        }
                        TextButton(onClick = { SystemActions.openAppInfo(context, app.app.component, app.app.user) }) {
                            Text(stringResource(R.string.action_force_stop))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (pickTime) {
        TimeDialog(
            // An hour from now, rounded down to the quarter hour.
            initialMinute = java.time.LocalTime.now().plusHours(1).let { it.hour * 60 + it.minute / 15 * 15 },
            onPick = { minute ->
                onPause(nextOccurrence(minute, System.currentTimeMillis()))
                onDismiss()
            },
            onDismiss = { pickTime = false },
        )
    }
}

@Composable
private fun PauseOption(label: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "3 notifications held until 5:00 PM", or null when nothing is held. */
@Composable
fun heldDescription(held: HeldNotifications?): String? {
    if (held == null || held.count <= 0) return null
    val count = LocalResources.current.getQuantityString(R.plurals.held_notifications, held.count, held.count)
    return stringResource(R.string.held_count_until, count, formatUntil(held.returnsAt))
}

/** Shown when tapping a paused app. A speed bump, not a wall. */
@Composable
fun AppPausedDialog(
    app: AppItem,
    state: PauseState,
    held: HeldNotifications?,
    frozen: Boolean,
    onOpenAnyway: () -> Unit,
    onUnpause: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppIcon(app, 40.dp) },
        title = { Text(stringResource(R.string.app_is_paused, app.label), textAlign = TextAlign.Center) },
        text = {
            Column {
                Text(pauseDescription(state))
                if (frozen) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.app_frozen_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                heldDescription(held)?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onOpenAnyway()
            }) { Text(stringResource(R.string.action_open_anyway)) }
        },
        dismissButton = {
            Row {
                // A schedule is edited in settings; only a one-off pause can be lifted here.
                if (state is PauseState.Manual) {
                    TextButton(onClick = {
                        onUnpause()
                        onDismiss()
                    }) { Text(stringResource(R.string.action_unpause)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** Settings page: every app paused right now (by hand or by a schedule), plus the schedules. */
@Composable
fun PausesPage(
    apps: List<AppItem>,
    pauses: Map<String, PauseState>,
    held: Map<String, HeldNotifications>,
    schedules: List<PauseSchedule>,
    onUnpause: (String) -> Unit,
    onToggleSchedule: (PauseSchedule, Boolean) -> Unit,
    onEditSchedule: (PauseSchedule) -> Unit,
    onAddSchedule: () -> Unit,
    notificationControls: @Composable () -> Unit = {},
    /** Pause a schedule's apps right away, until its end time: a one-tap focus session. */
    onStartNow: (PauseSchedule) -> Unit = {},
) {
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val paused = remember(pauses, byKey) {
        pauses.mapNotNull { (k, state) -> byKey[k]?.let { it to state } }.sortedBy { it.second.until }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text(
                stringResource(R.string.pauses_explainer),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { notificationControls() }
        item { Header(stringResource(R.string.pauses_now)) }
        if (paused.isEmpty()) {
            item { Muted(stringResource(R.string.pauses_none)) }
        }
        items(paused, key = { "p:" + it.first.key }) { (app, state) ->
            Row(Modifier.padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, 36.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        pauseDescription(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    heldDescription(held[app.badgeKey()])?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (state is PauseState.Manual) {
                    TextButton(onClick = { onUnpause(app.key) }) { Text(stringResource(R.string.action_unpause)) }
                }
            }
        }
        item { Header(stringResource(R.string.schedules)) }
        if (schedules.isEmpty()) {
            item { Muted(stringResource(R.string.schedules_none)) }
        }
        items(schedules, key = { "s:" + it.id }) { schedule ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onEditSchedule(schedule) }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(schedule.name.ifBlank { stringResource(R.string.schedule_default_name) }, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        scheduleSummary(schedule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (schedule.apps.isNotEmpty() && schedule.startMinute != schedule.endMinute) {
                    TextButton(onClick = { onStartNow(schedule) }) { Text(stringResource(R.string.schedule_start_now)) }
                }
                Switch(checked = schedule.enabled, onCheckedChange = { onToggleSchedule(schedule, it) })
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = onAddSchedule) { Text(stringResource(R.string.schedule_add)) }
            }
        }
    }
}

@Composable
private fun scheduleSummary(s: PauseSchedule): String {
    val locale = LocalConfiguration.current.locales[0]
    val days = when (s.days) {
        (1..7).toSet() -> stringResource(R.string.days_every_day)
        (1..5).toSet() -> stringResource(R.string.days_weekdays)
        setOf(6, 7) -> stringResource(R.string.days_weekends)
        else -> s.days.sorted().joinToString(" ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
    }
    val apps = LocalResources.current.getQuantityString(R.plurals.schedule_apps, s.apps.size, s.apps.size)
    return "$days · ${formatMinuteOfDay(s.startMinute)} - ${formatMinuteOfDay(s.endMinute)} · $apps"
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Muted(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Edit one schedule: name, days, start and end time, and which apps it pauses. Saves as you go. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditor(
    schedule: PauseSchedule,
    apps: List<AppItem>,
    onChange: (PauseSchedule) -> Unit,
    onDelete: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    var editingStart by remember { mutableStateOf<Boolean?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    val visible = remember(apps, filter) {
        apps.filter { !it.isHidden && it.app.profile.isVisible }
            .let { list -> if (filter.isBlank()) list else list.filter { Search.score(it.search, filter) > 0 } }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            OutlinedTextField(
                value = schedule.name,
                onValueChange = { onChange(schedule.copy(name = it.take(40))) },
                label = { Text(stringResource(R.string.schedule_name)) },
                placeholder = { Text(stringResource(R.string.schedule_default_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(schedule.enabled, role = Role.Switch) { onChange(schedule.copy(enabled = it)) }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.schedule_enabled), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = schedule.enabled, onCheckedChange = null)
            }
        }
        item { Header(stringResource(R.string.schedule_days)) }
        item {
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..7).forEach { day ->
                    val on = day in schedule.days
                    FilterChip(
                        selected = on,
                        onClick = { onChange(schedule.copy(days = if (on) schedule.days - day else schedule.days + day)) },
                        label = { Text(DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, locale)) },
                    )
                }
            }
        }
        item { Header(stringResource(R.string.schedule_time)) }
        item {
            TimeRow(stringResource(R.string.schedule_start), formatMinuteOfDay(schedule.startMinute)) { editingStart = true }
            TimeRow(stringResource(R.string.schedule_end), formatMinuteOfDay(schedule.endMinute)) { editingStart = false }
            if (schedule.endMinute < schedule.startMinute) Muted(stringResource(R.string.schedule_overnight))
            if (schedule.endMinute == schedule.startMinute) Muted(stringResource(R.string.schedule_same_time))
        }
        item { Header(stringResource(R.string.schedule_apps)) }
        item {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it.take(60) },
                placeholder = { Text(stringResource(R.string.search_apps)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        items(visible, key = { it.key }) { app ->
            val checked = app.key in schedule.apps
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(checked, role = Role.Checkbox) {
                        onChange(schedule.copy(apps = if (checked) schedule.apps - app.key else schedule.apps + app.key))
                    }
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(app, 32.dp)
                Spacer(Modifier.width(16.dp))
                Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Checkbox(checked = checked, onCheckedChange = null)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.schedule_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    editingStart?.let { start ->
        TimeDialog(
            initialMinute = if (start) schedule.startMinute else schedule.endMinute,
            onPick = { m -> onChange(if (start) schedule.copy(startMinute = m) else schedule.copy(endMinute = m)) },
            onDismiss = { editingStart = null },
        )
    }
}

@Composable
private fun TimeRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initialMinute: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = {
                onPick(state.hour * 60 + state.minute)
                onDismiss()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
