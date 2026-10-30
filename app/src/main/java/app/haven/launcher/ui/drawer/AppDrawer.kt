package app.haven.launcher.ui.drawer

import android.graphics.Rect
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.unit.IntOffset
import app.haven.launcher.ui.DrawerEntry
import app.haven.launcher.ui.components.FolderIcon
import app.haven.launcher.ui.components.LongPressDragCallbacks
import app.haven.launcher.ui.components.longPressDrag
import kotlin.math.roundToInt
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import app.haven.launcher.ui.SearchState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haven.launcher.R
import app.haven.launcher.data.AppItem
import app.haven.launcher.data.DrawerLayout
import app.haven.launcher.data.LauncherSettings
import app.haven.launcher.data.Profile
import app.haven.launcher.system.SystemActions
import app.haven.launcher.ui.DrawerModel
import app.haven.launcher.ui.DrawerTab
import app.haven.launcher.ui.components.AppIcon
import app.haven.launcher.ui.components.LocalPausedApps
import app.haven.launcher.ui.components.ProfileTag
import app.haven.launcher.ui.components.profileLabel
import app.haven.launcher.ui.components.windowRect
import kotlinx.coroutines.launch

class DrawerCallbacks(
    val onQueryChange: (String) -> Unit,
    val onSelectTab: (DrawerTab) -> Unit,
    val onLaunch: (AppItem, Rect?) -> Unit,
    val onLongPress: (AppItem) -> Unit,
    val onSetQuiet: (Profile, Boolean) -> Unit,
    val onOpenSettings: () -> Unit,
    val onUnlockHidden: () -> Unit,
    val onOpenFolder: (DrawerEntry.Folder) -> Unit,
    val onDropApp: (AppItem, DrawerEntry) -> Unit,
)

/** Recently installed apps that haven't been opened yet; they get a small dot. */
val LocalNewApps = compositionLocalOf { emptySet<String>() }

/** Whether hidden apps are unlocked right now (only matters when the hidden-apps lock is on). */
val LocalHiddenUnlocked = compositionLocalOf { false }

@Composable
fun AppDrawer(
    settings: LauncherSettings,
    model: DrawerModel,
    loaded: Boolean,
    selectedTab: DrawerTab,
    query: String,
    search: SearchState,
    suggestions: List<AppItem>,
    newApps: Set<String>,
    hiddenUnlocked: Boolean,
    searchFocus: FocusRequester,
    callbacks: DrawerCallbacks,
    modifier: Modifier = Modifier,
) {
    val results = search.results
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Scrolling the list means the person is browsing, not typing: get the keyboard out of the way.
    val hideKeyboardOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
                return Offset.Zero
            }
        }
    }
    val tab = if (selectedTab in model.tabs) selectedTab else DrawerTab.PERSONAL
    val opacity = settings.drawerOpacity

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = opacity))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (!settings.searchBarAtBottom) {
            SearchField(query, results, settings, searchFocus, callbacks)
        }
        if (query.isBlank() && model.tabs.size > 1) {
            ProfileTabs(model.tabs, tab, model.categoryNames, callbacks.onSelectTab)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(hideKeyboardOnScroll),
        ) {
            CompositionLocalProvider(LocalNewApps provides newApps, LocalHiddenUnlocked provides hiddenUnlocked) {
                when {
                    query.isNotBlank() -> SearchResults(query, search, settings, callbacks)
                    !loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    model.tabs.size > 1 -> ProfilePager(tab, model, settings, suggestions, callbacks)
                    else -> TabContent(tab, model, settings, suggestions, callbacks)
                }
            }
        }
        if (settings.searchBarAtBottom) {
            SearchField(query, results, settings, searchFocus, callbacks)
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    results: List<AppItem>,
    settings: LauncherSettings,
    focus: FocusRequester,
    cb: DrawerCallbacks,
) {
    val context = LocalContext.current
    TextField(
        value = query,
        onValueChange = cb.onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .focusRequester(focus),
        placeholder = { Text(stringResource(R.string.search_apps)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { cb.onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, stringResource(R.string.clear_search))
                }
            } else {
                IconButton(onClick = cb.onOpenSettings) {
                    Icon(Icons.Filled.Settings, stringResource(R.string.launcher_settings))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = {
            // Enter opens the top result: the fastest path from thought to app.
            val first = results.firstOrNull()
            when {
                first != null -> cb.onLaunch(first, null)
                query.isNotBlank() && settings.showWebSearch -> SystemActions.webSearch(context, query)
            }
        }),
    )
}

@Composable
private fun tabLabel(tab: DrawerTab, names: Map<String, String>): String = when (tab) {
    DrawerTab.Personal -> stringResource(R.string.profile_personal)
    DrawerTab.Work -> stringResource(R.string.profile_work)
    DrawerTab.Private -> stringResource(R.string.profile_private)
    DrawerTab.Hidden -> stringResource(R.string.tab_hidden)
    is DrawerTab.Category -> names[tab.id].orEmpty().ifBlank { stringResource(R.string.category_default) }
}

/** Segmented buttons for a few tabs; a scrollable tab row once categories make it longer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileTabs(tabs: List<DrawerTab>, selected: DrawerTab, names: Map<String, String>, onSelect: (DrawerTab) -> Unit) {
    if (tabs.size <= 3) {
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                SegmentedButton(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                    icon = {},
                    label = { Text(tabLabel(tab, names), maxLines = 1) },
                )
            }
        }
    } else {
        PrimaryScrollableTabRow(
            selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
            edgePadding = 16.dp,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            divider = {},
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    text = { Text(tabLabel(tab, names), maxLines = 1) },
                )
            }
        }
    }
}

/** Personal / Work / Private side by side: swipe between them, or tap the tabs above. */
@Composable
private fun ProfilePager(
    tab: DrawerTab,
    model: DrawerModel,
    settings: LauncherSettings,
    suggestions: List<AppItem>,
    cb: DrawerCallbacks,
) {
    val tabs = model.tabs
    val pager = rememberPagerState(initialPage = tabs.indexOf(tab).coerceAtLeast(0)) { tabs.size }
    // Tab tapped: follow it.
    LaunchedEffect(tab, tabs) {
        val index = tabs.indexOf(tab)
        if (index >= 0 && index != pager.currentPage) pager.animateScrollToPage(index)
    }
    // Swiped: tell the tabs.
    val currentTabs by rememberUpdatedState(tabs)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page -> currentTabs.getOrNull(page)?.let(cb.onSelectTab) }
    }
    HorizontalPager(state = pager, key = { tabs.getOrElse(it) { DrawerTab.PERSONAL }.key }, beyondViewportPageCount = 1) { page ->
        val pageTab = tabs.getOrElse(page) { DrawerTab.PERSONAL }
        TabContent(pageTab, model, settings, suggestions, cb)
    }
}

