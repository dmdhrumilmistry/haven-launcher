package app.haven.launcher

import android.content.pm.LauncherApps
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.haven.launcher.data.HomeItem
import app.haven.launcher.system.SystemActions
import app.haven.launcher.ui.components.ShortcutIcon
import app.haven.launcher.ui.theme.HavenTheme

/**
 * Handles "Add to home screen" requests from other apps (browsers, contacts, maps...).
 * Android requires the default launcher to confirm these; we ask the user, then pin it.
 */
class AddShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = try {
            launcherApps.getPinItemRequest(intent)
        } catch (e: Exception) {
            null
        }
        val info = request?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT }?.shortcutInfo
        if (request == null || info == null || !request.isValid) {
            finish()
            return
        }
        val label = (info.shortLabel ?: info.longLabel ?: "").toString()

        setContent {
            val settings by haven.settings.settings.collectAsState()
            HavenTheme(settings) {
                AlertDialog(
                    onDismissRequest = ::finish,
                    icon = { ShortcutIcon(info, 48.dp) },
                    title = { Text(stringResource(R.string.add_shortcut_title)) },
                    text = { Text(label) },
                    confirmButton = {
                        TextButton(onClick = {
                            val accepted = try {
                                request.isValid && request.accept()
                            } catch (e: Exception) {
                                false
                            }
                            if (accepted) {
                                val serial = haven.apps.serialFor(info.userHandle)
                                haven.settings.addHomeItem(HomeItem.Shortcut(info.`package`, info.id, serial, label))
                                haven.apps.reload()
                                SystemActions.toast(this@AddShortcutActivity, getString(R.string.added_to_home))
                            } else {
                                SystemActions.toast(this@AddShortcutActivity, getString(R.string.error_add_shortcut))
                            }
                            finish()
                        }) { Text(stringResource(R.string.action_add)) }
                    },
                    dismissButton = { TextButton(onClick = ::finish) { Text(stringResource(R.string.action_cancel)) } },
                )
            }
        }
    }
}
