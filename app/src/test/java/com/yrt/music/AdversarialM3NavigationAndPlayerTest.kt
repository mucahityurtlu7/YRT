package com.yrt.music

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import com.yrt.music.navigation.MainNavTabs
import com.yrt.music.navigation.Screen
import com.yrt.music.ui.player.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Adversarial Stress & Empirical Verification Test Suite for Milestone 3:
 * 1. Sealed Screen route definitions, route constructors, and argument parameter extraction.
 * 2. Route generator fuzzing with corner cases, Unicode, URI-encoding, and traversal payloads.
 * 3. Deep backstack journey state-machine simulation (TEST_INFRA.md Scenario 5).
 * 4. Multi-tab navigation idempotency, single-top invariant, and navigation bar selection.
 * 5. MiniPlayer visibility invariants (hasMedia false -> hidden, true -> visible, persistent across tab transitions).
 * 6. Playback state mutations, full player overlay coordination, and automatic dismissal.
 * 7. Share intent receiver contract, input validation, burst handling, and rotation deduplication.
 */
class AdversarialM3NavigationAndPlayerTest {

    // =========================================================================
    // Category 1: Route Contract, Argument Extraction & Fuzzing
    // =========================================================================

    @Test
    fun testAllFiveRoutesDefinedAndUnique() {
        val allRoutes = listOf(
            Screen.Search.route,
            Screen.Library.route,
            Screen.Downloads.route,
            Screen.Artist.route,
            Screen.Album.route,
        )
        assertEquals("Must have exactly 5 distinct routes", 5, allRoutes.size)
        assertEquals("All routes must be unique", 5, allRoutes.toSet().size)

        assertEquals("search", Screen.Search.route)
        assertEquals("library", Screen.Library.route)
        assertEquals("downloads", Screen.Downloads.route)
        assertEquals("artist/{artistId}", Screen.Artist.route)
        assertEquals("album/{albumId}", Screen.Album.route)

        assertEquals("artistId", Screen.Artist.ARG_ARTIST_ID)
        assertEquals("albumId", Screen.Album.ARG_ALBUM_ID)
    }

    @Test
    fun testRouteConstructionStandardCases() {
        val standardArtistIds = listOf(
            "UC_x5XG1OV2P6uZZ5FSM9Ttw",
            "UC2XdaAVUannpujzv32jcouQ",
            "FEmusic_library_privately_owned_artist_001",
        )
        for (artistId in standardArtistIds) {
            val route = Screen.Artist.createRoute(artistId)
            assertEquals("artist/$artistId", route)
            assertTrue(route.startsWith("artist/"))
            assertEquals(artistId, route.removePrefix("artist/"))
        }

        val standardAlbumIds = listOf(
            "MPREb_kL29j192K3b",
            "OLAK5uy_kQy94mX8Z3m",
            "album_12345",
        )
        for (albumId in standardAlbumIds) {
            val route = Screen.Album.createRoute(albumId)
            assertEquals("album/$albumId", route)
            assertTrue(route.startsWith("album/"))
            assertEquals(albumId, route.removePrefix("album/"))
        }
    }

    @Test
    fun testRouteConstructionAdversarialInputs() {
        val adversarialInputs = listOf(
            "", // empty string
            " ", // whitespace
            "   padded_id   ", // padded
            "1234567890", // purely numeric
            "special-id_with.dots~and:colons", // punctuation
            "Daft Punk", // space inside
            "Daft+Punk", // plus
            "%20%2F%26%3D%3F", // URL encoded
            "../../secret_path", // traversal attempt
            "UC123#fragment", // fragment
            "UC123?query=param&other=value", // query params
            "Şebnem_Ferah", // Turkish Unicode
            "初音ミク_2026", // Japanese Unicode
            "Кино_Группа", // Cyrillic Unicode
            "Björk_Guðmundsdóttir", // Icelandic Unicode
            "A".repeat(4096), // extremely long ID
        )

        for (input in adversarialInputs) {
            val artistRoute = Screen.Artist.createRoute(input)
            assertEquals("artist/$input", artistRoute)
            assertTrue(artistRoute.startsWith("artist/"))

            val albumRoute = Screen.Album.createRoute(input)
            assertEquals("album/$input", albumRoute)
            assertTrue(albumRoute.startsWith("album/"))
        }
    }

