package app.haven.launcher.data

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** How icons should look. Part of the cache key, so changing a setting re-renders cleanly. */
data class IconStyle(
    val sizePx: Int,
    val shape: IconShape,
    /** Non-null = themed (monochrome) icons using these ARGB colors. */
    val themed: Pair<Int, Int>?,
    /** Icon pack package, or null for the apps' own icons. */
    val iconPack: String? = null,
)

/**
 * Renders and caches app icons. Applies the user's shape, optional themed (monochrome) style,
 * and the system work/private/clone badge so every profile stays recognisable.
 */
class IconCache(private val context: Context, val iconPacks: IconPacks = IconPacks(context)) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val myUser = Process.myUserHandle()

    // Sized by bytes: ~1/8 of the app's heap is plenty for several hundred icons.
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun peek(key: String): ImageBitmap? = cache.get(key)

    fun clear() = cache.evictAll()

    fun invalidatePackage(packageName: String) {
        cache.snapshot().keys.filter { it.startsWith("$packageName/") || it.startsWith("sc:$packageName#") }
            .forEach(cache::remove)
    }

    fun keyFor(app: InstalledApp, style: IconStyle) = "${app.key}|$style"

    fun keyFor(shortcut: ShortcutInfo, style: IconStyle) =
        "sc:${shortcut.`package`}#${shortcut.userHandle.hashCode()}#${shortcut.id}|$style"

    /** Blocking; call from a background thread. */
    fun load(app: InstalledApp, style: IconStyle): ImageBitmap? {
        val key = keyFor(app, style)
        cache.get(key)?.let { return it }
        val density = context.resources.displayMetrics.densityDpi
        // Icon pack first (if chosen and it has this app), then the app's own icon.
        val drawable = style.iconPack?.let { iconPacks.drawableFor(it, app.component, density) }
            ?: try {
                app.info.getIcon(density)
            } catch (e: Exception) {
                null
            } ?: context.packageManager.defaultActivityIcon
        return render(drawable, app.user, style)?.also { cache.put(key, it) }
    }

    /** Blocking; call from a background thread. */
    fun load(shortcut: ShortcutInfo, style: IconStyle): ImageBitmap? {
        val key = keyFor(shortcut, style)
        cache.get(key)?.let { return it }
        val drawable = try {
            launcherApps.getShortcutIconDrawable(shortcut, context.resources.displayMetrics.densityDpi)
        } catch (e: Exception) {
            null
        } ?: return null
        return render(drawable, shortcut.userHandle, style)?.also { cache.put(key, it) }
    }

    private fun render(source: Drawable, user: UserHandle, style: IconStyle): ImageBitmap? = try {
        val size = style.sizePx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawable = source.mutate()
        if (drawable is AdaptiveIconDrawable) {
            drawAdaptive(canvas, drawable, size, style)
        } else {
            // Legacy icons keep their own shape; masking them would cut off logos.
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
        val badged = if (user != myUser) {
            // Work/private/clone badge comes from the system so it always matches Settings.
            val badgedDrawable = context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, bitmap), user)
            if (badgedDrawable is BitmapDrawable && badgedDrawable.bitmap != null) {
                badgedDrawable.bitmap
            } else {
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { out ->
                    badgedDrawable.setBounds(0, 0, size, size)
                    badgedDrawable.draw(Canvas(out))
                }
            }
        } else bitmap
        badged.asImageBitmap()
    } catch (e: Exception) {
        null
    }

    private fun drawAdaptive(canvas: Canvas, icon: AdaptiveIconDrawable, size: Int, style: IconStyle) {
        icon.setBounds(0, 0, size, size)
        val clip: Path = shapePath(style.shape, size) ?: icon.iconMask
        val monochrome = if (style.themed != null && Build.VERSION.SDK_INT >= 33) icon.monochrome else null

        if (style.shape == IconShape.SYSTEM && monochrome == null) {
            icon.draw(canvas) // Exactly what the system shows.
            return
        }
        // Layers are 108dp with the visible area being the middle 72dp, i.e. a quarter on each side.
        val inset = size / 4
        fun Drawable.drawLayer() {
            setBounds(-inset, -inset, size + inset, size + inset)
            draw(canvas)
        }
        val save = canvas.save()
        canvas.clipPath(clip)
        if (monochrome != null && style.themed != null) {
            val (background, foreground) = style.themed
            canvas.drawColor(background)
            monochrome.mutate().setTint(foreground)
            monochrome.drawLayer()
        } else {
            icon.background?.drawLayer()
            icon.foreground?.drawLayer()
        }
        canvas.restoreToCount(save)
    }

    private fun shapePath(shape: IconShape, size: Int): Path? {
        val s = size.toFloat()
        return when (shape) {
            IconShape.SYSTEM -> null
            IconShape.CIRCLE -> Path().apply { addCircle(s / 2, s / 2, s / 2, Path.Direction.CW) }
            IconShape.ROUNDED_SQUARE -> Path().apply { addRoundRect(0f, 0f, s, s, s * 0.22f, s * 0.22f, Path.Direction.CW) }
            IconShape.SQUIRCLE -> superellipse(s)
        }
    }

    /** |x|^4 + |y|^4 = 1, sampled. The soft "squircle" shape. */
    private fun superellipse(s: Float): Path {
        val r = s / 2
        val n = 4.0
        val path = Path()
        val steps = 96
        for (i in 0..steps) {
            val t = 2 * Math.PI * i / steps
            val c = cos(t)
            val sn = sin(t)
            val x = r + r * sign(c) * abs(c).pow(2 / n)
            val y = r + r * sign(sn) * abs(sn).pow(2 / n)
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close()
        return path
    }
}