@Composable
private fun TabContent(
    tab: DrawerTab,
    model: DrawerModel,
    settings: LauncherSettings,
    suggestions: List<AppItem>,
    cb: DrawerCallbacks,
) {
    if (tab == DrawerTab.HIDDEN && settings.lockHiddenApps && !LocalHiddenUnlocked.current) {
        StateMessage(
            title = stringResource(R.string.hidden_locked_title),
            body = stringResource(R.string.hidden_locked_body),
            action = stringResource(R.string.action_unlock),
            onAction = cb.onUnlockHidden,
        )
        return
    }
    val profile = model.profileByTab[tab]
    val entries = model.entriesByTab[tab].orEmpty()

    if (tab == DrawerTab.PRIVATE && profile != null && profile.isQuiet) {
        StateMessage(
            title = stringResource(R.string.private_locked_title),
            body = stringResource(R.string.private_locked_body),
            action = stringResource(R.string.action_unlock),
            onAction = { cb.onSetQuiet(profile, false) },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        val pausedWork = when {
            tab == DrawerTab.WORK && profile?.isQuiet == true -> profile
            tab == DrawerTab.PERSONAL && model.mergedWorkProfile?.isQuiet == true -> model.mergedWorkProfile
            else -> null
        }
        if (pausedWork != null) {
            Banner(
                text = stringResource(R.string.work_paused_body),
                action = stringResource(R.string.action_turn_on),
                onAction = { cb.onSetQuiet(pausedWork, false) },
            )
        }
        if (tab == DrawerTab.PRIVATE && profile != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.private_unlocked),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { cb.onSetQuiet(profile, true) }) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_lock))
                }
            }
        }

        if (tab == DrawerTab.PERSONAL && suggestions.isNotEmpty() && entries.isNotEmpty()) {
            SuggestionsRow(suggestions, settings, cb)
        }

        Box(Modifier.weight(1f)) {
            if (entries.isEmpty()) {
                StateMessage(
                    title = stringResource(R.string.no_apps_title),
                    body = stringResource(
                        when (tab) {
                            DrawerTab.PERSONAL -> R.string.no_apps_body_hidden
                            is DrawerTab.Category -> R.string.no_apps_body_category
                            else -> R.string.no_apps_body_profile
                        },
                    ),
                )
            } else {
                // Folders belong to the profile lists; categories and Hidden are plain lists.
                val foldersAllowed = tab == DrawerTab.Personal || tab == DrawerTab.Work || tab == DrawerTab.Private
                AppCollection(
                    entries = entries,
                    settings = settings,
                    showProfileTag = (tab == DrawerTab.PERSONAL && model.mergedWorkProfile != null) ||
                        tab == DrawerTab.HIDDEN || tab is DrawerTab.Category,
                    showScroller = model.alphabetical,
                    dragEnabled = foldersAllowed && !settings.lockHomeLayout,
                    cb = cb,
                )
            }
        }

        if (tab == DrawerTab.WORK && profile != null && !profile.isQuiet) {
            FilledTonalButton(
                onClick = { cb.onSetQuiet(profile, true) },
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.action_pause_work)) }
        }
    }
}

