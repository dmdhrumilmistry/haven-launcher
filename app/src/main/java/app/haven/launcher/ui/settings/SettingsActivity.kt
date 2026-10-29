package app.haven.launcher.ui.settings

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haven.launcher.BuildConfig
import app.haven.launcher.R
import app.haven.launcher.data.ClockFormat
import app.haven.launcher.data.ClockSize
import app.haven.launcher.data.DoubleTapAction
import app.haven.launcher.data.DrawerLayout
import app.haven.launcher.data.HomeAlignment
import app.haven.launcher.data.HomeItem
import app.haven.launcher.data.HomePosition
import app.haven.launcher.data.HomeStyle
import app.haven.launcher.data.IconShape
import app.haven.launcher.data.IconSize
import app.haven.launcher.data.LauncherSettings
import app.haven.launcher.data.Search
import app.haven.launcher.data.SwipeDownAction
import app.haven.launcher.data.TextColorMode
import app.haven.launcher.data.ThemeMode
import app.haven.launcher.haven
import app.haven.launcher.system.LauncherAccessibilityService
import app.haven.launcher.system.SystemActions
import app.haven.launcher.ui.LauncherViewModel
import app.haven.launcher.ui.components.AppIcon
import app.haven.launcher.ui.components.ConfirmDialog
import app.haven.launcher.ui.components.IconPrefs
import app.haven.launcher.ui.components.LocalIconPrefs
import app.haven.launcher.ui.components.ProfileTag
import app.haven.launcher.ui.components.profileLabel
import app.haven.launcher.ui.theme.HavenTheme
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Page { MAIN, HOME_APPS, ADD_HOME_APPS, HIDDEN_APPS }

class SettingsActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startPage = if (intent.getStringExtra(EXTRA_PAGE) == "home_apps") Page.HOME_APPS else Page.MAIN
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            HavenTheme(settings) {
                CompositionLocalProvider(LocalIconPrefs provides IconPrefs(settings.iconShape, settings.themedIcons)) {
                    SettingsRoot(vm, settings, startPage, onExit = ::finish)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val REQUEST_HOME_ROLE = 7
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot(vm: LauncherViewModel, settings: LauncherSettings, startPage: Page, onExit: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(startPage) }
    val goBack: () -> Unit = {
        when (page) {
            Page.MAIN -> onExit()
            Page.ADD_HOME_APPS -> page = Page.HOME_APPS
            // Opened straight from the home screen's menu: back returns there.
            else -> if (startPage == page) onExit() else page = Page.MAIN
        }
    }
    BackHandler(onBack = goBack)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (page) {
                                Page.MAIN -> R.string.settings_title
                                Page.HOME_APPS -> R.string.settings_home_apps
                                Page.ADD_HOME_APPS -> R.string.settings_add_home_apps
                                Page.HIDDEN_APPS -> R.string.settings_hidden_apps
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (page) {
                Page.MAIN -> MainSettings(vm, settings, onOpenPage = { page = it })
                Page.HOME_APPS -> HomeAppsPage(vm, settings, onAdd = { page = Page.ADD_HOME_APPS })
                Page.ADD_HOME_APPS -> AddHomeAppsPage(vm, settings)
                Page.HIDDEN_APPS -> HiddenAppsPage(vm)
            }
        }
    }
}

