package app.haven.launcher.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates

/** Callbacks for [longPressDrag]. Positions are in root (window content) coordinates. */
class LongPressDragCallbacks(
    val onMenu: () -> Unit,
    val onDragStart: (Offset) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onDragEnd: () -> Unit,
)

/**
 * Stock-launcher touch handling: tap is left to `clickable`; long-press and let go opens the menu;
 * long-press and move drags. Place it AFTER `clickable` in the chain so it can swallow the lift that
 * ends a long-press (otherwise the tap would fire too).
 */
fun Modifier.longPressDrag(
    key: Any,
    dragEnabled: Boolean,
    haptics: HapticFeedback,
    coordinates: () -> LayoutCoordinates?,
    callbacks: () -> LongPressDragCallbacks,
): Modifier = pointerInput(key, dragEnabled) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        var dragging = false
        var travelled = Offset.Zero
        fun root(local: Offset): Offset = coordinates()?.takeIf { it.isAttached }?.localToRoot(local) ?: local
        var finished = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == press.id } ?: break
                val delta = change.position - change.previousPosition
                change.consume()
                if (!change.pressed) break
                travelled += delta
                if (!dragging && dragEnabled && travelled.getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                    callbacks().onDragStart(root(change.position))
                } else if (dragging) {
                    callbacks().onDrag(root(change.position))
                }
            }
            finished = true
            if (dragging) callbacks().onDragEnd() else callbacks().onMenu()
        } finally {
            // Gesture cancelled (e.g. the list was recomposed away): never leave a drag hanging.
            if (!finished && dragging) callbacks().onDragEnd()
        }
    }
}