/** Your most-used apps, one tap away, like the suggestions row in stock Android. */
@Composable
private fun SuggestionsRow(suggestions: List<AppItem>, settings: LauncherSettings, cb: DrawerCallbacks) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            suggestions.forEach { item ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    AppCell(item, settings.copy(showGridLabels = true), cb, drag = null)
                }
            }
            // Keep cells the same width as a full row even with fewer suggestions.
            repeat(5 - suggestions.size) { Spacer(Modifier.weight(1f)) }
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
    }
}

/**
 * Drag-and-drop state for one list: which app is being dragged, where the finger is, and which
 * cell it's over. Cells report their bounds so hit-testing doesn't depend on lazy-list internals.
 */
private class DrawerDrag {
    var dragged by mutableStateOf<AppItem?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var target by mutableStateOf<String?>(null)
    val bounds = HashMap<String, androidx.compose.ui.geometry.Rect>()

    fun update(position: Offset) {
        pointer = position
        val self = dragged?.key
        target = bounds.entries.firstOrNull { (key, rect) -> key != self && rect.contains(position) }?.key
    }
}

/** Per-cell hookup for drag and drop. Null where dragging isn't offered (search, suggestions). */
private class CellDrag(val state: DrawerDrag, val enabled: Boolean, val onDrop: (AppItem, DrawerEntry) -> Unit, val entries: () -> List<DrawerEntry>)

@Composable
private fun Modifier.dragCell(key: String, drag: CellDrag?): Modifier {
    if (drag == null) return this
    DisposableEffect(key) { onDispose { drag.state.bounds.remove(key) } }
    val hovered = drag.state.target == key
    return this
        .onGloballyPositioned { drag.state.bounds[key] = it.boundsInRoot() }
        .then(
            if (hovered) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            else Modifier,
        )
}

/** List or grid of apps and folders, with the A-Z scroller alongside. */
@Composable
private fun AppCollection(
    entries: List<DrawerEntry>,
    settings: LauncherSettings,
    showProfileTag: Boolean,
    showScroller: Boolean,
    dragEnabled: Boolean,
    cb: DrawerCallbacks,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val dragState = remember { DrawerDrag() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val currentEntries by rememberUpdatedState(entries)
    val drag = remember(dragEnabled, cb) {
        CellDrag(dragState, dragEnabled, cb.onDropApp) { currentEntries }
    }
    val sections = remember(entries, showScroller) {
        if (!showScroller) emptyList()
        else entries.withIndex().distinctBy { it.value.section }.map { it.value.section to it.index }
    }
    // Starting fresh at the top whenever the content changes tab or ordering avoids "lost" lists.
    LaunchedEffect(entries.firstOrNull()?.key) {
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (settings.drawerLayout == DrawerLayout.GRID) {
                    AppGrid(entries, settings, gridState, drag, cb)
                } else {
                    AppList(entries, settings, listState, showProfileTag, drag, cb)
                }
            }
            // Only worth showing when the list is long enough to need it.
            if (sections.size >= 5 && entries.size > 20) {
                AlphabetScroller(
                    sections = sections.map { it.first },
                    onSection = { i ->
                        val target = sections[i].second
                        scope.launch {
                            if (settings.drawerLayout == DrawerLayout.GRID) gridState.scrollToItem(target)
                            else listState.scrollToItem(target)
                        }
                    },
                )
            }
        }
        // The app under your finger while dragging.
        dragState.dragged?.let { item ->
            val size = settings.iconSize.dp.dp
            val half = with(LocalDensity.current) { (size / 2).roundToPx() }
            AppIcon(
                item,
                size,
                Modifier
                    .offset {
                        IntOffset(
                            (dragState.pointer.x - origin.x).roundToInt() - half,
                            (dragState.pointer.y - origin.y).roundToInt() - half * 2,
                        )
                    }
                    .graphicsLayer {
                        scaleX = 1.15f
                        scaleY = 1.15f
                        shadowElevation = 12f
                    },
            )
        }
    }
}

