package com.modulex.translator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.modulex.translator.data.ThemeMode

// ModuleX identity: black and white only. Every role of the Material 3 scheme is set explicitly so
// no default (tinted) colour leaks into a component; text/background pairs are held to WCAG AAA
// (7:1) and outlines to 3:1 by ThemeContrastTest.
internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF000000), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E6E6), onPrimaryContainer = Color(0xFF000000),
    inversePrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF1F1F1F), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBEBEB), onSecondaryContainer = Color(0xFF000000),
    tertiary = Color(0xFF3D3D3D), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0E0E0), onTertiaryContainer = Color(0xFF000000),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF383838),
    surfaceTint = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF121212), inverseOnSurface = Color(0xFFF5F5F5),
    error = Color(0xFF000000), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF141414), onErrorContainer = Color(0xFFFFFFFF),
    outline = Color(0xFF5E5E5E), outlineVariant = Color(0xFFC4C4C4),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFDDDDDD),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF3F3F3), surfaceContainerHigh = Color(0xFFEEEEEE),
    surfaceContainerHighest = Color(0xFFE8E8E8),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF), onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF262626), onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF000000),
    secondary = Color(0xFFE6E6E6), onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF222222), onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFC2C2C2), onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF2E2E2E), onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF000000), onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0A0A0A), onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1C1C1C), onSurfaceVariant = Color(0xFFCFCFCF),
    surfaceTint = Color(0xFF0A0A0A),
    inverseSurface = Color(0xFFF0F0F0), inverseOnSurface = Color(0xFF0A0A0A),
    error = Color(0xFFFFFFFF), onError = Color(0xFF000000),
    errorContainer = Color(0xFFF2F2F2), onErrorContainer = Color(0xFF000000),
    outline = Color(0xFF9C9C9C), outlineVariant = Color(0xFF3A3A3A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A2A2A), surfaceDim = Color(0xFF0A0A0A),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF0F0F0F),
    surfaceContainer = Color(0xFF141414), surfaceContainerHigh = Color(0xFF1B1B1B),
    surfaceContainerHighest = Color(0xFF242424),
)

/** Status colours that are not part of Material's scheme (monochrome as well). */
data class StatusColors(val live: Color, val ok: Color, val warn: Color)

private val LightStatus = StatusColors(live = Color(0xFF000000), ok = Color(0xFF000000), warn = Color(0xFF000000))
private val DarkStatus = StatusColors(live = Color(0xFFFFFFFF), ok = Color(0xFFFFFFFF), warn = Color(0xFFFFFFFF))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Whether the app (not the system) is currently drawn dark; drives the status bar icon colour. */
val LocalAppIsDark = staticCompositionLocalOf { false }

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun ModuleXTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Fixed black-and-white identity instead of dynamic (wallpaper) colour.
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus, LocalAppIsDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
