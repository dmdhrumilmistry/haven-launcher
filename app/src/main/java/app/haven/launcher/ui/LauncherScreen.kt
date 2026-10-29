package app.haven.launcher.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haven.launcher.BuildConfig
import app.haven.launcher.R
import app.haven.launcher.data.AppItem
import app.haven.launcher.data.DoubleTapAction
import app.haven.launcher.data.SwipeDownAction
import app.haven.launcher.data.TextColorMode
import app.haven.launcher.system.SystemActions
import app.haven.launcher.ui.components.ActionRow
import app.haven.launcher.ui.components.AppActionsSheet
import app.haven.launcher.ui.components.IconPrefs
import app.haven.launcher.ui.components.LocalIconPrefs
import app.haven.launcher.ui.components.RenameDialog
import app.haven.launcher.ui.components.ShortcutActionsSheet
import app.haven.launcher.ui.drawer.AppDrawer
import app.haven.launcher.ui.drawer.DrawerCallbacks
import app.haven.launcher.ui.home.HomeCallbacks
import app.haven.launcher.ui.home.HomeScreen
import app.haven.launcher.ui.home.rememberWallpaperPrefersDarkText
import app.haven.launcher.ui.theme.HavenTheme
import app.haven.launcher.ui.theme.isDarkTheme
import app.haven.launcher.data.HomeWidget
import app.haven.launcher.haven
import app.haven.launcher.widgets.HomeWidgetStack
import app.haven.launcher.widgets.HomeWidgets
import app.haven.launcher.widgets.WidgetActionsSheet
import app.haven.launcher.widgets.WidgetPicker
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

