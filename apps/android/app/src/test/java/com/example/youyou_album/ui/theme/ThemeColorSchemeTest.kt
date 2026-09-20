package com.example.youyou_album.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorSchemeTest {

    @Test
    fun `all material color roles are owned by the youyou palette`() {
        assertEquals(Surface, LightColorScheme.surfaceContainerLow)

        val allowedColors = setOf(
            Primary,
            PrimaryDark,
            OnPrimary,
            Scaffold,
            Surface,
            SurfaceContainer,
            SurfaceContainerHigh,
            OnSurface,
            OnSurfaceVariant,
            Error,
            OnError,
            Success,
            DarkSurface,
            DarkOnSurface,
            DarkOnSurfaceVariant,
            DarkSurfaceContainerHighest,
            DarkSurfaceContainerHigh,
            Outline,
            OutlineVariant,
            DarkOutline,
            DarkOutlineVariant,
            Primary.copy(alpha = 0.15f),
            Success.copy(alpha = 0.15f),
            Error.copy(alpha = 0.12f),
            Error.copy(alpha = 0.18f),
            Color.Black,
            Color.White,
            Color.Transparent,
        )

        listOf(LightColorScheme, DarkColorScheme).forEach { scheme ->
            assertTrue(
                "ColorScheme contains a color outside the Youyou palette: $scheme",
                scheme.allRoles().all(allowedColors::contains),
            )
        }
    }

    @Test
    fun `action and supporting text remain readable on page and dialog surfaces`() {
        listOf(LightColorScheme, DarkColorScheme).forEach { scheme ->
            listOf(scheme.background, scheme.surface, scheme.surfaceContainerHigh).forEach { background ->
                listOf(scheme.onSurface, scheme.onSurfaceVariant).forEach { foreground ->
                    val lighter = maxOf(foreground.luminance(), background.luminance())
                    val darker = minOf(foreground.luminance(), background.luminance())
                    assertTrue("Text contrast must be at least 4.5:1", (lighter + 0.05f) / (darker + 0.05f) >= 4.5f)
                }
            }
        }
    }
}

private fun ColorScheme.allRoles(): List<Color> = listOf(
    primary,
    onPrimary,
    primaryContainer,
    onPrimaryContainer,
    inversePrimary,
    secondary,
    onSecondary,
    secondaryContainer,
    onSecondaryContainer,
    tertiary,
    onTertiary,
    tertiaryContainer,
    onTertiaryContainer,
    background,
    onBackground,
    surface,
    onSurface,
    surfaceVariant,
    onSurfaceVariant,
    surfaceTint,
    inverseSurface,
    inverseOnSurface,
    error,
    onError,
    errorContainer,
    onErrorContainer,
    outline,
    outlineVariant,
    scrim,
    surfaceBright,
    surfaceDim,
    surfaceContainer,
    surfaceContainerHigh,
    surfaceContainerHighest,
    surfaceContainerLow,
    surfaceContainerLowest,
)
