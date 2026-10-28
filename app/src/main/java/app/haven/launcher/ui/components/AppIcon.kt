package app.haven.launcher.ui.components

import android.content.pm.ShortcutInfo
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.haven.launcher.data.AppItem
import app.haven.launcher.data.IconShape
import app.haven.launcher.data.IconStyle
import app.haven.launcher.haven
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Icon shape + themed icon preferences, provided once at the root. */
data class IconPrefs(val shape: IconShape = IconShape.SYSTEM, val themed: Boolean = false)

val LocalIconPrefs = compositionLocalOf { IconPrefs() }

@Composable
private fun rememberIconStyle(size: Dp): IconStyle {
    val prefs = LocalIconPrefs.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val scheme = MaterialTheme.colorScheme
    val themed = if (prefs.themed) scheme.secondaryContainer.toArgb() to scheme.onSecondaryContainer.toArgb() else null
    return remember(px, prefs, themed) { IconStyle(px, prefs.shape, themed) }
}

private val greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * App icon that loads off the main thread. Shows a soft placeholder until ready, so scrolling
 * a long list never stutters. Paused or suspended apps are drawn greyed out.
 */
@Composable
fun AppIcon(item: AppItem, size: Dp, modifier: Modifier = Modifier) {
    val cache = LocalContext.current.haven.icons
    val style = rememberIconStyle(size)
    val key = cache.keyFor(item.app, style)
    val bitmap by produceState(initialValue = cache.peek(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) { cache.load(item.app, style) }
    }
    IconImage(bitmap, size, dimmed = item.isPaused || item.app.isSuspended, modifier)
}

@Composable
fun ShortcutIcon(info: ShortcutInfo, size: Dp, modifier: Modifier = Modifier) {
    val cache = LocalContext.current.haven.icons
    val style = rememberIconStyle(size)
    val key = cache.keyFor(info, style)
    val bitmap by produceState(initialValue = cache.peek(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) { cache.load(info, style) }
    }
    IconImage(bitmap, size, dimmed = false, modifier)
}

@Composable
private fun IconImage(bitmap: ImageBitmap?, size: Dp, dimmed: Boolean, modifier: Modifier) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null, // The label next to it is the accessible name.
            modifier = modifier
                .size(size)
                .then(if (dimmed) Modifier.alpha(0.55f) else Modifier),
            colorFilter = if (dimmed) greyscale else null,
        )
    } else {
        Box(
            modifier
                .size(size)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape),
        )
    }
}

/** Converts a composable's position to the Rect used for the open-from-icon animation. */
fun LayoutCoordinates.windowRect(): Rect {
    val b = boundsInWindow()
    return Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
}