private const val VELOCITY_THRESHOLD = 1200f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(vm: LauncherViewModel, widgets: HomeWidgets) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val drawerModel by vm.drawer.collectAsStateWithLifecycle()
    val homeEntries by vm.homeEntries.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val allApps by vm.allApps.collectAsStateWithLifecycle()
    val prompt by vm.prompt.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val view = LocalView.current
    // Configuration-aware, unlike context.resources, so strings follow language changes.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }

    // 0 = home, 1 = app drawer. Follows the finger while dragging, then settles with a spring.
    val progress = remember { Animatable(0f) }
    val dragDistancePx = with(LocalDensity.current) { (LocalConfiguration.current.screenHeightDp.dp * 0.45f).toPx() }
    var selectedTab by remember { mutableStateOf(DrawerTab.PERSONAL) }

    var sheetApp by remember { mutableStateOf<AppItem?>(null) }
    var sheetShortcut by remember { mutableStateOf<HomeEntry.Shortcut?>(null) }
    var renameApp by remember { mutableStateOf<AppItem?>(null) }
    var homeMenu by remember { mutableStateOf(false) }
    var widgetPicker by remember { mutableStateOf(false) }
    var sheetWidget by remember { mutableStateOf<HomeWidget?>(null) }

    // Undispatched so consecutive drag events always build on the latest value.
    fun snap(value: Float) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(value) }
    }

    fun hideKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
    }

    fun resetDrawer(animate: Boolean) {
        hideKeyboard()
        scope.launch {
            if (animate) progress.animateTo(0f, spring(stiffness = 500f)) else progress.snapTo(0f)
            vm.clearQuery()
            selectedTab = DrawerTab.PERSONAL
        }
    }

    fun openDrawer(withKeyboard: Boolean = settings.autoShowKeyboard) {
        scope.launch {
            progress.animateTo(1f, spring(stiffness = 500f))
            if (withKeyboard) {
                // Focus may fail if the drawer was closed mid-animation; that's fine.
                runCatching { searchFocus.requestFocus() }
                keyboard?.show()
            }
        }
    }

    fun settle(velocityY: Float) {
        val open = when {
            velocityY < -VELOCITY_THRESHOLD -> true
            velocityY > VELOCITY_THRESHOLD -> false
            else -> progress.value > 0.4f
        }
        if (open) openDrawer() else resetDrawer(animate = true)
    }

    // ---- Lifecycle ----

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    // Coming back from an app should land on a calm home screen, not a half-finished search.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        sheetApp = null
        sheetShortcut = null
        renameApp = null
        homeMenu = false
        sheetWidget = null
        resetDrawer(animate = false)
    }
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
            sheetApp = null
            sheetShortcut = null
            renameApp = null
            homeMenu = false
            sheetWidget = null
            resetDrawer(animate = true)
        }
    }
    // Closing the drawer by any route (drag, back) should drop the keyboard immediately.
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value < 0.95f }.collect { closing -> if (closing) hideKeyboard() }
    }

    // Derived so the screen only recomposes when crossing these thresholds, not on every frame.
    val showHome by remember { derivedStateOf { progress.value < 1f } }
    val showDrawer by remember { derivedStateOf { progress.value > 0f } }
    val drawerShowing by remember { derivedStateOf { progress.value > 0.5f } }

    BackHandler(enabled = showDrawer || progress.targetValue > 0f) {
        resetDrawer(animate = true)
    }

    // ---- Colours over the wallpaper ----

    val wallpaperIsLight by rememberWallpaperPrefersDarkText()
    val homeTextDark = when (settings.homeTextColor) {
        TextColorMode.LIGHT -> false
        TextColorMode.DARK -> true
        // A dim layer makes the wallpaper darker, so light text wins regardless.
        TextColorMode.AUTO -> wallpaperIsLight && settings.wallpaperDim < 0.25f
    }
    val homeColor = if (homeTextDark) Color(0xFF1B1C1C) else Color.White
    val darkTheme = isDarkTheme(settings)

    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        val lightBars = if (drawerShowing) !darkTheme else homeTextDark
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
        if (settings.hideStatusBar) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    // Dragging down on the drawer at the top of the list closes it, following the finger.
    val closeOnPull = remember(dragDistancePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Partially pulled down and now dragging back up: restore the drawer first.
                if (available.y < 0 && progress.value < 1f && source == NestedScrollSource.UserInput) {
                    val before = progress.value
                    val after = (before - available.y / dragDistancePx).coerceAtMost(1f)
                    snap(after)
                    return Offset(0f, (before - after) * dragDistancePx)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    val after = (progress.value - available.y / dragDistancePx).coerceAtLeast(0f)
                    snap(after)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (progress.value < 1f && progress.value > 0f) {
                    settle(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val homeCallbacks = remember(settings.doubleTapAction, dragDistancePx) {
        HomeCallbacks(
            onOpen = { entry, bounds ->
                when (entry) {
                    is HomeEntry.App -> vm.launch(entry.item, view, bounds)
                    is HomeEntry.Shortcut -> vm.launch(entry, view, bounds)
                }
            },
            onLongPress = { entry ->
                when (entry) {
                    is HomeEntry.App -> sheetApp = entry.item
                    is HomeEntry.Shortcut -> sheetShortcut = entry
                }
            },
            onOpenDrawer = { openDrawer() },
            onDrag = { dy ->
                // Only upward drags move the drawer; downward ones are the swipe-down gesture.
                if (dy < 0 || progress.value > 0f) {
                    val next = (progress.value - dy / dragDistancePx).coerceIn(0f, 1f)
                    snap(next)
                }
            },
            onDragEnd = { velocity, total ->
                if (progress.value > 0f) {
                    settle(velocity)
                } else if (total > 120f || velocity > VELOCITY_THRESHOLD) {
                    when (settings.swipeDownAction) {
                        SwipeDownAction.NOTIFICATIONS -> vm.expandNotifications()
                        SwipeDownAction.SEARCH -> openDrawer(withKeyboard = true)
                        SwipeDownAction.NONE -> Unit
                    }
                }
            },
            // Settings imported from a "full" build may ask for this; ignore it where unsupported.
            onDoubleTap = if (BuildConfig.LOCK_GESTURE && settings.doubleTapAction == DoubleTapAction.LOCK) ({ vm.lockScreen() }) else null,
            onBackgroundLongPress = { homeMenu = true },
        )
    }

    val drawerCallbacks = remember(vm) {
        DrawerCallbacks(
            onQueryChange = vm::setQuery,
            onSelectTab = { selectedTab = it },
            onLaunch = { item, bounds -> vm.launch(item, view, bounds) },
            onLongPress = { item ->
                hideKeyboard()
                sheetApp = item
            },
            onSetQuiet = { profile, quiet -> vm.setQuietMode(profile, quiet) },
            onOpenSettings = { SystemActions.openLauncherSettings(context) },
        )
    }

    HavenTheme(settings) {
        CompositionLocalProvider(LocalIconPrefs provides IconPrefs(settings.iconShape, settings.themedIcons)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = settings.wallpaperDim)),
            ) {
                if (showHome) {
                    HomeScreen(
                        settings = settings,
                        entries = homeEntries,
                        contentColor = homeColor,
                        callbacks = homeCallbacks,
                        widgetContent = if (settings.widgets.isEmpty()) null else { widgetModifier ->
                            HomeWidgetStack(
                                widgets = settings.widgets,
                                controller = widgets,
                                onLongPress = { sheetWidget = it },
                                contentColor = homeColor,
                                modifier = widgetModifier,
                            )
                        },
                        modifier = Modifier.graphicsLayer {
                            val p = progress.value
                            alpha = 1f - p
                            val scale = 1f - 0.04f * p
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
                if (showDrawer) {
                    AppDrawer(
                        settings = settings,
                        model = drawerModel,
                        loaded = snapshot.loaded,
                        selectedTab = selectedTab,
                        query = query,
                        results = search.results,
                        searchFocus = searchFocus,
                        callbacks = drawerCallbacks,
                        modifier = Modifier
                            .graphicsLayer {
                                val p = progress.value
                                alpha = p
                                translationY = (1f - p) * 64.dp.toPx()
                            }
                            .nestedScroll(closeOnPull)
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { dy ->
                                    val next = (progress.value - dy / dragDistancePx).coerceIn(0f, 1f)
                                    snap(next)
                                },
                                onDragStopped = { velocity -> settle(velocity) },
                            ),
                    )
                }

                AutoLaunch(settings.autoLaunchSingleResult, search) { vm.launch(it, view, null) }
            }

            // ---- Sheets & dialogs ----

            sheetApp?.let { item ->
                // Re-resolve so the sheet reflects changes (e.g. after renaming) while open.
                val live = allApps.firstOrNull { it.key == item.key } ?: item
                AppActionsSheet(
                    item = live,
                    isFavorite = settings.isFavorite(live.key),
                    canPinShortcuts = snapshot.canUseShortcuts,
                    loadShortcuts = { vm.shortcutsFor(live) },
                    onLaunchShortcut = { info ->
                        sheetApp = null
                        vm.launchShortcut(info, view, null)
                    },
                    onPinShortcut = { info ->
                        vm.pinShortcutToHome(info)
                        SystemActions.toast(context, resources.getString(R.string.added_to_home))
                    },
                    onToggleFavorite = {
                        vm.toggleFavorite(live)
                        sheetApp = null
                    },
                    onRename = {
                        renameApp = live
                        sheetApp = null
                    },
                    onToggleHidden = {
                        vm.setHidden(live, !live.isHidden)
                        if (!live.isHidden) SystemActions.toast(context, resources.getString(R.string.hidden_toast, live.label))
                        sheetApp = null
                    },
                    onDismiss = { sheetApp = null },
                )
            }

            sheetShortcut?.let { entry ->
                ShortcutActionsSheet(
                    info = entry.info,
                    label = (entry.info.shortLabel ?: entry.item.label).toString(),
                    onRemove = { vm.removeHomeEntry(entry) },
                    onDismiss = { sheetShortcut = null },
                )
            }

            renameApp?.let { item ->
                RenameDialog(
                    item = item,
                    onConfirm = { label ->
                        vm.rename(item, label)
                        renameApp = null
                    },
                    onDismiss = { renameApp = null },
                )
            }

            if (homeMenu) {
                ModalBottomSheet(onDismissRequest = { homeMenu = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    ActionRow(Icons.Filled.Add, stringResource(R.string.action_widgets)) {
                        homeMenu = false
                        widgetPicker = true
                    }
                    ActionRow(Icons.Filled.Home, stringResource(R.string.action_wallpaper)) {
                        homeMenu = false
                        SystemActions.pickWallpaper(context)
                    }
                    ActionRow(Icons.Filled.Edit, stringResource(R.string.action_edit_home)) {
                        homeMenu = false
                        context.startActivity(
                            android.content.Intent(context, app.haven.launcher.ui.settings.SettingsActivity::class.java)
                                .putExtra(app.haven.launcher.ui.settings.SettingsActivity.EXTRA_PAGE, "home_apps"),
                        )
                    }
                    ActionRow(Icons.Filled.Settings, stringResource(R.string.launcher_settings)) {
                        homeMenu = false
                        SystemActions.openLauncherSettings(context)
                    }
                }
            }

            if (widgetPicker) {
                WidgetPicker(
                    host = widgets.host,
                    apps = allApps,
                    onPick = { info ->
                        widgetPicker = false
                        widgets.add(info)
                    },
                    onDismiss = { widgetPicker = false },
                )
            }

            sheetWidget?.let { widget ->
                val index = settings.widgets.indexOfFirst { it.id == widget.id }
                val live = settings.widgets.getOrNull(index)
                if (live != null) {
                    WidgetActionsSheet(
                        widget = live,
                        info = remember(live.id) { widgets.host.infoFor(live.id) },
                        position = index,
                        count = settings.widgets.size,
                        canReconfigure = remember(live.id) { widgets.canReconfigure(live.id) },
                        onResize = { rows -> context.haven.settings.resizeWidget(live.id, rows) },
                        onMove = { delta -> context.haven.settings.moveWidget(live.id, delta) },
                        onReconfigure = { widgets.reconfigure(live.id) },
                        onRemove = { widgets.remove(live.id) },
                        onDismiss = { sheetWidget = null },
                    )
                }
            }

            PromptDialog(prompt, vm)
        }
    }
}

/**
 * "Launch when only one match": fires only while typing forward, never while deleting, so
 * correcting a typo can't accidentally open something.
 */
@Composable
private fun AutoLaunch(enabled: Boolean, search: SearchState, launch: (AppItem) -> Unit) {
    // Plain holder, not state: it only remembers what we last evaluated and must not recompose.
    val previous = remember { arrayOf("") }
    LaunchedEffect(search) {
        val grew = search.query.length > previous[0].length && search.query.startsWith(previous[0])
        previous[0] = search.query
        val only = search.results.singleOrNull()
        if (enabled && grew && search.query.trim().length >= 2 && only != null && !only.isPaused) {
            launch(only)
        }
    }
}

@Composable
private fun PromptDialog(prompt: LauncherPrompt?, vm: LauncherViewModel) {
    val context = LocalContext.current
    when (prompt) {
        null -> Unit
        is LauncherPrompt.TurnOnWorkApps -> AlertDialog(
            onDismissRequest = vm::dismissPrompt,
            title = { Text(stringResource(R.string.work_paused_title)) },
            text = { Text(stringResource(R.string.work_paused_prompt)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissPrompt()
                    vm.setQuietMode(prompt.profile, false)
                }) { Text(stringResource(R.string.action_turn_on)) }
            },
            dismissButton = { TextButton(onClick = vm::dismissPrompt) { Text(stringResource(R.string.action_cancel)) } },
        )
        LauncherPrompt.EnableLockService -> AlertDialog(
            onDismissRequest = vm::dismissPrompt,
            title = { Text(stringResource(R.string.lock_setup_title)) },
            // Prominent disclosure: say exactly what the service does and doesn't do before sending
            // anyone to the accessibility settings.
            text = { Text(stringResource(R.string.lock_setup_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissPrompt()
                    SystemActions.openAccessibilitySettings(context)
                }) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = { TextButton(onClick = vm::dismissPrompt) { Text(stringResource(R.string.action_not_now)) } },
        )
        LauncherPrompt.NeedDefaultLauncher -> AlertDialog(
            onDismissRequest = vm::dismissPrompt,
            title = { Text(stringResource(R.string.need_default_title)) },
            text = { Text(stringResource(R.string.need_default_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissPrompt()
                    SystemActions.openHomeSettings(context)
                }) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = { TextButton(onClick = vm::dismissPrompt) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