@Composable
private fun MainSettings(vm: LauncherViewModel, s: LauncherSettings, onOpenPage: (Page) -> Unit) {
    val context = LocalContext.current
    val repo = context.haven.settings
    val scope = rememberCoroutineScope()
    // Configuration-aware, unlike context.resources, so strings follow language changes.
    val resources = LocalResources.current
    val isDefault by vm.isDefaultLauncher.collectAsStateWithLifecycle()
    var lockServiceOn by remember { mutableStateOf(LauncherAccessibilityService.isEnabled(context)) }
    var showLockDisclosure by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.onResume()
        lockServiceOn = LauncherAccessibilityService.isEnabled(context)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(repo.exportJson().toByteArray()) }
                }.isSuccess
            }
            SystemActions.toast(context, resources.getString(if (ok) R.string.backup_saved else R.string.backup_failed))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        // Guard against picking a huge unrelated file.
                        val bytes = input.readNBytesCompat(MAX_BACKUP_BYTES)
                        String(bytes)
                    }
                }.getOrNull()
            }
            val ok = text != null && repo.importJson(text)
            SystemActions.toast(context, resources.getString(if (ok) R.string.backup_restored else R.string.backup_invalid))
        }
    }

    val hiddenCount = s.hiddenApps.size

    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        if (!isDefault) {
            item {
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.default_banner_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.default_banner_body), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = {
                            (context as? android.app.Activity)?.let {
                                SystemActions.requestDefaultLauncher(it, SettingsActivity.REQUEST_HOME_ROLE)
                            }
                        }) { Text(stringResource(R.string.action_set_default)) }
                    }
                }
            }
        }

        // ---- Home screen ----
        item { SectionHeader(stringResource(R.string.section_home)) }
        item {
            ClickRow(
                stringResource(R.string.settings_home_apps),
                pluralString(R.plurals.home_apps_count, s.favorites.size),
            ) { onOpenPage(Page.HOME_APPS) }
        }
        item {
            ChoiceRow(
                stringResource(R.string.setting_home_style),
                listOf(
                    HomeStyle.TEXT to stringResource(R.string.home_style_text),
                    HomeStyle.ICONS_AND_TEXT to stringResource(R.string.home_style_icons_text),
                    HomeStyle.DOCK to stringResource(R.string.home_style_dock),
                ),
                s.homeStyle,
                { v -> repo.update { it.copy(homeStyle = v) } },
            )
        }
        if (s.homeStyle == HomeStyle.DOCK) {
            item {
                ChoiceRow(
                    stringResource(R.string.setting_home_columns),
                    (3..6).map { it to it.toString() },
                    s.homeColumns,
                    { v -> repo.update { it.copy(homeColumns = v) } },
                )
            }
        }
        item {
            ChoiceRow(
                stringResource(R.string.setting_alignment),
                listOf(
                    HomeAlignment.START to stringResource(R.string.align_start),
                    HomeAlignment.CENTER to stringResource(R.string.align_center),
                    HomeAlignment.END to stringResource(R.string.align_end),
                ),
                s.homeAlignment,
                { v -> repo.update { it.copy(homeAlignment = v) } },
            )
        }
        item {
            ChoiceRow(
                stringResource(R.string.setting_position),
                listOf(
                    HomePosition.CENTER to stringResource(R.string.position_center),
                    HomePosition.BOTTOM to stringResource(R.string.position_bottom),
                ),
                s.homePosition,
                { v -> repo.update { it.copy(homePosition = v) } },
            )
        }
        item { SwitchRow(stringResource(R.string.setting_show_clock), s.showClock, { v -> repo.update { it.copy(showClock = v) } }) }
        item {
            ChoiceRow(
                stringResource(R.string.setting_clock_format),
                listOf(
                    ClockFormat.SYSTEM to stringResource(R.string.clock_system),
                    ClockFormat.H12 to stringResource(R.string.clock_12),
                    ClockFormat.H24 to stringResource(R.string.clock_24),
                ),
                s.clockFormat,
                { v -> repo.update { it.copy(clockFormat = v) } },
                enabled = s.showClock,
            )
        }
        item {
            ChoiceRow(
                stringResource(R.string.setting_clock_size),
                listOf(
                    ClockSize.SMALL to stringResource(R.string.size_small),
                    ClockSize.MEDIUM to stringResource(R.string.size_medium),
                    ClockSize.LARGE to stringResource(R.string.size_large),
                ),
                s.clockSize,
                { v -> repo.update { it.copy(clockSize = v) } },
                enabled = s.showClock,
            )
        }
        item { SwitchRow(stringResource(R.string.setting_show_date), s.showDate, { v -> repo.update { it.copy(showDate = v) } }) }
        item {
            SwitchRow(
                stringResource(R.string.setting_drawer_button),
                s.showDrawerButton,
                { v -> repo.update { it.copy(showDrawerButton = v) } },
                summary = stringResource(R.string.setting_drawer_button_summary),
            )
        }

        // ---- App drawer ----
        item { SectionHeader(stringResource(R.string.section_drawer)) }
        item {
            ChoiceRow(
                stringResource(R.string.setting_layout),
                listOf(
                    DrawerLayout.LIST to stringResource(R.string.layout_list),
                    DrawerLayout.GRID to stringResource(R.string.layout_grid),
                ),
                s.drawerLayout,
                { v -> repo.update { it.copy(drawerLayout = v) } },
            )
        }
        if (s.drawerLayout == DrawerLayout.GRID) {
            item {
                ChoiceRow(
                    stringResource(R.string.setting_columns),
                    listOf(0 to stringResource(R.string.columns_auto)) + (3..7).map { it to it.toString() },
                    s.gridColumns,
                    { v -> repo.update { it.copy(gridColumns = v) } },
                )
            }
            item { SwitchRow(stringResource(R.string.setting_grid_labels), s.showGridLabels, { v -> repo.update { it.copy(showGridLabels = v) } }) }
        } else {
            item { SwitchRow(stringResource(R.string.setting_list_icons), s.showListIcons, { v -> repo.update { it.copy(showListIcons = v) } }) }
        }
        item {
            ChoiceRow(
                stringResource(R.string.setting_icon_size),
                listOf(
                    IconSize.SMALL to stringResource(R.string.size_small),
                    IconSize.MEDIUM to stringResource(R.string.size_medium),
                    IconSize.LARGE to stringResource(R.string.size_large),
                ),
                s.iconSize,
                { v -> repo.update { it.copy(iconSize = v) } },
            )
        }
        item {
            ChoiceRow(
                stringResource(R.string.setting_icon_shape),
                listOf(
                    IconShape.SYSTEM to stringResource(R.string.shape_system),
                    IconShape.CIRCLE to stringResource(R.string.shape_circle),
                    IconShape.SQUIRCLE to stringResource(R.string.shape_squircle),
                    IconShape.ROUNDED_SQUARE to stringResource(R.string.shape_rounded),
                ),
                s.iconShape,
                { v -> repo.update { it.copy(iconShape = v) } },
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            item {
                SwitchRow(
                    stringResource(R.string.setting_themed_icons),
                    s.themedIcons,
                    { v -> repo.update { it.copy(themedIcons = v) } },
                    summary = stringResource(R.string.setting_themed_icons_summary),
                )
            }
        }
        item {
            SwitchRow(
                stringResource(R.string.setting_profile_tabs),
                s.separateProfileTabs,
                { v -> repo.update { it.copy(separateProfileTabs = v) } },
                summary = stringResource(R.string.setting_profile_tabs_summary),
            )
        }
        item {
            ClickRow(
                stringResource(R.string.settings_hidden_apps),
                if (hiddenCount == 0) stringResource(R.string.hidden_none) else pluralString(R.plurals.hidden_count, hiddenCount),
            ) { onOpenPage(Page.HIDDEN_APPS) }
        }

        // ---- Search ----
        item { SectionHeader(stringResource(R.string.section_search)) }
        item { SwitchRow(stringResource(R.string.setting_auto_keyboard), s.autoShowKeyboard, { v -> repo.update { it.copy(autoShowKeyboard = v) } }) }
        item {
            SwitchRow(
                stringResource(R.string.setting_search_bottom),
                s.searchBarAtBottom,
                { v -> repo.update { it.copy(searchBarAtBottom = v) } },
                summary = stringResource(R.string.setting_search_bottom_summary),
            )
        }
        item {
            SwitchRow(
                stringResource(R.string.setting_auto_launch),
                s.autoLaunchSingleResult,
                { v -> repo.update { it.copy(autoLaunchSingleResult = v) } },
                summary = stringResource(R.string.setting_auto_launch_summary),
            )
        }
        item { SwitchRow(stringResource(R.string.setting_web_search), s.showWebSearch, { v -> repo.update { it.copy(showWebSearch = v) } }) }
        item {
            SwitchRow(
                stringResource(R.string.setting_search_hidden),
                s.searchHiddenApps,
                { v -> repo.update { it.copy(searchHiddenApps = v) } },
                summary = stringResource(R.string.setting_search_hidden_summary),
            )
        }

        // ---- Gestures ----
        item { SectionHeader(stringResource(R.string.section_gestures)) }
        item { InfoRow(stringResource(R.string.gesture_swipe_up), stringResource(R.string.gesture_swipe_up_value)) }
        item {
            ChoiceRow(
                stringResource(R.string.gesture_swipe_down),
                listOf(
                    SwipeDownAction.NOTIFICATIONS to stringResource(R.string.action_notifications),
                    SwipeDownAction.SEARCH to stringResource(R.string.action_search),
                    SwipeDownAction.NONE to stringResource(R.string.action_nothing),
                ),
                s.swipeDownAction,
                { v -> repo.update { it.copy(swipeDownAction = v) } },
            )
        }
        if (BuildConfig.LOCK_GESTURE && Build.VERSION.SDK_INT >= 28) {
            item {
                ChoiceRow(
                    stringResource(R.string.gesture_double_tap),
                    listOf(
                        DoubleTapAction.LOCK to stringResource(R.string.action_lock_screen),
                        DoubleTapAction.NONE to stringResource(R.string.action_nothing),
                    ),
                    s.doubleTapAction,
                    { v ->
                        repo.update { it.copy(doubleTapAction = v) }
                        if (v == DoubleTapAction.LOCK && !lockServiceOn) showLockDisclosure = true
                    },
                )
            }
            if (s.doubleTapAction == DoubleTapAction.LOCK && !lockServiceOn) {
                item {
                    ClickRow(
                        stringResource(R.string.lock_setup_needed),
                        stringResource(R.string.lock_setup_needed_summary),
                    ) { showLockDisclosure = true }
                }
            }
        }
        item { InfoRow(stringResource(R.string.gesture_long_press), stringResource(R.string.gesture_long_press_value)) }

        // ---- Appearance ----
        item { SectionHeader(stringResource(R.string.section_appearance)) }
        item {
            ChoiceRow(
                stringResource(R.string.setting_theme),
                listOf(
                    ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                    ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    ThemeMode.DARK to stringResource(R.string.theme_dark),
                ),
                s.themeMode,
                { v -> repo.update { it.copy(themeMode = v) } },
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            item {
                SwitchRow(
                    stringResource(R.string.setting_dynamic_color),
                    s.dynamicColor,
                    { v -> repo.update { it.copy(dynamicColor = v) } },
                    summary = stringResource(R.string.setting_dynamic_color_summary),
                )
            }
        }
        item {
            ChoiceRow(
                stringResource(R.string.setting_home_text_color),
                listOf(
                    TextColorMode.AUTO to stringResource(R.string.text_color_auto),
                    TextColorMode.LIGHT to stringResource(R.string.text_color_light),
                    TextColorMode.DARK to stringResource(R.string.text_color_dark),
                ),
                s.homeTextColor,
                { v -> repo.update { it.copy(homeTextColor = v) } },
            )
        }
        item {
            SliderRow(
                stringResource(R.string.setting_dim),
                s.wallpaperDim,
                0f..0.8f,
                steps = 7,
                format = { "${(it * 100).roundToInt()}%" },
                onChange = { v -> repo.update { it.copy(wallpaperDim = v) } },
            )
        }
        item {
            SliderRow(
                stringResource(R.string.setting_drawer_opacity),
                s.drawerOpacity,
                0.5f..1f,
                steps = 9,
                format = { "${(it * 100).roundToInt()}%" },
                onChange = { v -> repo.update { it.copy(drawerOpacity = v) } },
            )
        }
        item {
            SliderRow(
                stringResource(R.string.setting_text_size),
                s.textScale,
                0.8f..1.4f,
                steps = 11,
                format = { "${(it * 100).roundToInt()}%" },
                onChange = { v -> repo.update { it.copy(textScale = v) } },
            )
        }
        item {
            SwitchRow(
                stringResource(R.string.setting_hide_status_bar),
                s.hideStatusBar,
                { v -> repo.update { it.copy(hideStatusBar = v) } },
                summary = stringResource(R.string.setting_hide_status_bar_summary),
            )
        }

        // ---- Backup ----
        item { SectionHeader(stringResource(R.string.section_backup)) }
        item {
            ClickRow(stringResource(R.string.backup_export), stringResource(R.string.backup_export_summary)) {
                exportLauncher.launch("haven-launcher-backup.json")
            }
        }
        item {
            ClickRow(stringResource(R.string.backup_import), stringResource(R.string.backup_import_summary)) {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        }
        item { ClickRow(stringResource(R.string.reset_all), stringResource(R.string.reset_all_summary)) { confirmReset = true } }

        // ---- About ----
        item { SectionHeader(stringResource(R.string.section_about)) }
        item { InfoRow(stringResource(R.string.about_privacy), stringResource(R.string.about_privacy_summary)) }
        item {
            ClickRow(stringResource(R.string.about_default_app), stringResource(R.string.about_default_app_summary)) {
                SystemActions.openHomeSettings(context)
            }
        }
        item { InfoRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME) }
    }

    if (showLockDisclosure) {
        AlertDialog(
            onDismissRequest = { showLockDisclosure = false },
            title = { Text(stringResource(R.string.lock_setup_title)) },
            text = { Text(stringResource(R.string.lock_setup_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showLockDisclosure = false
                    SystemActions.openAccessibilitySettings(context)
                }) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { showLockDisclosure = false }) { Text(stringResource(R.string.action_not_now)) } },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.reset_all),
            message = stringResource(R.string.reset_all_confirm),
            confirmLabel = stringResource(R.string.action_reset),
            onConfirm = { repo.resetAll() },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun pluralString(id: Int, count: Int): String =
    LocalResources.current.getQuantityString(id, count, count)

private const val MAX_BACKUP_BYTES = 1_000_000

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < max) {
        val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** Reorder and remove what's on the home screen. Up/down buttons are simple and fully accessible. */
@Composable
private fun HomeAppsPage(vm: LauncherViewModel, s: LauncherSettings, onAdd: () -> Unit) {
    val context = LocalContext.current
    val repo = context.haven.settings
    val apps by vm.allApps.collectAsStateWithLifecycle()
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val homeEntries by vm.homeEntries.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.home_apps_hint),
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(s.favorites, key = { _, it -> it.id }) { index, fav ->
                val app = (fav as? HomeItem.App)?.let { byKey[it.appKey] }
                val label = when (fav) {
                    is HomeItem.App -> app?.label ?: stringResource(R.string.unavailable_app)
                    is HomeItem.Shortcut -> fav.label.ifBlank { fav.packageName }
                }
                val unavailable = when (fav) {
                    is HomeItem.App -> app == null
                    is HomeItem.Shortcut -> homeEntries.none { it.id == fav.id }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (app != null) {
                        AppIcon(app, 36.dp)
                        Spacer(Modifier.width(16.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        val sub = when {
                            unavailable -> stringResource(R.string.unavailable_summary)
                            fav is HomeItem.Shortcut -> stringResource(R.string.shortcut_label)
                            else -> app?.let { profileLabel(it.profileType) }
                        }
                        if (sub != null) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { repo.moveHomeItem(fav.id, -1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.action_move_up, label))
                    }
                    IconButton(onClick = { repo.moveHomeItem(fav.id, 1) }, enabled = index < s.favorites.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.action_move_down, label))
                    }
                    IconButton(onClick = {
                        val entry = homeEntries.firstOrNull { it.id == fav.id }
                        if (entry != null) vm.removeHomeEntry(entry) else repo.removeHomeItem(fav.id)
                    }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.action_remove_item, label))
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            FilledTonalButton(onClick = onAdd) { Text(stringResource(R.string.settings_add_home_apps)) }
        }
    }
}

