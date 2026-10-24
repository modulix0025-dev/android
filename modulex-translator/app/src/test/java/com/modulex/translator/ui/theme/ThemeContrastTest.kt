package com.modulex.translator.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x contrast for every text/background pairing the UI uses, in both themes:
 * text >= 7:1 (AAA), outlines and component boundaries >= 3:1, and the identity stays greyscale.
 */
class ThemeContrastTest {

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double {
        val argb = c.toArgb()
        return 0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun textPairs(s: ColorScheme): List<Triple<String, Color, Color>> {
        val surfaces = listOf(
            "background" to s.background, "surface" to s.surface, "surfaceVariant" to s.surfaceVariant,
            "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
            "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
            "surfaceContainerHighest" to s.surfaceContainerHighest, "primaryContainer" to s.primaryContainer,
            "secondaryContainer" to s.secondaryContainer, "tertiaryContainer" to s.tertiaryContainer,
        )
        // Text drawn on any page or card colour: body text, secondary text, links/buttons (primary).
        val onAny = surfaces.flatMap { (n, bg) ->
            listOf(
                Triple("onSurface/$n", s.onSurface, bg),
                Triple("onSurfaceVariant/$n", s.onSurfaceVariant, bg),
                Triple("primary/$n", s.primary, bg),
                Triple("onBackground/$n", s.onBackground, bg),
            )
        }
        return onAny + listOf(
            Triple("onPrimary/primary", s.onPrimary, s.primary),
            Triple("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
            Triple("onSecondary/secondary", s.onSecondary, s.secondary),
            Triple("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
            Triple("onTertiary/tertiary", s.onTertiary, s.tertiary),
            Triple("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
            Triple("onError/error", s.onError, s.error),
            Triple("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer),
            Triple("inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface),
            Triple("error/surface", s.error, s.surface),
            Triple("error/background", s.error, s.background),
        )
    }

    private fun check(name: String, s: ColorScheme) {
        val failures = mutableListOf<String>()
        for ((pair, fg, bg) in textPairs(s)) {
            val r = contrast(fg, bg)
            if (r < 7.0) failures += "$name $pair = %.2f (needs 7.0)".format(r)
        }
        for ((n, bg) in listOf("surface" to s.surface, "background" to s.background, "surfaceVariant" to s.surfaceVariant)) {
            val r = contrast(s.outline, bg)
            if (r < 3.0) failures += "$name outline/$n = %.2f (needs 3.0)".format(r)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun lightThemeMeetsAaa() = check("light", LightColors)

    @Test fun darkThemeMeetsAaa() = check("dark", DarkColors)

    @Test
    fun identityIsBlackAndWhite() {
        for ((name, s) in listOf("light" to LightColors, "dark" to DarkColors)) {
            val all = listOf(
                s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer, s.secondary, s.onSecondary,
                s.secondaryContainer, s.onSecondaryContainer, s.tertiary, s.onTertiary, s.tertiaryContainer,
                s.onTertiaryContainer, s.background, s.onBackground, s.surface, s.onSurface, s.surfaceVariant,
                s.onSurfaceVariant, s.error, s.onError, s.errorContainer, s.onErrorContainer, s.outline,
                s.outlineVariant, s.inverseSurface, s.inverseOnSurface, s.inversePrimary, s.surfaceTint,
                s.surfaceBright, s.surfaceDim, s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer,
                s.surfaceContainerHigh, s.surfaceContainerHighest,
            )
            for (c in all) {
                val argb = c.toArgb()
                val r = argb shr 16 and 0xFF; val g = argb shr 8 and 0xFF; val b = argb and 0xFF
                assertTrue("$name: #%08X is not grey".format(argb), r == g && g == b)
            }
        }
    }
}
