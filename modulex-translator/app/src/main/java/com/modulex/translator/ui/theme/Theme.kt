package com.modulex.translator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.modulex.translator.data.ThemeMode

// ModuleX brand palette: deep navy + clear blue, with a warm accent for live/recording states.
private val Navy = Color(0xFF0B2E6F)
private val Blue = Color(0xFF1B5FD9)
private val SkyBlue = Color(0xFF7FB2FF)
private val Coral = Color(0xFFE5484D)
private val Mint = Color(0xFF12A594)

private val Light: ColorScheme = lightColorScheme(
    primary = Blue, onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF), onPrimaryContainer = Navy,
    secondary = Mint, onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F4EE), onSecondaryContainer = Color(0xFF00382F),
    tertiary = Coral, onTertiary = Color.White,
    background = Color(0xFFF7F9FC), onBackground = Color(0xFF111827),
    surface = Color.White, onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEEF2F8), onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFB42318), onError = Color.White,
)

private val Dark: ColorScheme = darkColorScheme(
    primary = SkyBlue, onPrimary = Color(0xFF002A6B),
    primaryContainer = Color(0xFF1E3A75), onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFF4FD1BC), onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0E4D43), onSecondaryContainer = Color(0xFFD3F4EE),
    tertiary = Color(0xFFFF8A8E), onTertiary = Color(0xFF5C0A0E),
    background = Color(0xFF0F1419), onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF161C24), onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF222A35), onSurfaceVariant = Color(0xFFAAB4C3),
    outline = Color(0xFF3B4555),
    error = Color(0xFFFF8A80), onError = Color(0xFF5C0A0E),
)

/** Status colors that are not part of Material's scheme. */
data class StatusColors(val live: Color, val ok: Color, val warn: Color)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(Coral, Mint, Color(0xFFB54708)) }

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
    // Brand colors are used instead of dynamic (wallpaper) color on purpose: consistent identity.
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
