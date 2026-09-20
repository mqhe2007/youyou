package com.example.youyou_album.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ---- 圆角（按元素层级递增）----
val RadiusSm = 8.dp    // 按钮、输入框、badge
val RadiusMd = 12.dp   // 卡片、列表项
val RadiusLg = 16.dp   // Dialog、bottom sheet
val RadiusXl = 24.dp   // hero、全屏面板

internal val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = OnPrimary,
    inversePrimary = PrimaryDark,
    secondary = Primary,
    onSecondary = OnPrimary,
    secondaryContainer = Primary.copy(alpha = 0.15f),
    onSecondaryContainer = OnPrimary,
    tertiary = Success,
    onTertiary = Color.White,
    tertiaryContainer = Success.copy(alpha = 0.15f),
    onTertiaryContainer = Success,
    background = Scaffold,
    onBackground = OnSurface,
    surface = Scaffold,
    onSurface = OnSurface,
    surfaceVariant = SurfaceContainer,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceTint = Color.Transparent,
    inverseSurface = OnSurface,
    inverseOnSurface = Surface,
    error = Error,
    onError = OnError,
    errorContainer = Error.copy(alpha = 0.12f),
    onErrorContainer = Error,
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Color.Black,
    surfaceBright = Surface,
    surfaceDim = SurfaceContainerHigh,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHigh,
    surfaceContainerLow = Surface,
    surfaceContainerLowest = Surface,
)

internal val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = OnPrimary,
    inversePrimary = Primary,
    secondary = Primary,
    onSecondary = OnPrimary,
    secondaryContainer = Primary.copy(alpha = 0.15f),
    onSecondaryContainer = OnPrimary,
    tertiary = Success,
    onTertiary = Color.White,
    tertiaryContainer = Success.copy(alpha = 0.15f),
    onTertiaryContainer = Success,
    background = DarkSurface,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceContainerHigh,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = Color.Transparent,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = DarkSurface,
    error = Error,
    onError = OnError,
    errorContainer = Error.copy(alpha = 0.18f),
    onErrorContainer = Error,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = Color.Black,
    surfaceBright = DarkSurfaceContainerHighest,
    surfaceDim = DarkSurface,
    surfaceContainer = DarkSurfaceContainerHigh,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceContainerLow = DarkSurface,
    surfaceContainerLowest = DarkSurface,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(RadiusSm),
    small = RoundedCornerShape(RadiusSm),
    medium = RoundedCornerShape(RadiusMd),
    large = RoundedCornerShape(RadiusLg),
    extraLarge = RoundedCornerShape(RadiusXl),
)

@Composable
fun YouyouTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 状态栏：浅色主题用 scaffold 色 + 深色图标；深色主题用 dark surface + 浅色图标
            window.statusBarColor = if (darkTheme) DarkSurface.toArgb() else Scaffold.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            // 导航栏
            window.navigationBarColor = if (darkTheme) DarkSurface.toArgb() else Scaffold.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
    ) { content() }
}
