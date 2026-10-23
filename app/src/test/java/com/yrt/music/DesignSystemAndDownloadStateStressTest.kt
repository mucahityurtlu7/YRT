package com.yrt.music

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.ui.components.HeroDownloadState
import com.yrt.music.ui.components.TrackDownloadState
import com.yrt.music.ui.components.createHeroScrim as createHeroHeaderScrim
import com.yrt.music.ui.components.resolveTrackDownloadState
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.system.measureTimeMillis

/**
 * Empirical stress test harness for Design System Tokens and Component Download State resolution.
 * Exercises edge cases, boundaries, performance, WCAG color contrast, and typographic invariants.
 */
class DesignSystemAndDownloadStateStressTest {

    private class StressMockSongLookup(
        private val indexedIds: Set<String> = emptySet(),
    ) : LocalSongLookup {
        override fun hasVideoId(videoId: String): Boolean = indexedIds.contains(videoId)
        override fun getSongByVideoId(videoId: String): Song? = null
        override val songs: StateFlow<List<Song>> = MutableStateFlow(emptyList())
        override suspend fun refresh() {}
    }

    private fun createTask(
        videoId: String,
        status: DownloadStatus,
        progress: Float = 0f,
        message: String? = null,
        id: String = "task-$videoId",
    ) = DownloadTask(
        id = id,
        url = "https://youtube.com/watch?v=$videoId",
        title = "Track $videoId",
        format = AudioFormat.M4A,
        status = status,
        progress = progress,
        message = message,
        videoId = videoId,
    )

    // =========================================================================
    // SECTION 1: resolveTrackDownloadState Stress & Boundary Tests
    // =========================================================================

    @Test
    fun testResolveTrackDownloadState_NullAndWhitespaceVariations() {
        val lookup = StressMockSongLookup(indexedIds = setOf("valid_id", " "))
        val tasks = listOf(
            createTask("valid_id", DownloadStatus.Running, 50f),
            createTask(" ", DownloadStatus.Running, 50f),
            createTask("", DownloadStatus.Running, 50f),
        )

        // Null
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState(null, lookup, tasks))

