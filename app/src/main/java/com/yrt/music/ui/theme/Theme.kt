package com.yrt.music.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val YrtDarkColorScheme = darkColorScheme(
    primary = CrimsonPrimary,
    onPrimary = TextOnAccent,
    primaryContainer = CrimsonContainer,
    onPrimaryContainer = OnCrimsonContainer,
    secondary = Color(0xFF7F8CFF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2A2A3A),
    onSecondaryContainer = Color(0xFFE2E2F0),
    tertiary = StreamingWarning,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF3E2D00),
    onTertiaryContainer = Color(0xFFFFDF9E),
    background = ObsidianBlack,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianDeep,
    surfaceContainerLow = ObsidianBlack,
    surfaceContainer = SurfaceCard,
    surfaceContainerHigh = SurfaceCardElevated,
    surfaceContainerHighest = SurfaceCardHighlight,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = StreamingError,
    onError = Color.Black,
    errorContainer = Color(0xFF4D0009),
    onErrorContainer = Color(0xFFFFDAD6),
)

val YrtLightColorScheme = lightColorScheme(
    primary = CrimsonPrimary,
    onPrimary = TextOnAccent,
    primaryContainer = Color(0xFFFFD9DC),
    onPrimaryContainer = Color(0xFF410009),
    secondary = Color(0xFF4C58A5),
    onSecondary = Color.White,
    background = Color(0xFFF9F9FC),
    onBackground = Color(0xFF1B1B20),
    surface = Color.White,
    onSurface = Color(0xFF1B1B20),
    surfaceVariant = Color(0xFFE6E6EE),
    onSurfaceVariant = Color(0xFF454552),
    outline = Color(0xFF767682),
    error = StreamingError,
    onError = Color.White,
)

@Composable
fun YrtTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> YrtDarkColorScheme
        else -> YrtLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = YrtTypography,
        content = content,
    )
}