/** Touch handling for an app cell: tap opens, long-press opens the menu, long-press + move drags. */
@Composable
private fun Modifier.appGestures(item: AppItem, drag: CellDrag?, cb: DrawerCallbacks, onTap: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val moreLabel = stringResource(R.string.more_options)
    if (drag == null) {
        return this
            .combinedClickable(
                role = Role.Button,
                onClickLabel = item.label,
                onLongClickLabel = moreLabel,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    cb.onLongPress(item)
                },
                onClick = onTap,
            )
            .semantics { customActions = listOf(CustomAccessibilityAction(moreLabel) { cb.onLongPress(item); true }) }
    }
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val latestTap by rememberUpdatedState(onTap)
    return this
        .onGloballyPositioned { coords[0] = it }
        .clickable(role = Role.Button, onClickLabel = item.label) { latestTap() }
        .longPressDrag(item.key, drag.enabled, haptics, { coords[0] }) {
            LongPressDragCallbacks(
                onMenu = { cb.onLongPress(item) },
                onDragStart = { p ->
                    drag.state.dragged = item
                    drag.state.update(p)
                },
                onDrag = { p -> drag.state.update(p) },
                onDragEnd = {
                    val targetKey = drag.state.target
                    val target = drag.entries().firstOrNull { it.key == targetKey }
                    drag.state.dragged = null
                    drag.state.target = null
                    if (target != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        drag.onDrop(item, target)
                    }
                },
            )
        }
        .semantics {
            onLongClick(moreLabel) { cb.onLongPress(item); true }
            customActions = listOf(CustomAccessibilityAction(moreLabel) { cb.onLongPress(item); true })
        }
}

@Composable
private fun AppList(
    entries: List<DrawerEntry>,
    settings: LauncherSettings,
    state: LazyListState,
    showProfileTag: Boolean,
    drag: CellDrag,
    cb: DrawerCallbacks,
) {
    LazyColumn(state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(entries, key = { _, it -> it.key }, contentType = { _, it -> if (it is DrawerEntry.Folder) "folder" else "app" }) { _, entry ->
            when (entry) {
                is DrawerEntry.App -> AppRow(entry.item, settings, showProfileTag, highlighted = false, cb, drag)
                is DrawerEntry.Folder -> FolderRow(entry, settings, drag, cb)
            }
        }
    }
}

@Composable
private fun AppGrid(entries: List<DrawerEntry>, settings: LauncherSettings, state: LazyGridState, drag: CellDrag, cb: DrawerCallbacks) {
    val cells = if (settings.gridColumns == 0) GridCells.Adaptive(settings.iconSize.dp.dp + 40.dp)
    else GridCells.Fixed(settings.gridColumns)
    LazyVerticalGrid(
        columns = cells,
        state = state,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(entries, key = { _, it -> it.key }, contentType = { _, it -> if (it is DrawerEntry.Folder) "folder" else "app" }) { _, entry ->
            when (entry) {
                is DrawerEntry.App -> AppCell(entry.item, settings, cb, drag)
                is DrawerEntry.Folder -> FolderCell(entry, settings, drag, cb)
            }
        }
    }
}

@Composable
fun AppRow(item: AppItem, settings: LauncherSettings, showProfileTag: Boolean, highlighted: Boolean, cb: DrawerCallbacks) =
    AppRow(item, settings, showProfileTag, highlighted, cb, drag = null)

@Composable
private fun AppRow(
    item: AppItem,
    settings: LauncherSettings,
    showProfileTag: Boolean,
    highlighted: Boolean,
    cb: DrawerCallbacks,
    drag: CellDrag?,
) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val dragging = drag?.state?.dragged?.key == item.key
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .dragCell(item.key, drag)
            .clip(RoundedCornerShape(16.dp))
            .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)) else Modifier)
            .appGestures(item, drag, cb) { cb.onLaunch(item, bounds) }
            .onGloballyPositioned { bounds = it.windowRect() }
            .graphicsLayer { alpha = if (dragging) 0.3f else 1f }
            .padding(horizontal = 16.dp, vertical = if (settings.showListIcons) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (settings.showListIcons) {
            AppIcon(item, (settings.iconSize.dp - 8).dp)
            Spacer(Modifier.width(16.dp))
        }
        if (item.key in LocalNewApps.current) NewDot()
        Text(
            item.label,
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.isPaused || item.key in LocalPausedApps.current) 0.5f else 1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val tag = profileLabel(item.profileType)
        if (showProfileTag && tag != null) {
            Spacer(Modifier.width(8.dp))
            ProfileTag(tag)
        }
    }
}

