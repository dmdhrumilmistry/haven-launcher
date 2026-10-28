package app.haven.launcher.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Fast A-Z jump strip. Excluded from the system back gesture so dragging it doesn't navigate
 * back, and hidden from TalkBack (the list itself is fully navigable, the strip would only add noise).
 */
@Composable
fun AlphabetScroller(sections: List<String>, onSection: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val onSectionState by rememberUpdatedState(onSection)
    var active by remember { mutableIntStateOf(-1) }
    var touchY by remember { mutableIntStateOf(0) }

    BoxWithConstraints(
        Modifier
            .fillMaxHeight()
            .width(28.dp)
            .systemGestureExclusion()
            .clearAndSetSemantics { },
    ) {
        val heightPx = constraints.maxHeight.toFloat()
        val perLetter = maxHeight / sections.size.coerceAtLeast(1)
        // On short screens (landscape, split screen) show every Nth letter but keep every one reachable.
        val step = ceil(14.dp / perLetter).toInt().coerceAtLeast(1)

        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .pointerInput(sections, heightPx) {
                    fun select(y: Float) {
                        val index = ((y / heightPx) * sections.size).toInt().coerceIn(0, sections.lastIndex)
                        touchY = y.roundToInt()
                        if (index != active) {
                            active = index
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSectionState(index)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        select(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            select(change.position.y)
                        }
                        active = -1
                    }
                },
        ) {
            sections.forEachIndexed { i, letter ->
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (i % step == 0) letter else "•",
                        fontSize = 11.sp,
                        fontWeight = if (i == active) FontWeight.Bold else FontWeight.Medium,
                        color = if (i == active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }

        // Large preview bubble next to the finger.
        if (active in sections.indices) {
            val bubble = 56.dp
            val bubblePx = with(density) { bubble.roundToPx() }
            Box(
                Modifier
                    .zIndex(1f)
                    .offset { IntOffset(-bubblePx - 16, (touchY - bubblePx / 2).coerceIn(0, (heightPx - bubblePx).toInt().coerceAtLeast(0))) }
                    .size(bubble)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    sections[active],
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
