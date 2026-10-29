package app.haven.launcher

import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.haven.launcher.data.HomeItem
import app.haven.launcher.data.HomeWidget
import app.haven.launcher.system.SystemActions
import app.haven.launcher.ui.components.ShortcutIcon
import app.haven.launcher.ui.theme.HavenTheme
import app.haven.launcher.widgets.defaultRows

/**
 * Handles "Add to home screen" requests from other apps: shortcuts (a website from a browser, a
 * contact) and widgets ("Add widget" buttons inside apps). Android requires the default launcher
 * to confirm these; we ask the user, then add it.
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
        if (request == null || !request.isValid) {
            finish()
            return
        }
        when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> request.shortcutInfo?.let { confirmShortcut(request, it) } ?: finish()
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> confirmWidget(request)
            else -> finish()
        }
    }

    private fun confirmShortcut(request: LauncherApps.PinItemRequest, info: ShortcutInfo) {
        val label = (info.shortLabel ?: info.longLabel ?: "").toString()
        showDialog(
            title = { stringResource(R.string.add_shortcut_title) },
            body = label,
            icon = { ShortcutIcon(info, 48.dp) },
        ) {
            val accepted = try {
                request.isValid && request.accept()
            } catch (e: Exception) {
                false
            }
            if (accepted) {
                val serial = haven.apps.serialFor(info.userHandle)
                haven.settings.addHomeItem(HomeItem.Shortcut(info.`package`, info.id, serial, label))
                haven.apps.reload()
            }
            accepted
        }
    }

    private fun confirmWidget(request: LauncherApps.PinItemRequest) {
        val info = try {
            request.getAppWidgetProviderInfo(this)
        } catch (e: Exception) {
            null
        }
        if (info == null) {
            finish()
            return
        }
        showDialog(
            title = { stringResource(R.string.add_widget_title) },
            body = info.loadLabel(packageManager),
            icon = null,
        ) {
            val host = haven.widgetHost
            val id = try {
                host.allocateAppWidgetId()
            } catch (e: Exception) {
                return@showDialog false
            }
            // Accepting with our new id makes the system bind the widget to it for us.
            val accepted = try {
                request.isValid && request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) })
            } catch (e: Exception) {
                false
            }
            if (accepted) {
                haven.settings.addWidget(HomeWidget(id, defaultRows(this, info)))
            } else {
                runCatching { host.deleteAppWidgetId(id) }
            }
            accepted
        }
    }

    private fun showDialog(
        title: @Composable () -> String,
        body: String,
        icon: (@Composable () -> Unit)?,
        onAdd: () -> Boolean,
    ) {
        setContent {
            val settings by haven.settings.settings.collectAsState()
            HavenTheme(settings) {
                AlertDialog(
                    onDismissRequest = ::finish,
                    icon = icon,
                    title = { Text(title()) },
                    text = { Text(body) },
                    confirmButton = {
                        TextButton(onClick = {
                            val ok = onAdd()
                            SystemActions.toast(
                                this@AddShortcutActivity,
                                getString(if (ok) R.string.added_to_home else R.string.error_add_shortcut),
                            )
                            finish()
                        }) { Text(stringResource(R.string.action_add)) }
                    },
                    dismissButton = { TextButton(onClick = ::finish) { Text(stringResource(R.string.action_cancel)) } },
                )
            }
        }
    }
}