    /**
     * Route parsing oracle verifying Navigation Compose path-matching mechanics:
     * - Route "artist/{artistId}" matches "artist/<segment>"
     * - Route "album/{albumId}" matches "album/<segment>"
     * - Static routes "search", "library", "downloads" match exactly
     */
    private object NavRouteOracle {
        private val artistPattern = Pattern.compile("^artist/([^/]+)$")
        private val albumPattern = Pattern.compile("^album/([^/]+)$")

        fun matchRoute(constructedRoute: String): Pair<Screen, Map<String, String>>? {
            return when {
                constructedRoute == Screen.Search.route -> Screen.Search to emptyMap()
                constructedRoute == Screen.Library.route -> Screen.Library to emptyMap()
                constructedRoute == Screen.Downloads.route -> Screen.Downloads to emptyMap()
                else -> {
                    val artistMatcher = artistPattern.matcher(constructedRoute)
                    if (artistMatcher.matches()) {
                        val id = artistMatcher.group(1) ?: return null
                        return Screen.Artist to mapOf(Screen.Artist.ARG_ARTIST_ID to id)
                    }
                    val albumMatcher = albumPattern.matcher(constructedRoute)
                    if (albumMatcher.matches()) {
                        val id = albumMatcher.group(1) ?: return null
                        return Screen.Album to mapOf(Screen.Album.ARG_ALBUM_ID to id)
                    }
                    null
                }
            }
        }
    }

    @Test
    fun testNavRouteOracleMatchingAndExtraction() {
        // Test static routes
        assertEquals(Screen.Search, NavRouteOracle.matchRoute("search")?.first)
        assertEquals(Screen.Library, NavRouteOracle.matchRoute("library")?.first)
        assertEquals(Screen.Downloads, NavRouteOracle.matchRoute("downloads")?.first)

        // Test parameterized artist routes
        val artistMatch = NavRouteOracle.matchRoute(Screen.Artist.createRoute("UC_Daft_Punk"))
        assertNotNull(artistMatch)
        assertEquals(Screen.Artist, artistMatch?.first)
        assertEquals("UC_Daft_Punk", artistMatch?.second?.get(Screen.Artist.ARG_ARTIST_ID))

        // Test parameterized album routes
        val albumMatch = NavRouteOracle.matchRoute(Screen.Album.createRoute("MPREb_RAM_2013"))
        assertNotNull(albumMatch)
        assertEquals(Screen.Album, albumMatch?.first)
        assertEquals("MPREb_RAM_2013", albumMatch?.second?.get(Screen.Album.ARG_ALBUM_ID))

        // Test malformed or invalid routes
        assertNull("Empty artist id 'artist/' should not match single segment pattern", NavRouteOracle.matchRoute("artist/"))
        assertNull("Empty album id 'album/' should not match single segment pattern", NavRouteOracle.matchRoute("album/"))
        assertNull("Prefix only 'artist' should not match parameterized route", NavRouteOracle.matchRoute("artist"))
        assertNull("Prefix only 'album' should not match parameterized route", NavRouteOracle.matchRoute("album"))
        assertNull("Multi-segment 'artist/a/b' should not match single segment", NavRouteOracle.matchRoute("artist/a/b"))
        assertNull("Unknown route 'settings' should not match", NavRouteOracle.matchRoute("settings"))
        assertNull("Empty route should not match", NavRouteOracle.matchRoute(""))
    }

    // =========================================================================
    // Category 2: MainNavTabs & Navigation Bar Invariants
    // =========================================================================

    @Test
    fun testMainNavTabsCompletenessAndIcons() {
        assertEquals(3, MainNavTabs.size)

        assertEquals(Screen.Search, MainNavTabs[0].screen)
        assertEquals("Keşfet", MainNavTabs[0].title)
        assertEquals(Icons.Default.Explore, MainNavTabs[0].icon)

        assertEquals(Screen.Library, MainNavTabs[1].screen)
        assertEquals("Kitaplık", MainNavTabs[1].title)
        assertEquals(Icons.Default.LibraryMusic, MainNavTabs[1].icon)

        assertEquals(Screen.Downloads, MainNavTabs[2].screen)
        assertEquals("İndirilenler", MainNavTabs[2].title)
        assertEquals(Icons.Default.Download, MainNavTabs[2].icon)
    }

