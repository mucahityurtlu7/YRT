package com.yrt.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.yrt.music.ui.theme.AmoledDarkBackground
import com.yrt.music.ui.theme.BadgeLossless
import com.yrt.music.ui.theme.CrimsonContainer
import com.yrt.music.ui.theme.CrimsonDark
import com.yrt.music.ui.theme.CrimsonHover
import com.yrt.music.ui.theme.CrimsonPrimary
import com.yrt.music.ui.theme.ObsidianBlack
import com.yrt.music.ui.theme.ObsidianDeep
import com.yrt.music.ui.theme.OnCrimsonContainer
import com.yrt.music.ui.theme.OutlineDark
import com.yrt.music.ui.theme.OutlineVariantDark
import com.yrt.music.ui.theme.StreamingColors
import com.yrt.music.ui.theme.StreamingError
import com.yrt.music.ui.theme.StreamingProgress
import com.yrt.music.ui.theme.StreamingSuccess
import com.yrt.music.ui.theme.StreamingWarning
import com.yrt.music.ui.theme.SurfaceCard
import com.yrt.music.ui.theme.SurfaceCardElevated
import com.yrt.music.ui.theme.SurfaceCardHighlight
import com.yrt.music.ui.theme.SurfaceDark
import com.yrt.music.ui.theme.SurfaceVariantDark
import com.yrt.music.ui.theme.TextOnAccent
import com.yrt.music.ui.theme.TextPrimary
import com.yrt.music.ui.theme.TextSecondary
import com.yrt.music.ui.theme.TextTertiary
import com.yrt.music.ui.theme.YrtDarkColorScheme
import com.yrt.music.ui.theme.YrtLightColorScheme
import com.yrt.music.ui.theme.YrtTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeAndTypographyTest {

    @Test
    fun testBaseObsidianTokens() {
        assertEquals(Color(0xFF0B0B0F), ObsidianBlack)
        assertEquals(Color(0xFF07070A), ObsidianDeep)
        assertEquals(Color(0xFF0D0D12), AmoledDarkBackground)
    }

    @Test
    fun testTieredSurfaceTokens() {
        assertEquals(Color(0xFF121218), SurfaceDark)
        assertEquals(Color(0xFF16161E), SurfaceCard)
        assertEquals(Color(0xFF22222E), SurfaceCardElevated)
        assertEquals(Color(0xFF2C2C3C), SurfaceCardHighlight)
        assertEquals(Color(0xFF1E1E2A), SurfaceVariantDark)
    }

    @Test
    fun testCrimsonStreamingAccentTokens() {
        assertEquals(Color(0xFFFF0033), CrimsonPrimary)
        assertEquals(Color(0xFFE60026), CrimsonDark)
        assertEquals(Color(0xFFFF2A55), CrimsonHover)
        assertEquals(Color(0xFF3B000C), CrimsonContainer)
        assertEquals(Color(0xFFFFD9DC), OnCrimsonContainer)
    }

    @Test
    fun testTextAndOutlineTokens() {
        assertEquals(Color(0xFFF5F5F7), TextPrimary)
        assertEquals(Color(0xFFA0A0B2), TextSecondary)
        assertEquals(Color(0xFF6E6E82), TextTertiary)
        assertEquals(Color(0xFFFFFFFF), TextOnAccent)
        assertEquals(Color(0xFF2A2A38), OutlineDark)
        assertEquals(Color(0xFF1B1B26), OutlineVariantDark)
    }

    @Test
    fun testSemanticStreamingTokens() {
        assertEquals(Color(0xFF00E676), StreamingSuccess)
        assertEquals(Color(0xFF2979FF), StreamingProgress)
        assertEquals(Color(0xFFFF5252), StreamingError)
        assertEquals(Color(0xFFFFB300), StreamingWarning)
        assertEquals(Color(0xFFFFD700), BadgeLossless)

        assertEquals(StreamingSuccess, StreamingColors.Success)
        assertEquals(StreamingProgress, StreamingColors.Progress)
        assertEquals(StreamingError, StreamingColors.Error)
        assertEquals(StreamingWarning, StreamingColors.Warning)
        assertEquals(BadgeLossless, StreamingColors.LosslessBadge)
        assertEquals(AmoledDarkBackground, StreamingColors.AmoledDark)
    }

    @Test
    fun testDarkColorSchemeMapping() {
        assertEquals(CrimsonPrimary, YrtDarkColorScheme.primary)
        assertEquals(TextOnAccent, YrtDarkColorScheme.onPrimary)
        assertEquals(CrimsonContainer, YrtDarkColorScheme.primaryContainer)
        assertEquals(OnCrimsonContainer, YrtDarkColorScheme.onPrimaryContainer)
        assertEquals(ObsidianBlack, YrtDarkColorScheme.background)
        assertEquals(TextPrimary, YrtDarkColorScheme.onBackground)
        assertEquals(SurfaceCard, YrtDarkColorScheme.surface)
        assertEquals(TextPrimary, YrtDarkColorScheme.onSurface)
        assertEquals(SurfaceVariantDark, YrtDarkColorScheme.surfaceVariant)
        assertEquals(TextSecondary, YrtDarkColorScheme.onSurfaceVariant)
        assertEquals(SurfaceCardElevated, YrtDarkColorScheme.surfaceContainerHigh)
        assertEquals(StreamingError, YrtDarkColorScheme.error)
    }

    @Test
    fun testLightColorSchemeFallback() {
        assertNotNull(YrtLightColorScheme)
        assertEquals(CrimsonPrimary, YrtLightColorScheme.primary)
    }

    @Test
    fun testTypographyScaleCoverage() {
        // Display tier
        assertEquals(57.sp, YrtTypography.displayLarge.fontSize)
        assertEquals(45.sp, YrtTypography.displayMedium.fontSize)
        assertEquals(36.sp, YrtTypography.displaySmall.fontSize)

        // Headline tier
        assertEquals(32.sp, YrtTypography.headlineLarge.fontSize)
        assertEquals(28.sp, YrtTypography.headlineMedium.fontSize)
        assertEquals(24.sp, YrtTypography.headlineSmall.fontSize)

        // Title tier
        assertEquals(20.sp, YrtTypography.titleLarge.fontSize)
        assertEquals(16.sp, YrtTypography.titleMedium.fontSize)
        assertEquals(14.sp, YrtTypography.titleSmall.fontSize)

        // Body tier
        assertEquals(16.sp, YrtTypography.bodyLarge.fontSize)
        assertEquals(14.sp, YrtTypography.bodyMedium.fontSize)
        assertEquals(12.sp, YrtTypography.bodySmall.fontSize)

        // Label tier
        assertEquals(14.sp, YrtTypography.labelLarge.fontSize)
        assertEquals(12.sp, YrtTypography.labelMedium.fontSize)
        assertEquals(11.sp, YrtTypography.labelSmall.fontSize)

        // Weights
        assertEquals(FontWeight.Bold, YrtTypography.displayLarge.fontWeight)
        assertEquals(FontWeight.SemiBold, YrtTypography.headlineLarge.fontWeight)
        assertEquals(FontWeight.SemiBold, YrtTypography.titleLarge.fontWeight)
        assertEquals(FontWeight.Medium, YrtTypography.titleMedium.fontWeight)
        assertEquals(FontWeight.Normal, YrtTypography.bodyLarge.fontWeight)
        assertEquals(FontWeight.SemiBold, YrtTypography.labelLarge.fontWeight)
    }

    @Test
    fun testHeroScrimGradientCreation() {
        val scrim = StreamingColors.createHeroScrim(ObsidianBlack)
        assertNotNull(scrim)
    }
}