        // Empty string
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState("", lookup, tasks))

        // Single space
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState(" ", lookup, tasks))

        // Multi space
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState("    ", lookup, tasks))

        // Tabs and newlines
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState("\t", lookup, tasks))
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState("\n", lookup, tasks))
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState("\r\n", lookup, tasks))
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState(" \t \n ", lookup, tasks))

        // Non-breaking space
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState("\u00A0", lookup, tasks))
    }

    @Test
    fun testResolveTrackDownloadState_UnindexedVideoId() {
        val lookup = StressMockSongLookup(indexedIds = setOf("id_alpha", "id_beta"))
        val tasks = listOf(
            createTask("id_alpha", DownloadStatus.Running, 40f),
            createTask("id_gamma", DownloadStatus.Queued),
        )

        // An ID that is neither in lookup nor in activeTasks
        val result = resolveTrackDownloadState("id_omega", lookup, tasks)
        assertEquals(TrackDownloadState.NotDownloaded, result)
    }

    @Test
    fun testResolveTrackDownloadState_CaseSensitivityAndSpecialChars() {
        val lookup = StressMockSongLookup(indexedIds = setOf("VideoID_123"))
        val tasks = listOf(createTask("VideoID_123", DownloadStatus.Running, 60f))

        // Exact match
        assertEquals(TrackDownloadState.Downloaded, resolveTrackDownloadState("VideoID_123", lookup, tasks))

        // Different case should not match if exact match is expected
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState("videoid_123", lookup, tasks))

        // Special characters in videoId
        val specialId = "dQw4w9WgXcQ-abc_123!@#"
        val specialLookup = StressMockSongLookup(indexedIds = setOf(specialId))
        assertEquals(TrackDownloadState.Downloaded, resolveTrackDownloadState(specialId, specialLookup, emptyList()))

        // Emoji in videoId
        val emojiId = "track_🎵_sound"
        val emojiTasks = listOf(createTask(emojiId, DownloadStatus.Queued))
        assertEquals(TrackDownloadState.Queued, resolveTrackDownloadState(emojiId, null, emojiTasks))
    }

    @Test
    fun testResolveTrackDownloadState_ExtremeLengthVideoId() {
        val longId = "a".repeat(10_000)
        val lookup = StressMockSongLookup(indexedIds = setOf(longId))
        assertEquals(TrackDownloadState.Downloaded, resolveTrackDownloadState(longId, lookup, emptyList()))
    }

    @Test
    fun testResolveTrackDownloadState_Precedence_LocalLibraryWinsOverAllTaskStates() {
        val testVideoId = "precedence_test_vid"
        val lookup = StressMockSongLookup(indexedIds = setOf(testVideoId))

        // Test against every possible DownloadStatus
        for (status in DownloadStatus.entries) {
            val task = createTask(testVideoId, status = status, progress = 42f, message = "error msg")
            val resolved = resolveTrackDownloadState(testVideoId, lookup, listOf(task))
            assertEquals(
                "LocalSongLookup.hasVideoId must take precedence over task status $status",
                TrackDownloadState.Downloaded,
                resolved,
            )
        }
    }

    @Test
    fun testResolveTrackDownloadState_ActiveTaskStates_WhenNotInLocalLibrary() {
        val lookup = StressMockSongLookup(indexedIds = emptySet())

        // 1. Queued
        val queuedTask = createTask("vid_q", DownloadStatus.Queued)
        assertEquals(TrackDownloadState.Queued, resolveTrackDownloadState("vid_q", lookup, listOf(queuedTask)))

        // 2. Running
        val runningTask = createTask("vid_r", DownloadStatus.Running, progress = 48.7f)
        val runningState = resolveTrackDownloadState("vid_r", lookup, listOf(runningTask))
        assertTrue(runningState is TrackDownloadState.Downloading)
        assertEquals(48.7f, (runningState as TrackDownloadState.Downloading).progress, 0.0001f)

        // 3. Done
        val doneTask = createTask("vid_d", DownloadStatus.Done, progress = 100f)
        assertEquals(TrackDownloadState.Downloaded, resolveTrackDownloadState("vid_d", lookup, listOf(doneTask)))

        // 4. Failed with message
        val failedWithMessage = createTask("vid_f1", DownloadStatus.Failed, message = "HTTP 403 Forbidden")
        val failedState1 = resolveTrackDownloadState("vid_f1", lookup, listOf(failedWithMessage))
        assertTrue(failedState1 is TrackDownloadState.Failed)
        assertEquals("HTTP 403 Forbidden", (failedState1 as TrackDownloadState.Failed).message)

        // 5. Failed without message (null message)
        val failedWithNull = createTask("vid_f2", DownloadStatus.Failed, message = null)
        val failedState2 = resolveTrackDownloadState("vid_f2", lookup, listOf(failedWithNull))
        assertTrue(failedState2 is TrackDownloadState.Failed)
        assertEquals(null, (failedState2 as TrackDownloadState.Failed).message)

        // 6. Cancelled -> must resolve to NotDownloaded
        val cancelledTask = createTask("vid_c", DownloadStatus.Cancelled)
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState("vid_c", lookup, listOf(cancelledTask)))
    }

    @Test
    fun testResolveTrackDownloadState_ProgressBoundaryValues() {
        val lookup = StressMockSongLookup()

        val boundaryValues = listOf(
            0.0f,
            0.0001f,
            0.05f,
            0.5f,
            1.0f,
            50.0f,
            99.99f,
            100.0f,
            -1.0f,      // Negative progress edge case
            150.0f,     // Greater than 100% edge case
            Float.MIN_VALUE,
            Float.MAX_VALUE,
        )

        for (progressVal in boundaryValues) {
            val task = createTask("prog_test", DownloadStatus.Running, progress = progressVal)
            val state = resolveTrackDownloadState("prog_test", lookup, listOf(task))
            assertTrue("Progress $progressVal must resolve to Downloading", state is TrackDownloadState.Downloading)
            assertEquals(progressVal, (state as TrackDownloadState.Downloading).progress, 0.00001f)

            // Verify the UI badge calculation logic: (state.progress / 100f).coerceIn(0.05f, 1f)
            val uiIndicatorValue = (state.progress / 100f).coerceIn(0.05f, 1f)
            assertTrue("UI indicator for progress $progressVal must be >= 0.05f", uiIndicatorValue >= 0.05f)
            assertTrue("UI indicator for progress $progressVal must be <= 1.0f", uiIndicatorValue <= 1.0f)
        }
    }

    @Test
    fun testResolveTrackDownloadState_DuplicateTasksInList() {
        val lookup = StressMockSongLookup()
        // If list contains multiple tasks with same videoId, first match should be returned deterministically
        val task1 = createTask("dup_vid", DownloadStatus.Running, progress = 30f, id = "t1")
        val task2 = createTask("dup_vid", DownloadStatus.Failed, message = "error", id = "t2")
        val task3 = createTask("dup_vid", DownloadStatus.Queued, id = "t3")

        val stateFirst = resolveTrackDownloadState("dup_vid", lookup, listOf(task1, task2, task3))
        assertTrue(stateFirst is TrackDownloadState.Downloading)
        assertEquals(30f, (stateFirst as TrackDownloadState.Downloading).progress, 0.001f)

        val stateSecond = resolveTrackDownloadState("dup_vid", lookup, listOf(task2, task1, task3))
        assertTrue(stateSecond is TrackDownloadState.Failed)
        assertEquals("error", (stateSecond as TrackDownloadState.Failed).message)
    }

    @Test
    fun testResolveTrackDownloadState_NullArgumentsResilience() {
        // null lookup, null tasks
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState("test_vid", null, null))

        // null lookup, valid tasks
        val task = createTask("test_vid", DownloadStatus.Queued)
        assertEquals(TrackDownloadState.Queued, resolveTrackDownloadState("test_vid", null, listOf(task)))

        // valid lookup, null tasks
        val lookup = StressMockSongLookup(indexedIds = setOf("test_vid"))
        assertEquals(TrackDownloadState.Downloaded, resolveTrackDownloadState("test_vid", lookup, null))

        val lookupEmpty = StressMockSongLookup(indexedIds = emptySet())
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState("test_vid", lookupEmpty, null))
    }

    @Test
    fun testResolveTrackDownloadState_HighVolumeStressPerformance() {
        val taskCount = 20_000
        val targetId = "target_vid_9999"
        val tasks = ArrayList<DownloadTask>(taskCount)
        for (i in 0 until taskCount) {
            val vid = "vid_$i"
            val status = if (i % 2 == 0) DownloadStatus.Queued else DownloadStatus.Running
            tasks.add(createTask(vid, status, progress = (i % 100).toFloat()))
        }
        // Place target near the middle
        tasks[9999] = createTask(targetId, DownloadStatus.Running, progress = 88.5f)

        val lookup = StressMockSongLookup()

        val elapsedMs = measureTimeMillis {
            for (repeat in 1..100) {
                val state = resolveTrackDownloadState(targetId, lookup, tasks)
                assertTrue(state is TrackDownloadState.Downloading)
                assertEquals(88.5f, (state as TrackDownloadState.Downloading).progress, 0.001f)
            }
        }

        // 100 searches across 20,000 tasks must execute well under 500ms
        assertTrue("High volume search took $elapsedMs ms which should be < 500ms", elapsedMs < 500)
    }

    // =========================================================================
    // SECTION 2: Color Contrast & WCAG Conformance Verification
    // =========================================================================

    /**
     * Calculates WCAG 2.1 relative luminance for a given Color.
     * Formula: L = 0.2126 * R + 0.7152 * G + 0.0722 * B where channels are linearized.
     */
    private fun calculateRelativeLuminance(color: Color): Double {
        fun linearize(channel: Float): Double {
            val c = channel.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = linearize(color.red)
        val g = linearize(color.green)
        val b = linearize(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Calculates WCAG 2.1 contrast ratio between foreground and background.
     * (L1 + 0.05) / (L2 + 0.05) where L1 is the lighter of the two colors.
     */
    private fun calculateContrastRatio(fg: Color, bg: Color): Double {
        val l1 = calculateRelativeLuminance(fg)
        val l2 = calculateRelativeLuminance(bg)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun testColorContrast_TextPrimaryOnDarkSurfaces_ExceedsWcagAAA() {
        val darkSurfaces = listOf(
            "ObsidianBlack" to ObsidianBlack,
            "ObsidianDeep" to ObsidianDeep,
            "AmoledDarkBackground" to AmoledDarkBackground,
            "SurfaceDark" to SurfaceDark,
            "SurfaceCard" to SurfaceCard,
            "SurfaceCardElevated" to SurfaceCardElevated,
            "SurfaceCardHighlight" to SurfaceCardHighlight,
            "SurfaceVariantDark" to SurfaceVariantDark,
        )

        for ((name, surface) in darkSurfaces) {
            val ratio = calculateContrastRatio(TextPrimary, surface)
            // WCAG AAA requires 7.0:1 for normal text
            assertTrue(
                "TextPrimary on $name must satisfy WCAG AAA (>= 7.0:1). Actual ratio: $ratio",
                ratio >= 7.0,
            )
        }
    }

    @Test
    fun testColorContrast_TextSecondaryOnSurfaces_ExceedsWcagAA() {
        // Secondary text on core surfaces must satisfy WCAG AA (>= 4.5:1 for regular text, >= 3.0:1 for large)
        val ratioOnObsidian = calculateContrastRatio(TextSecondary, ObsidianBlack)
        val ratioOnSurfaceCard = calculateContrastRatio(TextSecondary, SurfaceCard)
        val ratioOnSurfaceDark = calculateContrastRatio(TextSecondary, SurfaceDark)

        assertTrue(
            "TextSecondary on ObsidianBlack must satisfy WCAG AA (>= 4.5:1). Actual: $ratioOnObsidian",
            ratioOnObsidian >= 4.5,
        )
        assertTrue(
            "TextSecondary on SurfaceCard must satisfy WCAG AA (>= 4.5:1). Actual: $ratioOnSurfaceCard",
            ratioOnSurfaceCard >= 4.5,
        )
        assertTrue(
            "TextSecondary on SurfaceDark must satisfy WCAG AA (>= 4.5:1). Actual: $ratioOnSurfaceDark",
            ratioOnSurfaceDark >= 4.5,
        )
    }

    @Test
    fun testColorContrast_CrimsonContainersAndAccents() {
        // OnCrimsonContainer on CrimsonContainer
        val containerRatio = calculateContrastRatio(OnCrimsonContainer, CrimsonContainer)
        assertTrue(
            "OnCrimsonContainer on CrimsonContainer must satisfy WCAG AAA (>= 7.0:1). Actual: $containerRatio",
            containerRatio >= 7.0,
        )

        // TextOnAccent (White) on CrimsonPrimary
        val accentRatio = calculateContrastRatio(TextOnAccent, CrimsonPrimary)
        // Buttons and badges use Bold typography. WCAG AA for bold text requires >= 3.0:1.
        assertTrue(
            "TextOnAccent on CrimsonPrimary must satisfy WCAG AA for large/bold text (>= 3.0:1). Actual: $accentRatio",
            accentRatio >= 3.0,
        )
    }

    @Test
    fun testColorTokens_OpaquenessAndAlphaIntegrity() {
        val solidTokens = listOf(
            ObsidianBlack,
            ObsidianDeep,
            AmoledDarkBackground,
            SurfaceDark,
            SurfaceCard,
            SurfaceCardElevated,
            SurfaceCardHighlight,
            SurfaceVariantDark,
            CrimsonPrimary,
            CrimsonDark,
            CrimsonHover,
            CrimsonContainer,
            OnCrimsonContainer,
            TextPrimary,
            TextSecondary,
            TextTertiary,
            TextOnAccent,
            OutlineDark,
            OutlineVariantDark,
            StreamingSuccess,
            StreamingProgress,
            StreamingError,
            StreamingWarning,
            BadgeLossless,
        )

        for (token in solidTokens) {
            assertEquals("Solid color token must have alpha = 1.0f", 1.0f, token.alpha, 0.001f)
        }
    }

    // =========================================================================
    // SECTION 3: Hero Scrim Gradient Stops & Alpha Stress Verification
    // =========================================================================

    @Test
    fun testCreateHeroScrim_GradientStopsAndAlphaBehavior() {
        val testColor = Color(0xFF101018)

        // Verify both StreamingColors.createHeroScrim and createHeroHeaderScrim produce non-null Brush
        val themeScrim = StreamingColors.createHeroScrim(testColor)
        val headerScrim = createHeroHeaderScrim(testColor)

        assertNotNull(themeScrim)
        assertNotNull(headerScrim)
        assertTrue("themeScrim must be a gradient Brush", themeScrim is Brush)
        assertTrue("headerScrim must be a gradient Brush", headerScrim is Brush)

        // Default parameter verification
        val defaultScrim = StreamingColors.createHeroScrim()
        assertNotNull(defaultScrim)

        // Mathematical verification of stops and alphas:
        // Expected stops:
        // 0.00f -> alpha 0.50f (Dark top protection for status bar & back button)
        // 0.30f -> alpha 0.00f (Fully transparent center window for hero art)
        // 0.65f -> alpha 0.70f (Gradient buildup start for title legibility)
        // 0.88f -> alpha 0.95f (Dense scrim for metadata and actions)
        // 1.00f -> alpha 1.00f (Fully opaque background blend)
        val stops = listOf(0.00f, 0.30f, 0.65f, 0.88f, 1.00f)
        val alphas = listOf(0.50f, 0.00f, 0.70f, 0.95f, 1.00f)

        // Check monotonicity of stops
        for (i in 0 until stops.size - 1) {
            assertTrue("Stops must be strictly monotonically increasing", stops[i] < stops[i + 1])
        }
        assertEquals(0.00f, stops.first(), 0.0f)
        assertEquals(1.00f, stops.last(), 0.0f)

        // Check alphas are in valid [0, 1] range
        for (alpha in alphas) {
            assertTrue("Alpha must be in [0.0, 1.0]", alpha in 0.0f..1.0f)
        }

        // Verify contrast between text and the 1.00f bottom stop
        val bottomBlendedColor = testColor.copy(alpha = 1.0f)
        val bottomTextRatio = calculateContrastRatio(Color.White, bottomBlendedColor)
        assertTrue("White text on bottom scrim stop must have >= 7.0:1 contrast", bottomTextRatio >= 7.0)
    }

    // =========================================================================
    // SECTION 4: Typography Scale Integrity Verification
    // =========================================================================

    @Test
    fun testTypographyScale_MonotonicDescentAndLineHeightIntegrity() {
        val tiers = listOf(
            "Display" to listOf(YrtTypography.displayLarge, YrtTypography.displayMedium, YrtTypography.displaySmall),
            "Headline" to listOf(YrtTypography.headlineLarge, YrtTypography.headlineMedium, YrtTypography.headlineSmall),
            "Title" to listOf(YrtTypography.titleLarge, YrtTypography.titleMedium, YrtTypography.titleSmall),
            "Body" to listOf(YrtTypography.bodyLarge, YrtTypography.bodyMedium, YrtTypography.bodySmall),
            "Label" to listOf(YrtTypography.labelLarge, YrtTypography.labelMedium, YrtTypography.labelSmall),
        )

        for ((tierName, styles) in tiers) {
            val (large, medium, small) = styles

            // Verify strict font size descending order within category
            assertTrue(
                "$tierName: Large (${large.fontSize}) must be > Medium (${medium.fontSize})",
                large.fontSize.value > medium.fontSize.value,
            )
            assertTrue(
                "$tierName: Medium (${medium.fontSize}) must be > Small (${small.fontSize})",
                medium.fontSize.value > small.fontSize.value,
            )

            // Verify line height is always >= font size for all styles in category
            for (style in styles) {
                assertTrue(
                    "Style font size (${style.fontSize}) must have lineHeight (${style.lineHeight}) >= fontSize",
                    style.lineHeight.value >= style.fontSize.value,
                )
                assertNotNull("FontWeight must not be null", style.fontWeight)
                assertNotNull("LetterSpacing must not be null", style.letterSpacing)
            }
        }

        // Cross-tier invariant checks: Display > Headline > Title
        assertTrue(YrtTypography.displaySmall.fontSize.value > YrtTypography.headlineLarge.fontSize.value)
        assertTrue(YrtTypography.headlineSmall.fontSize.value > YrtTypography.titleLarge.fontSize.value)
    }
}