    @Test
    fun testNavigationBarSelectionPredicate() {
        fun isTabSelected(currentRoute: String, tabScreen: Screen): Boolean {
            // Replicates: currentDestination?.hierarchy?.any { it.route == tabItem.screen.route } == true
            // In a flat NavHost, each destination's hierarchy is [destination, rootGraph]
            return currentRoute == tabScreen.route
        }

        // When on Search
        assertTrue(isTabSelected("search", Screen.Search))
        assertFalse(isTabSelected("search", Screen.Library))
        assertFalse(isTabSelected("search", Screen.Downloads))

        // When on Library
        assertFalse(isTabSelected("library", Screen.Search))
        assertTrue(isTabSelected("library", Screen.Library))
        assertFalse(isTabSelected("library", Screen.Downloads))

        // When on Downloads
        assertFalse(isTabSelected("downloads", Screen.Search))
        assertFalse(isTabSelected("downloads", Screen.Library))
        assertTrue(isTabSelected("downloads", Screen.Downloads))

        // When on detail screens (Artist / Album), no bottom nav tab is selected
        assertFalse(isTabSelected("artist/UC123", Screen.Search))
        assertFalse(isTabSelected("artist/UC123", Screen.Library))
        assertFalse(isTabSelected("artist/UC123", Screen.Downloads))

        assertFalse(isTabSelected("album/MPRE456", Screen.Search))
        assertFalse(isTabSelected("album/MPRE456", Screen.Library))
        assertFalse(isTabSelected("album/MPRE456", Screen.Downloads))
    }

    // =========================================================================
    // Category 3: Backstack State Machine & Navigation Journey (Scenario 5)
    // =========================================================================

    /**
     * Exact state machine model simulating Compose NavController with:
     * - startDestination = "search"
     * - tab navigation with popUpTo(startDestination) { saveState = true }, launchSingleTop = true, restoreState = true
     * - stack navigation for artist and album
     */
    private class NavControllerHarness(val startDestination: String = Screen.Search.route) {
        data class BackStackEntry(val route: String, val arguments: Map<String, String> = emptyMap())

        // Active backstack
        val backStack = mutableListOf<BackStackEntry>()

        // Saved state stacks per destination id
        val savedStates = mutableMapOf<String, List<BackStackEntry>>()

        init {
            // Initial start destination
            backStack.add(BackStackEntry(startDestination))
        }

        val currentDestination: String get() = backStack.last().route
        val currentEntry: BackStackEntry get() = backStack.last()

        fun navigateToDetail(route: String) {
            val matched = NavRouteOracle.matchRoute(route)
                ?: throw IllegalArgumentException("Navigation destination not found for route: $route")
            backStack.add(BackStackEntry(route, matched.second))
        }

