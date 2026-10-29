package app.haven.launcher.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import app.haven.launcher.data.HomeAlignment
import app.haven.launcher.ui.HomeEntry
import kotlin.math.ceil

private const val MERGE_RADIUS = 0.28f
private const val REORDER_DELAY_MS = 250L
private const val TICK_MS = 80L

/** Where each slot sits, filled in by the layout and read by the drag logic. */
private class GridMetrics {
    var cellWidth = 0
    var rowPitch = 0
    var columns = 1
    var count = 0
    var alignment = HomeAlignment.CENTER

    fun slot(index: Int): IntOffset {
        val row = index / columns
        val col = index % columns
        val inRow = minOf(columns, count - row * columns)
        val empty = (columns - inRow) * cellWidth
        val start = when (alignment) {
            HomeAlignment.START -> 0
            HomeAlignment.CENTER -> empty / 2
            HomeAlignment.END -> empty
        }
        return IntOffset(start + col * cellWidth, row * rowPitch)
    }
}

/**
 * Home apps in equal cells (one column for the list styles). Long-press and drag an item to
 * move it, like stock Android; long-press and let go without moving opens its menu.
 */
@Composable
fun ReorderableHomeGrid(
    entries: List<HomeEntry>,
    columns: Int,
    alignment: HomeAlignment,
    rowSpacing: Dp,
    reorderEnabled: Boolean,
    onReorder: (List<String>) -> Unit,
    onMenu: (HomeEntry) -> Unit,
    /** Dropping [dragged] onto [target] makes or fills a folder. */
    canMerge: (dragged: HomeEntry, target: HomeEntry) -> Boolean,
    onMerge: (draggedId: String, targetId: String) -> Unit,
    modifier: Modifier = Modifier,
    item: @Composable (entry: HomeEntry, gestures: Modifier, dragging: Boolean, mergeTarget: Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val metrics = remember { GridMetrics() }
    // Local order while dragging; snaps back to the saved order whenever that changes.
    var order by remember(entries) { mutableStateOf(entries) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragStartSlot by remember { mutableStateOf(IntOffset.Zero) }
    var dragTotal by remember { mutableStateOf(Offset.Zero) }
    var mergeTargetId by remember { mutableStateOf<String?>(null) }
    val latestReorder by rememberUpdatedState(onReorder)
    val latestMenu by rememberUpdatedState(onMenu)
    val latestCanMerge by rememberUpdatedState(canMerge)
    val latestMerge by rememberUpdatedState(onMerge)
    // Moving to a new slot only reorders after a short pause there, so you can drag *onto* an app
    // (to make a folder) without everything shuffling out from under you, like stock Android.
    // [0] = slot the finger is lingering over (-1 none), [1] = since when. Not state: no redraw needed.
    val timer = remember { longArrayOf(-1L, 0L) }

    fun evaluate(now: Long) {
        val id = draggingId ?: return
        val from = order.indexOfFirst { it.id == id }
        if (from < 0) return
        val center = Offset(
            dragStartSlot.x + dragTotal.x + metrics.cellWidth / 2f,
            dragStartSlot.y + dragTotal.y + metrics.rowPitch / 2f,
        )
        val nearest = order.indices.minByOrNull { i ->
            val s = metrics.slot(i)
            (Offset(s.x + metrics.cellWidth / 2f, s.y + metrics.rowPitch / 2f) - center).getDistanceSquared()
        } ?: return
        val slot = metrics.slot(nearest)
        val distance = (Offset(slot.x + metrics.cellWidth / 2f, slot.y + metrics.rowPitch / 2f) - center).getDistance()
        val over = order[nearest]
        val dragged = order[from]
        // Close to another item's centre: it's a drop target for a folder.
        if (nearest != from && distance < metrics.cellWidth * MERGE_RADIUS && latestCanMerge(dragged, over)) {
            if (mergeTargetId != over.id) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            mergeTargetId = over.id
            timer[0] = -1
            return
        }
        mergeTargetId = null
        if (nearest == from) {
            timer[0] = -1
            return
        }
        if (timer[0] != nearest.toLong()) {
            timer[0] = nearest.toLong()
            timer[1] = now
        } else if (now - timer[1] >= REORDER_DELAY_MS) {
            order = order.toMutableList().apply { add(nearest, removeAt(from)) }
            timer[0] = -1
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Layout(
        modifier = modifier,
        content = {
            order.forEach { entry ->
                // Keyed so the dragged item keeps its gesture while it moves to a new slot.
                key(entry.id) {
                    val dragging = draggingId == entry.id
                    val gestures = Modifier.pointerInput(entry.id, reorderEnabled) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            var moved = false
                            var travelled = Offset.Zero
                            while (true) {
                                // Wake up regularly even if the finger is still, so a pause over a slot reorders.
                                val event = withTimeoutOrNull(TICK_MS) { awaitPointerEvent() }
                                if (event == null) {
                                    if (moved) evaluate(System.currentTimeMillis())
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == press.id } ?: break
                                // Consuming everything (including the lift) stops the tap from also firing.
                                val delta = change.positionChange()
                                change.consume()
                                if (!change.pressed) break
                                travelled += delta
                                if (!moved && travelled.getDistance() > viewConfiguration.touchSlop && reorderEnabled) {
                                    moved = true
                                    draggingId = entry.id
                                    dragStartSlot = metrics.slot(order.indexOfFirst { it.id == entry.id }.coerceAtLeast(0))
                                    dragTotal = travelled
                                } else if (moved) {
                                    dragTotal += delta
                                    evaluate(System.currentTimeMillis())
                                }
                            }
                            if (moved) {
                                val target = mergeTargetId
                                if (target != null) latestMerge(entry.id, target) else latestReorder(order.map { it.id })
                                draggingId = null
                                mergeTargetId = null
                                dragTotal = Offset.Zero
                            } else {
                                latestMenu(entry)
                            }
                        }
                    }
                    Box(
                        Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                if (dragging) {
                                    val current = metrics.slot(order.indexOfFirst { it.id == entry.id }.coerceAtLeast(0))
                                    translationX = dragStartSlot.x + dragTotal.x - current.x
                                    translationY = dragStartSlot.y + dragTotal.y - current.y
                                    scaleX = 1.08f
                                    scaleY = 1.08f
                                }
                            },
                        contentAlignment = when (alignment) {
                            HomeAlignment.START -> Alignment.TopStart
                            HomeAlignment.CENTER -> Alignment.TopCenter
                            HomeAlignment.END -> Alignment.TopEnd
                        },
                    ) {
                        item(entry, gestures, dragging, mergeTargetId == entry.id)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val cols = columns.coerceAtLeast(1)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val cellWidth = width / cols
        val placeables = measurables.map { it.measure(Constraints(minWidth = cellWidth, maxWidth = cellWidth)) }
        val rowHeight = placeables.maxOfOrNull { it.height } ?: 0
        val pitch = rowHeight + rowSpacing.roundToPx()
        metrics.cellWidth = cellWidth
        metrics.rowPitch = pitch
        metrics.columns = cols
        metrics.count = placeables.size
        metrics.alignment = alignment
        val rows = ceil(placeables.size / cols.toFloat()).toInt()
        val height = if (rows == 0) 0 else rows * pitch - rowSpacing.roundToPx()
        layout(width, height) {
            placeables.forEachIndexed { i, p -> p.place(metrics.slot(i)) }
        }
    }
}
