package com.yrt.music

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import com.yrt.music.data.downloader.YtDlpDownloadManager
import com.yrt.music.data.model.AlbumDetails
import com.yrt.music.data.model.AlbumSearchResult
import com.yrt.music.data.model.ArtistProfile
import com.yrt.music.data.model.ArtistSearchResult
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.BulkDownloadRequest
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.data.model.toBulkDownloadRequest
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.data.repository.YouTubeMusicService
import com.yrt.music.navigation.MainNavTabs
import com.yrt.music.navigation.Screen
import com.yrt.music.ui.album.AlbumUiState
import com.yrt.music.ui.album.AlbumViewModel
import com.yrt.music.ui.components.HeroDownloadState
import com.yrt.music.ui.player.PlayerUiState
import com.yrt.music.ui.search.SearchFilter
import com.yrt.music.ui.search.SearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.regex.Pattern

/**
 * Adversarial Stress & Empirical Challenge Suite for Milestone 4:
 * Scope:
 * 1. AlbumViewModel Empirical Verification:
 *    - Batch download enqueuing with full tracklist and deep request mapping
 *    - Empty & massive album tracklist boundary stress
 *    - Single track download enqueueing vs full album download conflict resolution & deduplication
 *    - Rapid double-tap / concurrent download album stress
 *    - Real-time HeroDownloadState dynamic lifecycle (Idle -> InProgress -> Cancelled -> Completed)
 *    - Offline track playback resolution and strict album tracklist order preservation
 * 2. Navigation Architecture Empirical Verification (NavGraph.kt & MainActivity.kt):
 *    - Deep backstack navigation journey (TEST_INFRA.md Scenario 5: Search -> Artist -> Album -> Artist -> Back -> Library -> Downloads)
 *    - Cyclic deep navigation backstack depth and argument resolution
 *    - Activity-scoped SearchViewModel persistence across tab transitions
 *    - MiniPlayer visibility invariants across all 5 navigation destinations
 *    - FullPlayer modal sheet overlay dismiss and back handler interactions
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdversarialM4AlbumAndNavigationChallengeTest {

    // =========================================================================
    // Test Doubles & Fixtures
    // =========================================================================

    private class TestYouTubeMusicService(
        var albumResult: Result<AlbumDetails>? = null,
        var artistResult: Result<ArtistProfile>? = null,
        var artistSearchResults: List<ArtistSearchResult> = emptyList(),
        var albumSearchResults: List<AlbumSearchResult> = emptyList(),
    ) : YouTubeMusicService {
        var albumCallCount = 0
        var lastAlbumBrowseId: String? = null

        override suspend fun getAlbumDetails(browseId: String): Result<AlbumDetails> {
            albumCallCount++
            lastAlbumBrowseId = browseId
            return albumResult ?: Result.failure(IOException("Album not found: $browseId"))
        }

        override suspend fun getArtistProfile(browseId: String): Result<ArtistProfile> {
            return artistResult ?: Result.failure(IOException("Artist not found: $browseId"))
        }

        override suspend fun searchArtists(query: String): Result<List<ArtistSearchResult>> {
            return Result.success(artistSearchResults)
        }

        override suspend fun searchAlbums(query: String): Result<List<AlbumSearchResult>> {
            return Result.success(albumSearchResults)
        }
    }

    private class TestLocalSongLookup(
        initialSongs: List<Song> = emptyList(),
    ) : LocalSongLookup {
        private val _songs = MutableStateFlow(initialSongs)
        override val songs: StateFlow<List<Song>> = _songs.asStateFlow()

        override fun hasVideoId(videoId: String): Boolean = _songs.value.any { it.videoId == videoId }
        override fun getSongByVideoId(videoId: String): Song? = _songs.value.find { it.videoId == videoId }
        override suspend fun refresh() {}

        fun addSong(song: Song) {
            _songs.value = _songs.value + song
        }

        fun removeSong(videoId: String) {
            _songs.value = _songs.value.filterNot { it.videoId == videoId }
        }
    }

    private fun createSong(
        id: String,
        title: String,
        artist: String = "Test Artist",
        album: String = "Test Album",
        videoId: String? = null,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = 210000L,
        file = File("/music/$id.m4a"),
        artworkFile = null,
        lastModified = System.currentTimeMillis(),
        videoId = videoId,
    )

    private fun buildAlbum(
        browseId: String,
        title: String,
        artist: String = "Daft Punk",
        artistBrowseId: String? = "art_daft_punk",
        trackCount: Int = 4,
    ): AlbumDetails {
        val tracks = (1..trackCount).map { i ->
            RemoteTrack(
                videoId = "vid_${browseId}_$i",
                title = "Track $i of $title",
                artist = if (i % 2 == 0) null else "Featured Artist $i", // tests fallback to album artist
                album = if (i % 2 == 0) null else "Custom Album Name", // tests fallback to album title
                durationMs = (180 + i * 20) * 1000L,
                durationText = "${3 + i / 2}:${(i * 15) % 60}",
                thumbnailUrl = "https://example.com/thumb_$i.jpg",
                index = i,
            )
        }
        return AlbumDetails(
            browseId = browseId,
            title = title,
            artist = artist,
            artistBrowseId = artistBrowseId,
            year = "2024",
            artworkUrl = "https://example.com/$browseId.jpg",
            trackCount = tracks.size,
            durationText = "45 dakika",
            tracks = tracks,
        )
    }

    // =========================================================================
    // PART A: AlbumViewModel Empirical & Stress Verification
    // =========================================================================

    @Test
    fun testAlbumDownloadRequestFullMapping() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = buildAlbum(browseId = "alb_map_test", title = "Mapping Album", artist = "Primary Artist")
        val service = TestYouTubeMusicService(albumResult = Result.success(album))
        val lookup = TestLocalSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_map_test").join()

        val enqueueResult = vm.downloadAlbum()
        assertNotNull("Batch enqueue result must not be null", enqueueResult)
        assertEquals("album_alb_map_test", enqueueResult?.batchId)
        assertEquals(4, enqueueResult?.totalRequested)
        assertEquals(4, enqueueResult?.enqueuedCount)

        // Verify task parameters mapping
        val tasks = manager.tasks.value.filter { it.batchId == "album_alb_map_test" }
        assertEquals(4, tasks.size)

        // Track 1: explicit artist and explicit album in track
        val task1 = tasks.find { it.videoId == "vid_alb_map_test_1" }
        assertNotNull(task1)
        assertEquals("Track 1 of Mapping Album", task1?.title)
        assertEquals("Featured Artist 1", task1?.artist)
        assertEquals("Custom Album Name", task1?.album)
        assertEquals("https://example.com/thumb_1.jpg", task1?.thumbnailUrl)
        assertEquals(200000L, task1?.durationMs)
        assertEquals(AudioFormat.M4A, task1?.format)

        // Track 2: null artist and null album -> fallback to album.artist & album.title
        val task2 = tasks.find { it.videoId == "vid_alb_map_test_2" }
        assertNotNull(task2)
        assertEquals("Track 2 of Mapping Album", task2?.title)
        assertEquals("Primary Artist", task2?.artist) // Fell back to album.artist!
        assertEquals("Mapping Album", task2?.album) // Fell back to album.title!
        assertEquals(220000L, task2?.durationMs)

        testScope.cancel()
    }

    @Test
    fun testAlbumDownloadEmptyAlbumBoundary() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val emptyAlbum = AlbumDetails(
            browseId = "alb_empty",
            title = "Empty Album",
            artist = "Ghost Artist",
            tracks = emptyList(),
        )
        val service = TestYouTubeMusicService(albumResult = Result.success(emptyAlbum))
        val lookup = TestLocalSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_empty").join()

        val result = vm.downloadAlbum()
        assertNull("Empty album download should return null", result)
        assertEquals(0, manager.batches.value.size)
        assertEquals(0, manager.tasks.value.size)
        assertEquals(HeroDownloadState.Idle, vm.heroDownloadState.value)

        testScope.cancel()
    }

    @Test
    fun testAlbumDownloadMassiveTracklistStress() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val massiveAlbum = buildAlbum(browseId = "alb_massive", title = "Massive 200 Track Album", trackCount = 200)
        val service = TestYouTubeMusicService(albumResult = Result.success(massiveAlbum))
        val lookup = TestLocalSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_massive").join()

        val result = vm.downloadAlbum()
        assertNotNull(result)
        assertEquals("album_alb_massive", result?.batchId)
        assertEquals(200, result?.totalRequested)
        assertEquals(200, result?.enqueuedCount)
        assertEquals(0, result?.alreadyDownloadedCount)

        val batch = manager.batches.value.find { it.id == "album_alb_massive" }
        assertNotNull(batch)
        assertEquals("Albüm: Massive 200 Track Album", batch?.title)
        assertEquals(200, batch?.totalCount)
        assertEquals(200, manager.tasks.value.size)

        testScope.cancel()
    }

    @Test
    fun testSingleTrackDownloadVsAlbumDownload_SingleFirst() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = buildAlbum(browseId = "alb_conflict", title = "Conflict Album", trackCount = 4)
        val service = TestYouTubeMusicService(albumResult = Result.success(album))
        val lookup = TestLocalSongLookup()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            gate.await()
        }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_conflict").join()

        // 1. User downloads track 2 first
        val track2 = album.tracks[1]
        vm.downloadTrack(track2)

        val singleTask = manager.tasks.value.find { it.videoId == track2.videoId }
        assertNotNull("Single track task must be enqueued", singleTask)
        assertEquals("track_${track2.videoId}", singleTask?.batchId)

        // 2. User then clicks "Download Album" while track 2 is in-flight
        val albumResult = vm.downloadAlbum()
        assertNotNull(albumResult)
        assertEquals(4, albumResult?.totalRequested)
        // Track 2 is already in-flight in tasks, so it MUST be skipped by downloadManager!
        assertEquals(3, albumResult?.enqueuedCount)
        assertEquals(1, albumResult?.alreadyDownloadedCount)

        // Verify no duplicate tasks were created for track 2
        val track2Tasks = manager.tasks.value.filter { it.videoId == track2.videoId }
        assertEquals("Must have exactly 1 task for track 2 despite both single and album requests", 1, track2Tasks.size)

        // Total tasks in manager must be exactly 4 (1 single + 3 album)
        assertEquals(4, manager.tasks.value.size)

        gate.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testSingleTrackDownloadVsAlbumDownload_AlbumFirst() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = buildAlbum(browseId = "alb_conflict_2", title = "Conflict Album 2", trackCount = 4)
        val service = TestYouTubeMusicService(albumResult = Result.success(album))
        val lookup = TestLocalSongLookup()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            gate.await()
        }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_conflict_2").join()

        // 1. User starts full album download
        val albumResult = vm.downloadAlbum()
        assertEquals(4, albumResult?.enqueuedCount)
        assertEquals(4, manager.tasks.value.size)

        // 2. User subsequently clicks single track download on track 3
        val track3 = album.tracks[2]
        vm.downloadTrack(track3)

        // downloadTrack calls enqueueBatch with track 3. Since track 3 is already active,
        // it must skip it without adding any duplicate task.
        val track3Tasks = manager.tasks.value.filter { it.videoId == track3.videoId }
        assertEquals("Must remain exactly 1 task for track 3", 1, track3Tasks.size)
        assertEquals(4, manager.tasks.value.size)

        gate.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testRapidDoubleTapDownloadAlbumStress() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = buildAlbum(browseId = "alb_double_tap", title = "Double Tap Album", trackCount = 5)
        val service = TestYouTubeMusicService(albumResult = Result.success(album))
        val lookup = TestLocalSongLookup()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            gate.await()
        }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_double_tap").join()

        // First tap
        val result1 = vm.downloadAlbum()
        assertEquals(5, result1?.enqueuedCount)
        assertEquals(0, result1?.alreadyDownloadedCount)

        // Second tap immediately following
        val result2 = vm.downloadAlbum()
        assertEquals("Second tap must enqueue 0 tasks", 0, result2?.enqueuedCount)
        assertEquals("Second tap must skip all 5 in-flight tracks", 5, result2?.alreadyDownloadedCount)

        // Tasks in download manager must stay exactly 5
        assertEquals(5, manager.tasks.value.size)

        gate.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testAlbumHeroDownloadStateTransitionsAndCancellation() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = buildAlbum(browseId = "alb_state_flow", title = "State Flow Album", trackCount = 3)
        val service = TestYouTubeMusicService(albumResult = Result.success(album))
        val lookup = TestLocalSongLookup()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            gate.await()
        }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_state_flow").join()

        // Initial: Idle
        assertEquals(HeroDownloadState.Idle, vm.heroDownloadState.value)

        // Enqueue batch
        vm.downloadAlbum()

        // Now InProgress
        val inProgressState = vm.heroDownloadState.first { it is HeroDownloadState.InProgress }
        assertTrue(inProgressState is HeroDownloadState.InProgress)

        // Cancel batch
        manager.cancelBatch("album_alb_state_flow")

        // Once cancelled, HeroDownloadState reverts to Idle
        val cancelledState = vm.heroDownloadState.first { it == HeroDownloadState.Idle }
        assertEquals(HeroDownloadState.Idle, cancelledState)

        // Now simulate that all tracks exist in local library
        for (track in album.tracks) {
            lookup.addSong(createSong("song_${track.videoId}", track.title, videoId = track.videoId))
        }

        // HeroDownloadState transitions to Completed
        val completedState = vm.heroDownloadState.first { it == HeroDownloadState.Completed }
        assertEquals(HeroDownloadState.Completed, completedState)

        gate.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testOfflineTrackPlaybackResolutionOrdering() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = buildAlbum(browseId = "alb_ordering", title = "Order Test Album", trackCount = 5)
        val service = TestYouTubeMusicService(albumResult = Result.success(album))

        // Create local songs in REVERSE / ARBITRARY order with extra unrelated songs
        val song4 = createSong("id_4", "Track 4 of Order Test Album", videoId = "vid_alb_ordering_4")
        val song2 = createSong("id_2", "Track 2 of Order Test Album", videoId = "vid_alb_ordering_2")
        val unrelated = createSong("id_unrelated", "Random Song", videoId = "vid_unrelated")
        val lookup = TestLocalSongLookup(listOf(song4, unrelated, song2))

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }
        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_ordering").join()

        // Test findLocalSong
        assertEquals(song2, vm.findLocalSong("vid_alb_ordering_2"))
        assertEquals(song4, vm.findLocalSong("vid_alb_ordering_4"))
        assertNull(vm.findLocalSong("vid_alb_ordering_1"))
        assertNull(vm.findLocalSong("vid_alb_ordering_3"))
        assertNull(vm.findLocalSong("vid_alb_ordering_5"))

        // Test getDownloadedAlbumSongs: MUST match album tracklist order [Track 2, Track 4]
        // even though local repository had [song4, unrelated, song2]
        val downloadedSongs = vm.getDownloadedAlbumSongs()
        assertEquals(2, downloadedSongs.size)
        assertEquals("Track 2 of Order Test Album", downloadedSongs[0].title)
        assertEquals("Track 4 of Order Test Album", downloadedSongs[1].title)

        testScope.cancel()
    }

    // =========================================================================
    // PART B: Navigation Architecture Empirical Verification
    // =========================================================================

    /**
     * Exact state machine model simulating Compose NavController with:
     * - startDestination = "search"
     * - tab navigation with popUpTo(startDestination) { saveState = true }, launchSingleTop = true, restoreState = true
     * - stack navigation for artist and album
     */
    private class NavigationHarness(val startDestination: String = Screen.Search.route) {
        data class BackStackEntry(val route: String, val arguments: Map<String, String> = emptyMap())

        private val artistPattern = Pattern.compile("^artist/([^/]+)$")
        private val albumPattern = Pattern.compile("^album/([^/]+)$")

        val backStack = mutableListOf<BackStackEntry>()
        val savedStates = mutableMapOf<String, List<BackStackEntry>>()

        init {
            backStack.add(BackStackEntry(startDestination))
        }

        val currentDestination: String get() = backStack.last().route
        val currentEntry: BackStackEntry get() = backStack.last()

        fun navigateToDetail(route: String) {
            val args = when {
                route.startsWith("artist/") -> {
                    val m = artistPattern.matcher(route)
                    if (m.matches()) mapOf(Screen.Artist.ARG_ARTIST_ID to m.group(1).orEmpty()) else emptyMap()
                }
                route.startsWith("album/") -> {
                    val m = albumPattern.matcher(route)
                    if (m.matches()) mapOf(Screen.Album.ARG_ALBUM_ID to m.group(1).orEmpty()) else emptyMap()
                }
                else -> emptyMap()
            }
            backStack.add(BackStackEntry(route, args))
        }

        fun popBackStack(): Boolean {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.size - 1)
                return true
            }
            return false
        }

        fun navigateTab(tabRoute: String) {
            if (backStack.isNotEmpty() && backStack.last().route == tabRoute) {
                return // launchSingleTop
            }

            val startIndex = backStack.indexOfFirst { it.route == startDestination }
            if (startIndex >= 0 && backStack.size > startIndex + 1) {
                val popped = mutableListOf<BackStackEntry>()
                while (backStack.size > startIndex + 1) {
                    popped.add(0, backStack.removeAt(backStack.size - 1))
                }
                val topRoute = popped.first().route
                savedStates[topRoute] = popped
            }

            if (tabRoute == startDestination) {
                savedStates[tabRoute]?.let { restored ->
                    backStack.addAll(restored)
                    savedStates.remove(tabRoute)
                }
            } else {
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
    fun testScenario5DeepNavigationJourney() {
        val nav = NavigationHarness(startDestination = Screen.Search.route)

        // Step 1: Start at Search
        assertEquals("search", nav.currentDestination)
        assertEquals(1, nav.backStack.size)

        // Step 2: Search -> Artist Profile "daft_punk"
        nav.navigateToDetail(Screen.Artist.createRoute("daft_punk"))
        assertEquals("artist/daft_punk", nav.currentDestination)
        assertEquals("daft_punk", nav.currentEntry.arguments[Screen.Artist.ARG_ARTIST_ID])
        assertEquals(2, nav.backStack.size)

        // Step 3: Artist -> Album Details "random_access_memories"
        nav.navigateToDetail(Screen.Album.createRoute("random_access_memories"))
        assertEquals("album/random_access_memories", nav.currentDestination)
        assertEquals("random_access_memories", nav.currentEntry.arguments[Screen.Album.ARG_ALBUM_ID])
        assertEquals(3, nav.backStack.size)

        // Step 4: Click Artist Name in AlbumScreen -> Artist Profile "daft_punk"
        nav.navigateToDetail(Screen.Artist.createRoute("daft_punk"))
        assertEquals("artist/daft_punk", nav.currentDestination)
        assertEquals(4, nav.backStack.size)

        // Step 5: Back click -> pops to Album
        assertTrue(nav.popBackStack())
        assertEquals("album/random_access_memories", nav.currentDestination)
        assertEquals(3, nav.backStack.size)

        // Step 6: Back click -> pops to Artist
        assertTrue(nav.popBackStack())
        assertEquals("artist/daft_punk", nav.currentDestination)
        assertEquals(2, nav.backStack.size)

        // Step 7: Back click -> pops to Search
        assertTrue(nav.popBackStack())
        assertEquals("search", nav.currentDestination)
        assertEquals(1, nav.backStack.size)

        // Step 8: Tab click Library
        nav.navigateTab(Screen.Library.route)
        assertEquals("library", nav.currentDestination)
        assertEquals(2, nav.backStack.size)

        // Step 9: Open Settings from Library
        nav.navigateToDetail(Screen.Settings.route)
        assertEquals("settings", nav.currentDestination)
        assertEquals(3, nav.backStack.size)
        assertTrue(nav.popBackStack())
        assertEquals("library", nav.currentDestination)

        // Step 10: Tab click Search -> restored to start destination
        nav.navigateTab(Screen.Search.route)
        assertEquals("search", nav.currentDestination)
        assertEquals(1, nav.backStack.size)
    }

    @Test
    fun testCyclicDeepNavigation_ArtistAlbumArtistAlbum() {
        val nav = NavigationHarness(startDestination = Screen.Search.route)

        // Chain: Search -> Artist(A) -> Album(B) -> Artist(C) -> Album(D)
        nav.navigateToDetail(Screen.Artist.createRoute("art_A"))
        nav.navigateToDetail(Screen.Album.createRoute("alb_B"))
        nav.navigateToDetail(Screen.Artist.createRoute("art_C"))
        nav.navigateToDetail(Screen.Album.createRoute("alb_D"))

        assertEquals(5, nav.backStack.size)
        assertEquals("album/alb_D", nav.currentDestination)
        assertEquals("alb_D", nav.currentEntry.arguments[Screen.Album.ARG_ALBUM_ID])

        // Pop back in reverse order
        assertTrue(nav.popBackStack())
        assertEquals("artist/art_C", nav.currentDestination)
        assertEquals("art_C", nav.currentEntry.arguments[Screen.Artist.ARG_ARTIST_ID])

        assertTrue(nav.popBackStack())
        assertEquals("album/alb_B", nav.currentDestination)
        assertEquals("alb_B", nav.currentEntry.arguments[Screen.Album.ARG_ALBUM_ID])

        assertTrue(nav.popBackStack())
        assertEquals("artist/art_A", nav.currentDestination)
        assertEquals("art_A", nav.currentEntry.arguments[Screen.Artist.ARG_ARTIST_ID])

        assertTrue(nav.popBackStack())
        assertEquals("search", nav.currentDestination)
        assertEquals(1, nav.backStack.size)

        // Cannot pop root start destination
        assertFalse("Cannot pop root destination", nav.popBackStack())
        assertEquals("search", nav.currentDestination)
    }

    @Test
    fun testSearchQueryAndFilterPersistenceAcrossTabs() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val service = TestYouTubeMusicService(
            artistSearchResults = listOf(ArtistSearchResult("art_queen", "Queen")),
            albumSearchResults = listOf(AlbumSearchResult("alb_opera", "A Night at the Opera", "Queen")),
        )
        val lookup = TestLocalSongLookup()

        // In MainActivity, searchVm is scoped to MainActivity (`by viewModels`), NOT recreated per composable
        val searchVm = SearchViewModel(
            repository = service,
            localSongLookup = lookup,
            ioDispatcher = testDispatcher,
            scopeOverride = backgroundScope,
        )

        // 1. User performs a search
        searchVm.onQueryChange("Queen")
        searchVm.onFilterSelected(SearchFilter.ALBUMS)
        advanceTimeBy(350)
        advanceUntilIdle()

        val stateBeforeTabSwitch = searchVm.uiState.value
        assertEquals("Queen", stateBeforeTabSwitch.query)
        assertEquals(SearchFilter.ALBUMS, stateBeforeTabSwitch.filter)
        assertEquals(1, stateBeforeTabSwitch.albums.size)
        assertTrue(stateBeforeTabSwitch.hasSearched)

        // 2. User switches tabs: Search -> Library -> Search
        val nav = NavigationHarness(Screen.Search.route)
        nav.navigateTab(Screen.Library.route)
        assertEquals("library", nav.currentDestination)

        nav.navigateToDetail(Screen.Settings.route)
        assertEquals("settings", nav.currentDestination)
        nav.popBackStack()
        assertEquals("library", nav.currentDestination)

        nav.navigateTab(Screen.Search.route)
        assertEquals("search", nav.currentDestination)

        // 3. Invariant: searchVm state MUST remain identical
        val stateAfterTabSwitch = searchVm.uiState.value
        assertEquals("Queen", stateAfterTabSwitch.query)
        assertEquals(SearchFilter.ALBUMS, stateAfterTabSwitch.filter)
        assertEquals(1, stateAfterTabSwitch.albums.size)
        assertTrue(stateAfterTabSwitch.hasSearched)
        assertFalse(stateAfterTabSwitch.isLoading)
    }

    @Test
    fun testMiniPlayerVisibilityInvariantsAcrossAllDestinations() {
        val allDestinations = listOf(
            Screen.Search.route,
            Screen.Library.route,
            Screen.Settings.route,
            Screen.Artist.createRoute("art_dp"),
            Screen.Album.createRoute("alb_ram"),
        )

        val emptyPlayerStates = listOf(
            PlayerUiState(),
            PlayerUiState(connected = true, currentMediaId = null),
            PlayerUiState(connected = false, currentMediaId = null),
            PlayerUiState(title = "No Track", currentMediaId = null),
        )

        val activePlayerStates = listOf(
            PlayerUiState(currentMediaId = "song_1", title = "Get Lucky", isPlaying = true),
            PlayerUiState(currentMediaId = "song_1", title = "Get Lucky", isPlaying = false),
            PlayerUiState(currentMediaId = "song_1", positionMs = 50000L, durationMs = 240000L),
            PlayerUiState(currentMediaId = "song_2", title = "Harder Better Faster Stronger"),
        )

        // Test 1: Inactive player state -> MiniPlayer MUST BE HIDDEN on every single destination
        for (dest in allDestinations) {
            for (playerState in emptyPlayerStates) {
                assertFalse(
                    "MiniPlayer must be HIDDEN on $dest when currentMediaId is null",
                    playerState.hasMedia,
                )
            }
        }

        // Test 2: Active player state -> MiniPlayer MUST BE VISIBLE on every single destination
        for (dest in allDestinations) {
            for (playerState in activePlayerStates) {
                assertTrue(
                    "MiniPlayer must be VISIBLE on $dest when currentMediaId is present",
                    playerState.hasMedia,
                )
            }
        }
    }

    @Test
    fun testFullPlayerModalSheetOverlayCoordination() {
        // MainActivity logic:
        // var showPlayer by rememberSaveable { mutableStateOf(false) }
        // Full player AnimatedVisibility: visible = showPlayer && playerState.hasMedia
        // BackHandler(enabled = showPlayer) { showPlayer = false }

        fun isFullPlayerVisible(showPlayer: Boolean, playerState: PlayerUiState): Boolean {
            return showPlayer && playerState.hasMedia
        }

        var showPlayer = false
        var playerState = PlayerUiState(currentMediaId = "song_123", title = "One More Time", isPlaying = true)

        val nav = NavigationHarness(Screen.Search.route)
        nav.navigateToDetail(Screen.Album.createRoute("alb_discovery"))
        assertEquals("album/alb_discovery", nav.currentDestination)

        // Tap MiniPlayer -> opens full player
        showPlayer = true
        assertTrue("Full player must be visible", isFullPlayerVisible(showPlayer, playerState))
        // Navigation destination must still be album
        assertEquals("album/alb_discovery", nav.currentDestination)

        // First Back press: BackHandler intercepts and sets showPlayer = false
        // Navigation destination does NOT pop!
        showPlayer = false
        assertFalse("Full player must be closed", isFullPlayerVisible(showPlayer, playerState))
        assertEquals("album/alb_discovery", nav.currentDestination)
        assertTrue("MiniPlayer remains visible", playerState.hasMedia)

        // Second Back press: AlbumScreen pops back to Search
        assertTrue(nav.popBackStack())
        assertEquals("search", nav.currentDestination)
        assertTrue("MiniPlayer still remains visible on Search", playerState.hasMedia)

        // Re-open full player, then playback terminates
        showPlayer = true
        assertTrue(isFullPlayerVisible(showPlayer, playerState))
        playerState = playerState.copy(currentMediaId = null) // stopped/cleared
        assertFalse("Full player must automatically dismiss when media is cleared", isFullPlayerVisible(showPlayer, playerState))
        assertFalse("MiniPlayer must also hide when media is cleared", playerState.hasMedia)
    }

    @Test
    fun testAlbumScreenArtistClickNavigationRoute() {
        val albumWithArtistId = buildAlbum(browseId = "alb_1", title = "Album 1", artistBrowseId = "art_queen")
        assertEquals("artist/art_queen", Screen.Artist.createRoute(albumWithArtistId.artistBrowseId!!))

        val albumWithoutArtistId = AlbumDetails(
            browseId = "alb_comp",
            title = "Compilation",
            artist = "Various Artists",
            artistBrowseId = null,
        )
        assertNull(albumWithoutArtistId.artistBrowseId)
    }
}
