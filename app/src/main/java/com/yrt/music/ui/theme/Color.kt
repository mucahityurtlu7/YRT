package com.yrt.music.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Base Obsidian & AMOLED Backgrounds
val ObsidianBlack = Color(0xFF0B0B0F)
val ObsidianDeep = Color(0xFF07070A)
val AmoledDarkBackground = Color(0xFF0D0D12)

// Tiered Surface Hierarchy (Card Elevation)
val SurfaceDark = Color(0xFF121218)
val SurfaceCard = Color(0xFF16161E)
val SurfaceCardElevated = Color(0xFF22222E)
val SurfaceCardHighlight = Color(0xFF2C2C3C)
val SurfaceVariantDark = Color(0xFF1E1E2A)

// Crimson Streaming Accent Tokens
val CrimsonPrimary = Color(0xFFFF0033)
val CrimsonDark = Color(0xFFE60026)
val CrimsonHover = Color(0xFFFF2A55)
val CrimsonContainer = Color(0xFF3B000C)
val OnCrimsonContainer = Color(0xFFFFD9DC)

// Text & On-Surface Tokens
val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFFA0A0B2)
val TextTertiary = Color(0xFF6E6E82)
val TextOnAccent = Color(0xFFFFFFFF)

// Outlines & Dividers
val OutlineDark = Color(0xFF2A2A38)
val OutlineVariantDark = Color(0xFF1B1B26)

// Semantic Streaming Tokens
val StreamingSuccess = Color(0xFF00E676)
val StreamingProgress = Color(0xFF2979FF)
val StreamingError = Color(0xFFFF5252)
val StreamingWarning = Color(0xFFFFB300)
val BadgeLossless = Color(0xFFFFD700)

/**
 * Functional semantic container for streaming colors and gradient scrims.
 */
object StreamingColors {
    val Success = StreamingSuccess
    val Progress = StreamingProgress
    val Error = StreamingError
    val Warning = StreamingWarning
    val LosslessBadge = BadgeLossless
    val AmoledDark = AmoledDarkBackground

    fun createHeroScrim(targetBackground: Color = ObsidianBlack): Brush = Brush.verticalGradient(
        0.00f to Color.Black.copy(alpha = 0.50f),
        0.30f to Color.Transparent,
        0.65f to targetBackground.copy(alpha = 0.70f),
        0.88f to targetBackground.copy(alpha = 0.95f),
        1.00f to targetBackground,
    )
}