@Composable
private fun AddHomeAppsPage(vm: LauncherViewModel, s: LauncherSettings) {
    val repo = LocalContext.current.haven.settings
    val apps by vm.allApps.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    val visible = remember(apps, filter) {
        apps.filter { !it.isHidden && it.app.profile.isVisible }
            .let { list -> if (filter.isBlank()) list else list.filter { Search.score(it.search, filter) > 0 } }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it.take(60) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_apps)) },
            singleLine = true,
        )
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(visible, key = { _, it -> it.key }) { _, app ->
                val checked = s.isFavorite(app.key)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = checked, role = Role.Checkbox, onValueChange = { repo.toggleFavorite(app.key) })
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app, 36.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    profileLabel(app.profileType)?.let {
                        ProfileTag(it)
                        Spacer(Modifier.width(8.dp))
                    }
                    Checkbox(checked = checked, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun HiddenAppsPage(vm: LauncherViewModel) {
    val context = LocalContext.current
    val apps by vm.allApps.collectAsStateWithLifecycle()
    val hidden = remember(apps) { apps.filter { it.isHidden } }
    if (hidden.isEmpty()) {
        Text(
            stringResource(R.string.hidden_empty),
            Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn {
        item {
            Text(
                stringResource(R.string.hidden_hint),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(hidden, key = { _, it -> it.key }) { _, app ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(app, 36.dp)
                Spacer(Modifier.width(16.dp))
                Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { SystemActions.launchApp(context, app.app) }) { Text(stringResource(R.string.action_open)) }
                TextButton(onClick = { vm.setHidden(app, false) }) { Text(stringResource(R.string.action_unhide)) }
            }
        }
    }
}
