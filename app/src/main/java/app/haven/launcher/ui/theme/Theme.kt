package app.haven.launcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.haven.launcher.data.LauncherSettings
import app.haven.launcher.data.ThemeMode

// A quiet, warm neutral palette for devices without Material You.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5368),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F4),
    onPrimaryContainer = Color(0xFF0D1E2B),
    secondary = Color(0xFF52606D),
    secondaryContainer = Color(0xFFDDE3EA),
    onSecondaryContainer = Color(0xFF101C26),
    background = Color(0xFFF7F7F5),
    surface = Color(0xFFF7F7F5),
    surfaceVariant = Color(0xFFE2E3E1),
    surfaceContainerHigh = Color(0xFFECEDEB),
    onSurface = Color(0xFF1B1C1C),
    onSurfaceVariant = Color(0xFF45474A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C8E2),
    onPrimary = Color(0xFF10324A),
    primaryContainer = Color(0xFF2A4A62),
    onPrimaryContainer = Color(0xFFD3E4F4),
    secondary = Color(0xFFBAC7D4),
    secondaryContainer = Color(0xFF39444F),
    onSecondaryContainer = Color(0xFFDDE3EA),
    background = Color(0xFF121314),
    surface = Color(0xFF121314),
    surfaceVariant = Color(0xFF2D2F31),
    surfaceContainerHigh = Color(0xFF232527),
    onSurface = Color(0xFFE3E3E1),
    onSurfaceVariant = Color(0xFFC4C6C9),
)

@Composable
fun isDarkTheme(settings: LauncherSettings): Boolean = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun HavenTheme(settings: LauncherSettings, content: @Composable () -> Unit) {
    val dark = isDarkTheme(settings)
    val context = LocalContext.current
    val colors: ColorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    // The user's text size multiplies the system font scale, so accessibility settings still apply.
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * settings.textScale),
    ) {
        MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
    }
}
