package app.haven.launcher.widgets

import android.appwidget.AppWidgetProviderInfo
import android.os.Process
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import app.haven.launcher.R
import app.haven.launcher.data.AppItem
import app.haven.launcher.data.HomeWidget
import app.haven.launcher.data.Search
import app.haven.launcher.ui.components.ActionRow
import app.haven.launcher.ui.components.AppIcon
import app.haven.launcher.ui.components.ProfileTag
import app.haven.launcher.ui.components.profileLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The widgets on the home screen, stacked full width like stock Android. */
@Composable
fun HomeWidgetStack(
    widgets: List<HomeWidget>,
    controller: HomeWidgets,
    onLongPress: (HomeWidget) -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val longPress by rememberUpdatedState(onLongPress)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        widgets.forEach { widget ->
            key(widget.id) {
                val height = (widget.rows * WIDGET_ROW_DP).dp
                val view = remember(widget.id) { controller.viewFor(widget.id) }
                if (view == null) {
                    // Its app was uninstalled or the widget broke: offer removal instead of a blank gap.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(height)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.25f))
                            .clickable(role = Role.Button) { longPress(widget) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.widget_unavailable),
                            color = contentColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    AndroidView(
                        factory = {
                            (view.parent as? android.view.ViewGroup)?.removeView(view)
                            view
                        },
                        update = { it.onLongPress = { longPress(widget) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(height)
                            .onSizeChanged { controller.applySize(view, it.width, it.height) },
                    )
                }
            }
        }
    }
}

private data class WidgetGroup(
    val key: String,
    val label: String,
    val app: AppItem?,
    val widgets: List<AppWidgetProviderInfo>,
)

/** Pick a widget to add, grouped by app, with previews and sizes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetPicker(
    host: HavenWidgetHost,
    apps: List<AppItem>,
    onPick: (AppWidgetProviderInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf("") }
    val groups by produceState<List<WidgetGroup>?>(null, apps) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            host.allProviders()
                .groupBy { "${it.provider.packageName}#${it.profile.hashCode()}" }
                .map { (key, list) ->
                    val first = list.first()
                    val app = apps.firstOrNull { it.app.packageName == first.provider.packageName && it.app.user == first.profile }
                    val label = app?.label ?: runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(first.provider.packageName, 0)).toString()
                    }.getOrDefault(first.provider.packageName)
                    WidgetGroup(key, label, app, list.sortedBy { it.loadLabel(pm) })
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    val visible = remember(groups, filter) {
        groups?.filter { filter.isBlank() || Search.score(Search.keyFor(it.label, it.label, ""), filter) > 0 }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            stringResource(R.string.widgets_title),
            Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it.take(60) },
            placeholder = { Text(stringResource(R.string.widgets_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when {
            visible == null -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            visible.isEmpty() -> Text(
                stringResource(R.string.widgets_empty),
                Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(Modifier.fillMaxHeight()) {
                visible.forEach { group ->
                    item(key = group.key) {
                        Row(
                            Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            group.app?.let {
                                AppIcon(it, 28.dp)
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(group.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                            if (group.widgets.first().profile != Process.myUserHandle()) {
                                Spacer(Modifier.width(8.dp))
                                group.app?.let { app -> profileLabel(app.profileType)?.let { ProfileTag(it) } }
                            }
                        }
                    }
                    items(group.widgets, key = { "${group.key}/${it.provider.className}" }) { info ->
                        WidgetOption(info) { onPick(info) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetOption(info: AppWidgetProviderInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    val label = remember(info) { info.loadLabel(context.packageManager) }
    val (w, h) = remember(info) { widgetCellSize(context, info) }
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val density = context.resources.displayMetrics.densityDpi
                val drawable = info.loadPreviewImage(context, density) ?: info.loadIcon(context, density)
                val maxPx = (260 * context.resources.displayMetrics.density).toInt()
                val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxPx
                val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: (maxPx / 2)
                val scale = minOf(1f, maxPx.toFloat() / maxOf(iw, ih))
                drawable.toBitmap((iw * scale).toInt().coerceAtLeast(1), (ih * scale).toInt().coerceAtLeast(1)).asImageBitmap()
            }.getOrNull()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 120.dp, height = 84.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.padding(6.dp).heightIn(max = 72.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.widget_size, w, h),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Long-press menu for a widget. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetActionsSheet(
    widget: HomeWidget,
    info: AppWidgetProviderInfo?,
    position: Int,
    count: Int,
    canReconfigure: Boolean,
    onResize: (Int) -> Unit,
    onMove: (Int) -> Unit,
    onReconfigure: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.widget_unavailable_title)
    val label = remember(info) { info?.loadLabel(context.packageManager) ?: unavailable }
    val resizable = info != null && canResizeVertically(info)
    val min = remember(info) { info?.let { minRows(context, it) } ?: 1 }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 12.dp)) {
            Text(
                label,
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (resizable) {
                if (widget.rows < MAX_WIDGET_ROWS) {
                    ActionRow(Icons.Filled.KeyboardArrowDown, stringResource(R.string.widget_taller)) { onResize(widget.rows + 1) }
                }
                if (widget.rows > min) {
                    ActionRow(Icons.Filled.KeyboardArrowUp, stringResource(R.string.widget_shorter)) { onResize(widget.rows - 1) }
                }
            }
            if (count > 1 && position > 0) {
                ActionRow(Icons.Filled.KeyboardArrowUp, stringResource(R.string.widget_move_up)) { onMove(-1) }
            }
            if (count > 1 && position < count - 1) {
                ActionRow(Icons.Filled.KeyboardArrowDown, stringResource(R.string.widget_move_down)) { onMove(1) }
            }
            if (canReconfigure) {
                ActionRow(Icons.Filled.Settings, stringResource(R.string.widget_settings)) {
                    onReconfigure()
                    onDismiss()
                }
            }
            ActionRow(Icons.Filled.Close, stringResource(R.string.widget_remove)) {
                onRemove()
                onDismiss()
            }
        }
    }
}