@Composable
private fun AppCell(item: AppItem, settings: LauncherSettings, cb: DrawerCallbacks, drag: CellDrag?) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val dragging = drag?.state?.dragged?.key == item.key
    Column(
        Modifier
            .dragCell(item.key, drag)
            .clip(RoundedCornerShape(16.dp))
            .appGestures(item, drag, cb) { cb.onLaunch(item, bounds) }
            .onGloballyPositioned { bounds = it.windowRect() }
            .graphicsLayer { alpha = if (dragging) 0.3f else 1f }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(item, settings.iconSize.dp.dp)
        if (settings.showGridLabels) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.key in LocalNewApps.current) NewDot()
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.isPaused || item.key in LocalPausedApps.current) 0.5f else 1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FolderCell(entry: DrawerEntry.Folder, settings: LauncherSettings, drag: CellDrag, cb: DrawerCallbacks) {
    val label = stringResource(R.string.open_folder, entry.label)
    Column(
        Modifier
            .dragCell(entry.key, drag)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(role = Role.Button, onClickLabel = label, onLongClick = { cb.onOpenFolder(entry) }) { cb.onOpenFolder(entry) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FolderIcon(entry.apps, settings.iconSize.dp.dp)
        if (settings.showGridLabels) {
            Spacer(Modifier.height(6.dp))
            Text(
                entry.label,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FolderRow(entry: DrawerEntry.Folder, settings: LauncherSettings, drag: CellDrag, cb: DrawerCallbacks) {
    val label = stringResource(R.string.open_folder, entry.label)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .dragCell(entry.key, drag)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(role = Role.Button, onClickLabel = label, onLongClick = { cb.onOpenFolder(entry) }) { cb.onOpenFolder(entry) }
            .padding(horizontal = 16.dp, vertical = if (settings.showListIcons) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (settings.showListIcons) {
            FolderIcon(entry.apps, (settings.iconSize.dp - 8).dp)
            Spacer(Modifier.width(16.dp))
        }
        Text(
            entry.label,
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            entry.apps.size.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchResults(query: String, search: SearchState, settings: LauncherSettings, cb: DrawerCallbacks) {
    val results = search.results
    val context = LocalContext.current
    val copiedLabel = stringResource(R.string.copied)
    val canWeb = remember { SystemActions.canWebSearch(context) || SystemActions.canResolve(context, browserProbe) }
    val canStore = remember { SystemActions.canStoreSearch(context) }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        search.calculation?.let { answer ->
            item(key = "calc") {
                CalculatorRow(answer) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(answer, answer))
                    // Android 13+ shows its own "copied" confirmation.
                    if (Build.VERSION.SDK_INT < 33) SystemActions.toast(context, copiedLabel)
                }
            }
        }
        if (results.isEmpty() && search.calculation == null) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.search_no_results, query.trim()),
                    Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(results, key = { _, it -> it.key }) { index, item ->
            // The first result is what Enter will open, so show that.
            AppRow(item, settings, showProfileTag = true, highlighted = index == 0, cb = cb)
        }
        if (search.canUnlockHidden) {
            item(key = "unlock") {
                SearchAction(stringResource(R.string.search_unlock_hidden), Icons.Outlined.Lock, cb.onUnlockHidden)
            }
        }
        if (settings.showWebSearch) {
            if (canWeb) {
                item(key = "web") {
                    SearchAction(stringResource(R.string.search_web, query.trim())) {
                        SystemActions.webSearch(context, query.trim())
                    }
                }
            }
            if (canStore) {
                item(key = "store") {
                    SearchAction(stringResource(R.string.search_store, query.trim())) {
                        SystemActions.storeSearch(context, query.trim())
                    }
                }
            }
        }
    }
}

private val browserProbe = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))

@Composable
private fun CalculatorRow(answer: String, onCopy: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_copy), onClick = onCopy)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "= $answer",
            Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(stringResource(R.string.action_copy), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun NewDot() {
    Box(
        Modifier
            .padding(end = 4.dp)
            .size(6.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

@Composable
private fun SearchAction(label: String, onClick: () -> Unit) = SearchAction(label, Icons.Filled.Search, onClick)

@Composable
private fun SearchAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onAction) { Text(action, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun StateMessage(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}
