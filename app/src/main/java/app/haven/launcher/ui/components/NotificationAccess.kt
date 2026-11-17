package app.haven.launcher.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.haven.launcher.R
import app.haven.launcher.system.SystemActions

/**
 * Prominent disclosure before sending anyone to the notification access screen: what it's used
 * for (badges and holding paused apps' notifications) and what it never does.
 */
@Composable
fun NotificationAccessDisclosure(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.badges_disclosure_title)) },
        text = { Text(stringResource(R.string.badges_disclosure_body)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                SystemActions.openNotificationAccessSettings(context)
            }) { Text(stringResource(R.string.action_open_settings)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) } },
    )
}
