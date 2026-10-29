package app.haven.launcher.ui.components

import android.content.pm.ShortcutInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Home as HomeOutlined
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.haven.launcher.R
import app.haven.launcher.data.AppItem
import app.haven.launcher.data.ProfileType
import app.haven.launcher.data.SettingsRepository
import app.haven.launcher.focus.PauseState
import app.haven.launcher.focus.pauseDescription
import app.haven.launcher.system.SystemActions

@Composable
fun profileLabel(type: ProfileType): String? = when (type) {
    ProfileType.PERSONAL -> null
    ProfileType.WORK -> stringResource(R.string.profile_work)
    ProfileType.PRIVATE -> stringResource(R.string.profile_private)
    ProfileType.CLONE -> stringResource(R.string.profile_clone)
}

/** Long-press menu for an app: its shortcuts first (most useful), then customisation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActionsSheet(
    item: AppItem,
    isFavorite: Boolean,
    canPinShortcuts: Boolean,
    loadShortcuts: suspend () -> List<ShortcutInfo>,
    onLaunchShortcut: (ShortcutInfo) -> Unit,
    onPinShortcut: (ShortcutInfo) -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onToggleHidden: () -> Unit,
    onDismiss: () -> Unit,
    /** Opens the phone's own app-cloning screen; null where the phone has none. */
    onClone: (() -> Unit)? = null,
    /** Shown when the menu was opened from inside a folder. */
    onRemoveFromFolder: (() -> Unit)? = null,
    onCategories: (() -> Unit)? = null,
    /** Haven-level pause state, if paused right now. */
    pauseState: PauseState? = null,
    onPause: (() -> Unit)? = null,
    onUnpause: (() -> Unit)? = null,
    /** Digital Wellbeing's app timer for this app; null where unavailable. */
    onAppTimer: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var shortcuts by remember(item.key) { mutableStateOf<List<ShortcutInfo>>(emptyList()) }
    LaunchedEffect(item.key) { shortcuts = loadShortcuts() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(item, 44.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val secondary = listOfNotNull(
                        profileLabel(item.profileType),
                        item.app.systemLabel.takeIf { it != item.label },
                        stringResource(R.string.app_paused).takeIf { item.isPaused },
                        stringResource(R.string.app_suspended).takeIf { item.app.isSuspended },
                        pauseState?.let { pauseDescription(it) },
                    ).joinToString(" · ")
                    if (secondary.isNotEmpty()) {
                        Text(secondary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (shortcuts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                shortcuts.forEach { sc ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onLaunchShortcut(sc) }
                            .padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ShortcutIcon(sc, 28.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            (sc.shortLabel ?: sc.longLabel ?: "").toString(),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (canPinShortcuts) {
                            IconButton(onClick = { onPinShortcut(sc) }) {
                                Icon(Icons.Filled.Add, stringResource(R.string.action_add_shortcut_to_home))
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 24.dp))
            }

            ActionRow(
                if (isFavorite) Icons.Filled.Home else Icons.Outlined.HomeOutlined,
                stringResource(if (isFavorite) R.string.action_remove_from_home else R.string.action_add_to_home),
                onToggleFavorite,
            )
            if (onRemoveFromFolder != null) {
                ActionRow(Icons.Filled.Close, stringResource(R.string.action_remove_from_folder), onRemoveFromFolder)
            }
            ActionRow(Icons.Filled.Edit, stringResource(R.string.action_rename), onRename)
            if (onCategories != null) {
                ActionRow(Icons.Filled.List, stringResource(R.string.action_categories), onCategories)
            }
            ActionRow(
                Icons.Outlined.Lock,
                stringResource(if (item.isHidden) R.string.action_unhide else R.string.action_hide),
                onToggleHidden,
            )
            ActionRow(Icons.Filled.Info, stringResource(R.string.action_app_info)) {
                SystemActions.openAppInfo(context, item.app.component, item.app.user)
                onDismiss()
            }
            when {
                pauseState is PauseState.Manual && onUnpause != null ->
                    ActionRow(Icons.Filled.PlayArrow, stringResource(R.string.action_unpause), onUnpause)
                // Paused by a schedule: that's changed in settings, the subtitle explains.
                pauseState == null && onPause != null ->
                    ActionRow(Icons.Filled.DateRange, stringResource(R.string.action_pause), onPause)
            }
            if (onAppTimer != null) {
                ActionRow(Icons.Filled.Notifications, stringResource(R.string.action_app_timer), onAppTimer)
            }
            if (onClone != null) {
                ActionRow(Icons.Filled.AddCircle, stringResource(R.string.action_clone), onClone)
            }
            if (!item.app.isSystemApp) {
                ActionRow(Icons.Filled.Delete, stringResource(R.string.action_uninstall)) {
                    SystemActions.uninstall(context, item.app.packageName, item.app.user)
                    onDismiss()
                }
            }
        }
    }
}

/** Long-press menu for a pinned shortcut on the home screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutActionsSheet(info: ShortcutInfo, label: String, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ShortcutIcon(info, 44.dp)
                Spacer(Modifier.width(16.dp))
                Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ActionRow(Icons.Filled.Close, stringResource(R.string.action_remove_from_home)) {
                onRemove()
                onDismiss()
            }
            val activity = info.activity
            if (activity != null) {
                ActionRow(Icons.Filled.Info, stringResource(R.string.action_app_info)) {
                    SystemActions.openAppInfo(context, activity, info.userHandle)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun RenameDialog(item: AppItem, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var value by remember(item.key) {
        mutableStateOf(TextFieldValue(item.label, selection = TextRange(0, item.label.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val save = { onConfirm(value.text.trim().takeIf { it.isNotEmpty() }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.text.length <= SettingsRepository.MAX_LABEL) value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    supportingText = {
                        if (item.label != item.app.systemLabel) {
                            Text(stringResource(R.string.rename_original, item.app.systemLabel))
                        }
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = save) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.label != item.app.systemLabel) {
                    TextButton(onClick = { onConfirm(null) }) { Text(stringResource(R.string.action_reset)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** A small rounded tag, e.g. "Work" next to an app in search results. */
@Composable
fun ProfileTag(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