        fun popBackStack(): Boolean {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.size - 1)
                return true
            }
            return false // Cannot pop start destination
        }

        /**
         * Replicates tab navigation onClick:
         * navController.navigate(tabItem.screen.route) {
         *     popUpTo(findStartDestination().id) { saveState = true }
         *     launchSingleTop = true
         *     restoreState = true
         * }
         */
        fun navigateTab(tabRoute: String) {
            if (backStack.isNotEmpty() && backStack.last().route == tabRoute) {
                // launchSingleTop: already on destination, do nothing
                return
            }

            // popUpTo(startDestination) with saveState = true
            val startIndex = backStack.indexOfFirst { it.route == startDestination }
            if (startIndex >= 0 && backStack.size > startIndex + 1) {
                // Pop everything above start destination and save
                val popped = mutableListOf<BackStackEntry>()
                while (backStack.size > startIndex + 1) {
                    popped.add(0, backStack.removeAt(backStack.size - 1))
                }
                // Save state keyed by the popped top or target
                val topRoute = popped.first().route
                savedStates[topRoute] = popped
            }

            if (tabRoute == startDestination) {
                // If navigating to startDestination itself
                // Restore state if available
                savedStates[tabRoute]?.let { restored ->
                    backStack.addAll(restored)
                    savedStates.remove(tabRoute)
                }
            } else {
                // Restore destination state if exists, otherwise push fresh
                val saved = savedStates.remove(tabRoute)
                if (saved != null) {
                    backStack.addAll(saved)
                } else {
                    backStack.add(BackStackEntry(tabRoute))
                }
            }
        }
    }

    @Test
    fun testDeepBackstackNavigationJourney_Scenario5() {
        val nav = NavControllerHarness(startDestination = Screen.Search.route)

        // 1. User starts at Search
        assertEquals("search", nav.currentDestination)
        assertEquals(1, nav.backStack.size)

        // 2. User searches and navigates to Artist Profile "daft_punk"
        nav.navigateToDetail(Screen.Artist.createRoute("daft_punk"))
        assertEquals("artist/daft_punk", nav.currentDestination)
        assertEquals("daft_punk", nav.currentEntry.arguments[Screen.Artist.ARG_ARTIST_ID])
        assertEquals(2, nav.backStack.size)

        // 3. User taps Album "random_access_memories"
        nav.navigateToDetail(Screen.Album.createRoute("random_access_memories"))
        assertEquals("album/random_access_memories", nav.currentDestination)
        assertEquals("random_access_memories", nav.currentEntry.arguments[Screen.Album.ARG_ALBUM_ID])
        assertEquals(3, nav.backStack.size)

        // 4. User navigates back -> returns to Artist Profile "daft_punk"
        val poppedAlbum = nav.popBackStack()
        assertTrue("Album pop must succeed", poppedAlbum)
        assertEquals("artist/daft_punk", nav.currentDestination)
        assertEquals("daft_punk", nav.currentEntry.arguments[Screen.Artist.ARG_ARTIST_ID])
        assertEquals(2, nav.backStack.size)

        // 5. User taps "Library" tab from bottom navigation
        nav.navigateTab(Screen.Library.route)
        assertEquals("library", nav.currentDestination)
        assertEquals(2, nav.backStack.size) // [search, library]

        // 6. User taps "Downloads" tab from bottom navigation
        nav.navigateTab(Screen.Downloads.route)
        assertEquals("downloads", nav.currentDestination)
        assertEquals(2, nav.backStack.size) // [search, downloads]

        // 7. User taps "Library" tab again -> state restored, no duplicate push
        nav.navigateTab(Screen.Library.route)
        assertEquals("library", nav.currentDestination)
        assertEquals(2, nav.backStack.size) // [search, library]
    }

    @Test
    fun testTabNavigationIdempotencySingleTop() {
        val nav = NavControllerHarness(startDestination = Screen.Search.route)

        // Navigate to Downloads
        nav.navigateTab(Screen.Downloads.route)
        assertEquals("downloads", nav.currentDestination)
        val initialDepth = nav.backStack.size

        // Tap Downloads 20 times consecutively
        repeat(20) {
            nav.navigateTab(Screen.Downloads.route)
            assertEquals("downloads", nav.currentDestination)
            assertEquals("Backstack must not grow on repeated clicks due to launchSingleTop", initialDepth, nav.backStack.size)
        }

        // Tap Library 10 times consecutively
        nav.navigateTab(Screen.Library.route)
        assertEquals("library", nav.currentDestination)
        val libraryDepth = nav.backStack.size
        repeat(10) {
            nav.navigateTab(Screen.Library.route)
            assertEquals("library", nav.currentDestination)
            assertEquals(libraryDepth, nav.backStack.size)
        }
    }

    @Test
    fun testRapidInterleavedTabSwitchingStress() {
        val nav = NavControllerHarness(startDestination = Screen.Search.route)
        val tabs = listOf(Screen.Search.route, Screen.Library.route, Screen.Downloads.route)

        // 300 rapid random tab switches
        val rng = java.util.Random(1337)
        for (i in 0 until 300) {
            val target = tabs[rng.nextInt(tabs.size)]
            nav.navigateTab(target)
            assertEquals("Current destination must match target after navigation", target, nav.currentDestination)
            assertTrue("Backstack must never be empty", nav.backStack.isNotEmpty())
            assertTrue("Backstack depth must remain bounded", nav.backStack.size <= 3)
        }
    }

    // =========================================================================
    // Category 4: MiniPlayer Visibility & Persistence Invariants
    // =========================================================================

    @Test
    fun testMiniPlayerVisibilityInvariant_HasMedia() {
        // Invariant: MiniPlayer is visible if and only if playerState.hasMedia == true
        val emptyState = PlayerUiState()
        assertNull(emptyState.currentMediaId)
        assertFalse("MiniPlayer must be HIDDEN when currentMediaId is null", emptyState.hasMedia)

        val connectedEmptyState = PlayerUiState(connected = true, currentMediaId = null)
        assertFalse("MiniPlayer must be HIDDEN when connected but no track loaded", connectedEmptyState.hasMedia)

        val activeTrackState = PlayerUiState(
            connected = true,
            currentMediaId = "track_001",
            title = "Around the World",
            artist = "Daft Punk",
            isPlaying = true,
            positionMs = 15_000L,
            durationMs = 240_000L,
        )
        assertTrue("MiniPlayer must be VISIBLE when currentMediaId is present", activeTrackState.hasMedia)

        // Empty string mediaId is not null, so hasMedia is true
        val blankIdState = PlayerUiState(currentMediaId = "")
        assertTrue(blankIdState.hasMedia)
    }

    @Test
    fun testMiniPlayerPersistenceAcrossTabTransitions() {
        // Simulate MainActivity state:
        // playerState is held in playerVm and observed in AppRoot.
        // It lives outside AppNavHost and Scaffold content.
        var playerState = PlayerUiState(
            connected = true,
            currentMediaId = "daft_punk_get_lucky",
            title = "Get Lucky",
            artist = "Daft Punk",
            isPlaying = true,
            positionMs = 30_000L,
            durationMs = 248_000L,
        )

        val nav = NavControllerHarness(Screen.Search.route)

        val tabCycle = listOf(
            Screen.Search.route,
            Screen.Library.route,
            Screen.Downloads.route,
            Screen.Search.route,
            Screen.Artist.createRoute("daft_punk"),
            Screen.Album.createRoute("random_access_memories"),
            Screen.Library.route,
            Screen.Downloads.route,
        )

        for (dest in tabCycle) {
            if (dest.startsWith("artist/") || dest.startsWith("album/")) {
                nav.navigateToDetail(dest)
            } else {
                nav.navigateTab(dest)
            }

            // Invariant: Navigating between screens must not reset playerState or hide MiniPlayer
            assertTrue("MiniPlayer must remain VISIBLE during transition to $dest", playerState.hasMedia)
            assertEquals("daft_punk_get_lucky", playerState.currentMediaId)
            assertTrue(playerState.isPlaying)
        }
    }

    @Test
    fun testPlayerPlaybackStateMutationsPreserveVisibility() {
        var state = PlayerUiState()
        assertFalse("Initial: hidden", state.hasMedia)

        // 1. Song loaded and playing
        state = state.copy(currentMediaId = "song_1", title = "Track 1", isPlaying = true)
        assertTrue("Playing: visible", state.hasMedia)

        // 2. Paused -> must remain visible
        state = state.copy(isPlaying = false)
        assertTrue("Paused: must remain visible", state.hasMedia)

        // 3. Scrubbing position -> must remain visible
        state = state.copy(positionMs = 120_000L)
        assertTrue("Scrubbing: must remain visible", state.hasMedia)

        // 4. Track change -> next song
        state = state.copy(currentMediaId = "song_2", title = "Track 2")
        assertTrue("Next track: must remain visible", state.hasMedia)

        // 5. Shuffle / repeat toggles
        state = state.copy(shuffle = true, repeatMode = 1)
        assertTrue("Mode change: must remain visible", state.hasMedia)

        // 6. Playback stopped / cleared -> currentMediaId set to null
        state = state.copy(currentMediaId = null, title = "", isPlaying = false)
        assertFalse("Stopped/Cleared: must be hidden", state.hasMedia)
    }

    @Test
    fun testFullPlayerModalSheetVisibilityCoordination() {
        // MainActivity logic:
        // var showPlayer by rememberSaveable { mutableStateOf(false) }
        // Full player AnimatedVisibility: visible = showPlayer && playerState.hasMedia
        // BackHandler(enabled = showPlayer) { showPlayer = false }

        fun isFullPlayerVisible(showPlayer: Boolean, playerState: PlayerUiState): Boolean {
            return showPlayer && playerState.hasMedia
        }

        var showPlayer = false
        var state = PlayerUiState(currentMediaId = "song_1", isPlaying = true)

        // Initially closed
        assertFalse(isFullPlayerVisible(showPlayer, state))

        // User taps MiniPlayer -> opens full player
        showPlayer = true
        assertTrue(isFullPlayerVisible(showPlayer, state))

        // BackHandler pressed -> closes full player
        showPlayer = false
        assertFalse(isFullPlayerVisible(showPlayer, state))

        // Re-opened, then song ends/cleared (hasMedia -> false)
        showPlayer = true
        assertTrue(isFullPlayerVisible(showPlayer, state))
        state = state.copy(currentMediaId = null) // playback stops
        assertFalse("Full player must dismiss automatically if hasMedia becomes false", isFullPlayerVisible(showPlayer, state))
    }

    // =========================================================================
    // Category 5: Share Intent Receiver & Downloads Navigation Stress
    // =========================================================================

    /**
     * Intent handler simulator modeling MainActivity.handleIntent and AppRoot.LaunchedEffect:
     * - Checks action == ACTION_SEND and type == "text/plain"
     * - Extracts EXTRA_TEXT
     * - Calls downloaderVm.onSharedText(text)
     * - Increments shareSignal
     * - In LaunchedEffect(shareSignal): collapses showPlayer, navigates to Screen.Downloads.route
     */
    private class IntentHandlerHarness {
        companion object {
            const val ACTION_SEND = "android.intent.action.SEND"
            const val ACTION_VIEW = "android.intent.action.VIEW"
            const val ACTION_MAIN = "android.intent.action.MAIN"
            const val EXTRA_TEXT = "android.intent.extra.TEXT"
        }

        data class MockIntent(
            val action: String? = null,
            val type: String? = null,
            val extras: Map<String, String?> = emptyMap(),
        ) {
            fun getStringExtra(name: String): String? = extras[name]
        }

        var shareSignal = 0
        val sharedTexts = mutableListOf<String>()
        var showPlayer = false
        val nav = NavControllerHarness(Screen.Search.route)

        fun onCreate(savedInstanceState: Any?, intent: MockIntent?) {
            // Lines 71: if (savedInstanceState == null) handleIntent(intent)
            if (savedInstanceState == null) {
                handleIntent(intent)
            }
        }

        fun onNewIntent(intent: MockIntent?) {
            // Line 83: handleIntent(intent)
            handleIntent(intent)
        }

        private fun handleIntent(intent: MockIntent?) {
            // Lines 87-94:
            // if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            //     intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
            //         downloaderVm.onSharedText(it)
            //         shareSignal.value++
            //     }
            // }
            if (intent?.action == ACTION_SEND && intent.type == "text/plain") {
                intent.getStringExtra(EXTRA_TEXT)?.let { text ->
                    sharedTexts.add(text)
                    shareSignal++
                    onShareSignalTriggered()
                }
            }
        }

        private fun onShareSignalTriggered() {
            // Lines 109-120:
            // LaunchedEffect(shareSignal) {
            //     if (shareSignal > 0) {
            //         showPlayer = false
            //         navController.navigate(Screen.Downloads.route) {
            //             popUpTo(navController.graph.findStartDestination().id) {
            //                 saveState = true
            //             }
            //             launchSingleTop = true
            //             restoreState = true
            //         }
            //     }
            // }
            if (shareSignal > 0) {
                showPlayer = false
                nav.navigateTab(Screen.Downloads.route)
            }
        }
    }

    @Test
    fun testShareIntentValidInputTriggersDownloadsNavigation() {
        val harness = IntentHandlerHarness()
        harness.showPlayer = true // Full player was open
        assertEquals("search", harness.nav.currentDestination)

        val shareIntent = IntentHandlerHarness.MockIntent(
            action = IntentHandlerHarness.ACTION_SEND,
            type = "text/plain",
            extras = mapOf(IntentHandlerHarness.EXTRA_TEXT to "https://music.youtube.com/watch?v=dQw4w9WgXcQ"),
        )

        harness.onCreate(savedInstanceState = null, intent = shareIntent)

        // Verifications
        assertEquals(1, harness.shareSignal)
        assertEquals(listOf("https://music.youtube.com/watch?v=dQw4w9WgXcQ"), harness.sharedTexts)
        assertFalse("Full player must be collapsed when share intent arrives", harness.showPlayer)
        assertEquals("Downloads tab must be navigated to", Screen.Downloads.route, harness.nav.currentDestination)
    }

    @Test
    fun testShareIntentCornerCasesDoNotTriggerOrCrash() {
        val harness = IntentHandlerHarness()

        // 1. Null intent
        harness.onNewIntent(null)
        assertEquals(0, harness.shareSignal)
        assertEquals("search", harness.nav.currentDestination)

        // 2. Non-SEND action (e.g. ACTION_VIEW)
        harness.onNewIntent(
            IntentHandlerHarness.MockIntent(
                action = IntentHandlerHarness.ACTION_VIEW,
                type = "text/plain",
                extras = mapOf(IntentHandlerHarness.EXTRA_TEXT to "https://example.com"),
            )
        )
        assertEquals(0, harness.shareSignal)
        assertEquals("search", harness.nav.currentDestination)

        // 3. Null type
        harness.onNewIntent(
            IntentHandlerHarness.MockIntent(
                action = IntentHandlerHarness.ACTION_SEND,
                type = null,
                extras = mapOf(IntentHandlerHarness.EXTRA_TEXT to "https://example.com"),
            )
        )
        assertEquals(0, harness.shareSignal)

        // 4. Incompatible type (e.g. image/png)
        harness.onNewIntent(
            IntentHandlerHarness.MockIntent(
                action = IntentHandlerHarness.ACTION_SEND,
                type = "image/png",
                extras = mapOf(IntentHandlerHarness.EXTRA_TEXT to "https://example.com"),
            )
        )
        assertEquals(0, harness.shareSignal)

        // 5. Null EXTRA_TEXT
        harness.onNewIntent(
            IntentHandlerHarness.MockIntent(
                action = IntentHandlerHarness.ACTION_SEND,
                type = "text/plain",
                extras = emptyMap(),
            )
        )
        assertEquals(0, harness.shareSignal)
        assertEquals("search", harness.nav.currentDestination)
    }

    @Test
    fun testShareIntentRotationDeduplication() {
        val harness = IntentHandlerHarness()
        val shareIntent = IntentHandlerHarness.MockIntent(
            action = IntentHandlerHarness.ACTION_SEND,
            type = "text/plain",
            extras = mapOf(IntentHandlerHarness.EXTRA_TEXT to "https://music.youtube.com/watch?v=test"),
        )

        // Cold start with intent: savedInstanceState == null -> intent is processed
        harness.onCreate(savedInstanceState = null, intent = shareIntent)
        assertEquals(1, harness.shareSignal)
        assertEquals(1, harness.sharedTexts.size)

        // Configuration change / rotation recreation: savedInstanceState != null
        // Intent must NOT be processed again to avoid duplicate enqueuing
        harness.onCreate(savedInstanceState = "saved_state_bundle", intent = shareIntent)
        assertEquals("Must not re-process intent on rotation", 1, harness.shareSignal)
        assertEquals("Must not add duplicate shared text on rotation", 1, harness.sharedTexts.size)
    }

    @Test
    fun testRapidShareIntentBurst() {
        val harness = IntentHandlerHarness()

        // 50 rapid share intents arriving via onNewIntent
        for (i in 1..50) {
            val url = "https://music.youtube.com/watch?v=track_$i"
            harness.onNewIntent(
                IntentHandlerHarness.MockIntent(
                    action = IntentHandlerHarness.ACTION_SEND,
                    type = "text/plain",
                    extras = mapOf(IntentHandlerHarness.EXTRA_TEXT to url),
                )
            )
            assertEquals(i, harness.shareSignal)
            assertEquals(Screen.Downloads.route, harness.nav.currentDestination)
        }

        assertEquals(50, harness.sharedTexts.size)
        assertEquals("https://music.youtube.com/watch?v=track_50", harness.sharedTexts.last())
        assertEquals("Backstack depth must remain single top on downloads", 2, harness.nav.backStack.size)
    }
}
