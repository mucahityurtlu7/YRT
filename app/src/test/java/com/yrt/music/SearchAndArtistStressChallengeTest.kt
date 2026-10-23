package com.yrt.music

import com.yrt.music.data.downloader.YtDlpDownloadManager
import com.yrt.music.data.model.AlbumDetails
import com.yrt.music.data.model.AlbumSearchResult
import com.yrt.music.data.model.ArtistProfile
import com.yrt.music.data.model.ArtistSearchResult
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.data.repository.YouTubeMusicService
import com.yrt.music.ui.album.AlbumViewModel
import com.yrt.music.ui.artist.ArtistViewModel
import com.yrt.music.ui.components.HeroDownloadState
import com.yrt.music.ui.search.SearchFilter
import com.yrt.music.ui.search.SearchViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SearchAndArtistStressChallengeTest {

    // ==========================================
    // Test Doubles & Mocks
    // ==========================================

    private class ControllableMusicService : YouTubeMusicService {
        var artistResultDelayMs: Long = 0L
        var albumResultDelayMs: Long = 0L
        var artistResponse: Result<List<ArtistSearchResult>> = Result.success(emptyList())
        var albumResponse: Result<List<AlbumSearchResult>> = Result.success(emptyList())
        var artistProfileResponse: Result<ArtistProfile> = Result.failure(UnsupportedOperationException())
        var albumDetailsResponse: Result<AlbumDetails> = Result.failure(UnsupportedOperationException())

        val artistCallCount = AtomicInteger(0)
        val albumCallCount = AtomicInteger(0)
        val artistQueries = mutableListOf<String>()
        val albumQueries = mutableListOf<String>()

        override suspend fun searchArtists(query: String): Result<List<ArtistSearchResult>> {
            artistCallCount.incrementAndGet()
            synchronized(artistQueries) { artistQueries.add(query) }
            if (artistResultDelayMs > 0) delay(artistResultDelayMs)
            return artistResponse
        }

        override suspend fun searchAlbums(query: String): Result<List<AlbumSearchResult>> {
            albumCallCount.incrementAndGet()
            synchronized(albumQueries) { albumQueries.add(query) }
            if (albumResultDelayMs > 0) delay(albumResultDelayMs)
            return albumResponse
        }

        override suspend fun getArtistProfile(browseId: String): Result<ArtistProfile> {
            return artistProfileResponse
        }

        override suspend fun getAlbumDetails(browseId: String): Result<AlbumDetails> {
            return albumDetailsResponse
        }
    }

    private class DynamicLocalSongLookup(
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

        fun setSongs(songs: List<Song>) {
            _songs.value = songs
        }
    }

    private fun dummySong(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        videoId: String? = null,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = 180000L,
        file = File("/music/$id.m4a"),
        artworkFile = null,
        lastModified = System.currentTimeMillis(),
        videoId = videoId,
    )

    // ==========================================
    // 1. SearchViewModel Stress Tests
    // ==========================================

    @Test
    fun testRapidTypingBurst_50Keystrokes_TriggersExactlyOneNetworkCall() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val service = ControllableMusicService().apply {
            artistResponse = Result.success(listOf(ArtistSearchResult("a1", "Radiohead")))
            albumResponse = Result.success(listOf(AlbumSearchResult("alb1", "OK Computer", "Radiohead")))
        }
        val lookup = DynamicLocalSongLookup()
        val vm = SearchViewModel(service, lookup, testDispatcher, backgroundScope)

        // Rapid keystroke burst: 50 characters typed rapidly with 20ms between keys
        var currentQuery = ""
        val targetQuery = "Radiohead - Paranoid Android Long Search Query Keystroke Burst"
        for (char in targetQuery) {
            currentQuery += char
            vm.onQueryChange(currentQuery)
            advanceTimeBy(20) // 20ms < 300ms debounce
        }

        // Debounce window has NOT elapsed yet since last keystroke
        assertEquals(0, service.artistCallCount.get())
        assertEquals(0, service.albumCallCount.get())

        // Now advance debounce past 300ms
        advanceTimeBy(350)
        advanceUntilIdle()

        // Exactly one call should have been made for the final full query string
        assertEquals(1, service.artistCallCount.get())
        assertEquals(1, service.albumCallCount.get())
        assertEquals(targetQuery, service.artistQueries.last())
        assertEquals(targetQuery, service.albumQueries.last())

        val uiState = vm.uiState.value
        assertEquals(targetQuery, uiState.query)
        assertEquals(1, uiState.artists.size)
        assertEquals(1, uiState.albums.size)
        assertFalse(uiState.isLoading)
        assertTrue(uiState.hasSearched)
    }

    @Test
    fun testDebounceCancellation_PreviousInFlightSearchDoesNotOverwriteNewerQuery() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val service = ControllableMusicService().apply {
            artistResultDelayMs = 200 // simulated network latency
            artistResponse = Result.success(listOf(ArtistSearchResult("a_old", "Old Artist")))
        }
        val lookup = DynamicLocalSongLookup()
        val vm = SearchViewModel(service, lookup, testDispatcher, backgroundScope)

        // Type query 1
        vm.onQueryChange("Query1")
        advanceTimeBy(350) // Debounce triggers, executeSearch("Query1") starts and is delayed 200ms

        // Before Query 1 finishes, user types Query 2
        advanceTimeBy(50)
        service.artistResponse = Result.success(listOf(ArtistSearchResult("a_new", "New Artist")))
        vm.onQueryChange("Query2")

        // Advance debounce for Query 2 (300ms) plus latency (200ms)
        advanceTimeBy(350)
        advanceTimeBy(250)
        advanceUntilIdle()

        // UI state must hold results for Query2, never overridden by Query1
        val uiState = vm.uiState.value
        assertEquals("Query2", uiState.query)
        assertEquals(1, uiState.artists.size)
        assertEquals("New Artist", uiState.artists.first().name)
        assertFalse(uiState.isLoading)
    }

    @Test
    fun testFilterChipsSwitching_CategoryFilteringIsolation() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val service = ControllableMusicService().apply {
            artistResponse = Result.success(listOf(ArtistSearchResult("a1", "Daft Punk")))
            albumResponse = Result.success(listOf(AlbumSearchResult("alb1", "Discovery", "Daft Punk")))
        }
        val lookup = DynamicLocalSongLookup(
            listOf(dummySong("s1", "Harder Better Faster Stronger", "Daft Punk", "Discovery", "v1"))
        )
        val vm = SearchViewModel(service, lookup, testDispatcher, backgroundScope)

        // Search with ALL
        vm.onQueryChange("Daft")
        advanceTimeBy(350)
        advanceUntilIdle()

        var state = vm.uiState.value
        assertEquals(SearchFilter.ALL, state.filter)
        assertEquals(1, state.artists.size)
        assertEquals(1, state.albums.size)
        assertEquals(1, state.localTracks.size) // local tracks appear in ALL
        assertEquals(1, service.artistCallCount.get())
        assertEquals(1, service.albumCallCount.get())

        // Switch to ARTISTS: only artists searched, albums and local tracks isolated
        vm.onFilterSelected(SearchFilter.ARTISTS)?.join()
        advanceUntilIdle()

        state = vm.uiState.value
        assertEquals(SearchFilter.ARTISTS, state.filter)
        assertEquals(1, state.artists.size)
        assertTrue(state.albums.isEmpty()) // Albums cleared/not fetched
        assertTrue(state.localTracks.isEmpty()) // Local tracks cleared in ARTISTS
        assertEquals(2, service.artistCallCount.get())
        assertEquals(1, service.albumCallCount.get()) // album call count unchanged

        // Switch to ALBUMS: only albums searched
        vm.onFilterSelected(SearchFilter.ALBUMS)?.join()
        advanceUntilIdle()

        state = vm.uiState.value
        assertEquals(SearchFilter.ALBUMS, state.filter)
        assertTrue(state.artists.isEmpty()) // Artists cleared
        assertEquals(1, state.albums.size)
        assertTrue(state.localTracks.isEmpty())
        assertEquals(2, service.artistCallCount.get()) // artist call count unchanged
        assertEquals(2, service.albumCallCount.get())

        // Selecting same filter again is a NO-OP
        val redundantJob = vm.onFilterSelected(SearchFilter.ALBUMS)
        assertNull(redundantJob)
        assertEquals(2, service.albumCallCount.get())
    }

    @Test
    fun testLocalSongFiltering_ExhaustiveFieldMatchingAndCaseInsensitivity() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val library = listOf(
            dummySong("1", "Bohemian Rhapsody", "Queen", "A Night at the Opera", "v1"),
            dummySong("2", "Another One Bites the Dust", "Queen", "The Game", "v2"),
            dummySong("3", "Under Pressure", "Queen & David Bowie", "Hot Space", "v3"),
            dummySong("4", "Space Oddity", "David Bowie", "David Bowie", "v4"),
            dummySong("5", "Heroes", "David Bowie", "\"Heroes\"", "v5"),
        )
        val service = ControllableMusicService()
        val lookup = DynamicLocalSongLookup(library)
        val vm = SearchViewModel(service, lookup, testDispatcher, backgroundScope)

        // 1. Match by Track Title (case-insensitive "rhapsody")
        vm.onQueryChange("rhapsody")
        advanceTimeBy(350)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.localTracks.size)
        assertEquals("Bohemian Rhapsody", vm.uiState.value.localTracks.first().title)

        // 2. Match by Artist Name ("david bowie" matches songs 3, 4, 5)
        vm.onQueryChange("david bowie")
        advanceTimeBy(350)
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.localTracks.size)

        // 3. Match by Album Name ("the game" matches song 2)
        vm.onQueryChange("the game")
        advanceTimeBy(350)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.localTracks.size)
        assertEquals("Another One Bites the Dust", vm.uiState.value.localTracks.first().title)

        // 4. Non-matching query yields 0 local tracks
        vm.onQueryChange("NonExistentTrackQuery999")
        advanceTimeBy(350)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.localTracks.isEmpty())
    }

    @Test
    fun testErrorRecoveryAndRetry_FullCycle() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val service = ControllableMusicService().apply {
            artistResponse = Result.failure(IOException("DNS lookup failed"))
            albumResponse = Result.failure(IOException("DNS lookup failed"))
        }
        val lookup = DynamicLocalSongLookup()
        val vm = SearchViewModel(service, lookup, testDispatcher, backgroundScope)

        // Query fails with network error
        vm.onQueryChange("RetryTest")
        advanceTimeBy(350)
        advanceUntilIdle()

        var state = vm.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.hasSearched)
        assertNotNull(state.errorMessage)
        assertEquals("DNS lookup failed", state.errorMessage)

        // Now network recovers
        service.artistResponse = Result.success(listOf(ArtistSearchResult("a_rec", "Recovered Artist")))
        service.albumResponse = Result.success(listOf(AlbumSearchResult("alb_rec", "Recovered Album", "Recovered Artist")))

        // Trigger retry
        val retryJob = vm.retry()
        assertNotNull(retryJob)
        retryJob?.join()
        advanceUntilIdle()

        state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(1, state.artists.size)
        assertEquals(1, state.albums.size)
        assertEquals("Recovered Artist", state.artists.first().name)
    }

    @Test
    fun testBlankAndWhitespaceQueries_CleanResetWithoutNetworkCall() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val service = ControllableMusicService()
        val lookup = DynamicLocalSongLookup()
        val vm = SearchViewModel(service, lookup, testDispatcher, backgroundScope)

        vm.onQueryChange("   \t  \n ")
        advanceTimeBy(400)
        advanceUntilIdle()

        assertEquals(0, service.artistCallCount.get())
        assertEquals(0, service.albumCallCount.get())
        assertFalse(vm.uiState.value.hasSearched)
        assertFalse(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.errorMessage)
    }

    // ==========================================
    // 2. ArtistViewModel & Bulk Download Stress Tests
    // ==========================================

    private fun sampleArtistProfile(
        browseId: String = "art_muse",
        name: String = "Muse",
        tracks: List<RemoteTrack> = listOf(
            RemoteTrack("vid_uprising", "Uprising", "Muse", "The Resistance", 304000),
            RemoteTrack("vid_starlight", "Starlight", "Muse", "Black Holes and Revelations", 240000),
            RemoteTrack("vid_supermassive", "Supermassive Black Hole", "Muse", "Black Holes and Revelations", 209000),
            RemoteTrack("vid_hysteria", "Hysteria", "Muse", "Absolution", 227000),
        ),
    ) = ArtistProfile(
        browseId = browseId,
        name = name,
        heroArtworkUrl = "https://example.com/muse.jpg",
        description = "English rock band.",
        subscribers = "4.5M",
        topSongs = tracks,
        albums = listOf(
            AlbumSearchResult("alb_bh", "Black Holes and Revelations", "Muse", "2006"),
            AlbumSearchResult("alb_res", "The Resistance", "Muse", "2009"),
        ),
    )

    @Test
    fun testDownloadAllSongs_AudioFormatM4A_BatchTitle_And_RequestMapping() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val artist = sampleArtistProfile()
        val service = ControllableMusicService().apply {
            artistProfileResponse = Result.success(artist)
        }
        val lookup = DynamicLocalSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadArtist("art_muse").join()

        // Trigger "Download All Songs"
        vm.downloadAllSongs()

        // 1. Verify batch ID format and title
        val expectedBatchId = "artist_art_muse"
        val expectedBatchTitle = "Sanatçı: Muse"
        val batch = manager.batches.value.find { it.id == expectedBatchId }
        assertNotNull("Batch must be registered in downloadManager", batch)
        assertEquals(expectedBatchId, batch?.id)
        assertEquals(expectedBatchTitle, batch?.title)
        assertEquals(4, batch?.totalCount)

        // 2. Verify all tasks have AudioFormat.M4A and correct request mapping
        val tasks = manager.tasks.value.filter { it.batchId == expectedBatchId }
        assertEquals(4, tasks.size)
        for (task in tasks) {
            assertEquals("All batch tasks must use AudioFormat.M4A", AudioFormat.M4A, task.format)
            assertEquals("Muse", task.artist)
            assertTrue("Task URL must match YouTube watch format", task.url.startsWith("https://www.youtube.com/watch?v="))
            assertNotNull(task.durationMs)
            assertTrue(task.durationMs!! > 0)
        }

        testScope.cancel()
    }

    @Test
    fun testDownloadAllSongs_IntraBatchAndLibraryDeduplication() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Artist profile with duplicate videoIds in topSongs
        val duplicateTracks = listOf(
            RemoteTrack("vid_dup1", "Song A", "Artist", "Album 1", 200000),
            RemoteTrack("vid_dup1", "Song A (Remastered)", "Artist", "Album 2", 205000), // DUPLICATE videoId
            RemoteTrack("vid_unique2", "Song B", "Artist", "Album 1", 180000),
            RemoteTrack("vid_already_downloaded", "Song C", "Artist", "Album 1", 210000), // ALREADY IN LIBRARY
        )
        val artist = sampleArtistProfile("art_dedup", "Dedup Artist", duplicateTracks)
        val service = ControllableMusicService().apply {
            artistProfileResponse = Result.success(artist)
        }

        // Library already has 'vid_already_downloaded'
        val lookup = DynamicLocalSongLookup(
            listOf(dummySong("local_c", "Song C", "Artist", "Album 1", "vid_already_downloaded"))
        )
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadArtist("art_dedup").join()

        vm.downloadAllSongs()

        val batch = manager.batches.value.find { it.id == "artist_art_dedup" }
        assertNotNull(batch)

        // Total requested was 4 tracks:
        // - vid_dup1 (2 entries -> deduplicated to 1)
        // - vid_unique2 (1 entry -> enqueued)
        // - vid_already_downloaded (1 entry -> skipped because in library)
        // Expected enqueued tasks: exactly 2 (vid_dup1, vid_unique2)
        assertEquals(2, batch?.totalCount)
        val taskVideoIds = manager.tasks.value.filter { it.batchId == "artist_art_dedup" }.map { it.videoId }
        assertEquals(2, taskVideoIds.size)
        assertTrue(taskVideoIds.contains("vid_dup1"))
        assertTrue(taskVideoIds.contains("vid_unique2"))
        assertFalse(taskVideoIds.contains("vid_already_downloaded"))

        testScope.cancel()
    }

    @Test
    fun testCrossEntityDeduplication_ArtistTopSongsAndAlbumDetails() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Artist has songs v1, v2
        val artistTracks = listOf(
            RemoteTrack("v1", "Track 1", "Daft Punk", "Discovery", 200000),
            RemoteTrack("v2", "Track 2", "Daft Punk", "Discovery", 210000),
        )
        val artist = sampleArtistProfile("art_cross", "Daft Punk", artistTracks)

        // Album has songs v2 (overlap), v3, v4
        val albumTracks = listOf(
            RemoteTrack("v2", "Track 2", "Daft Punk", "Discovery", 210000), // overlaps with artist top song!
            RemoteTrack("v3", "Track 3", "Daft Punk", "Discovery", 220000),
            RemoteTrack("v4", "Track 4", "Daft Punk", "Discovery", 230000),
        )
        val albumDetails = AlbumDetails("alb_discovery", "Discovery", "Daft Punk", "art_cross", "2001", null, 3, "10:00", albumTracks)

        val service = ControllableMusicService().apply {
            artistProfileResponse = Result.success(artist)
            albumDetailsResponse = Result.success(albumDetails)
        }
        val lookup = DynamicLocalSongLookup()

        // Keep downloads running/in-flight using a deferred latch so they stay in activeTasks
        val inFlightLatch = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            inFlightLatch.await()
        }

        val artistVm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        artistVm.loadArtist("art_cross").join()

        // 1. User downloads artist top songs (v1, v2 become Queued / Running)
        artistVm.downloadAllSongs()
        assertEquals(2, manager.tasks.value.size)

        // 2. User navigates to album and clicks "Download Album"
        val albumVm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        albumVm.loadAlbum("alb_discovery").join()
        val result = albumVm.downloadAlbum()

        assertNotNull(result)
        // Total requested in album was 3 (v2, v3, v4).
        // Since v2 is currently active in the download manager, it MUST NOT be enqueued again!
        assertEquals(3, result?.totalRequested)
        assertEquals(2, result?.enqueuedCount) // only v3, v4 enqueued
        assertEquals(1, result?.alreadyDownloadedCount) // v2 skipped due to in-flight deduplication

        val allActiveVideoIds = manager.tasks.value.map { it.videoId }
        // Unique videoIds should be v1, v2, v3, v4 (no duplicate tasks for v2)
        assertEquals(4, allActiveVideoIds.size)
        assertEquals(4, allActiveVideoIds.distinct().size)

        inFlightLatch.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testHeroDownloadState_FullLifecycleTransitions() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val artist = sampleArtistProfile("art_lifecycle", "Rockers", listOf(
            RemoteTrack("v_a", "Song A", "Rockers", "Album A", 180000),
            RemoteTrack("v_b", "Song B", "Rockers", "Album A", 190000),
        ))
        val service = ControllableMusicService().apply {
            artistProfileResponse = Result.success(artist)
        }
        val lookup = DynamicLocalSongLookup()

        val downloadHold = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, onProgress ->
            onProgress(45f, 15, "[download] 45%")
            downloadHold.await()
        }

        val vm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadArtist("art_lifecycle").join()

        // 1. Initial State: Idle (Not Downloaded)
        assertEquals(HeroDownloadState.Idle, vm.uiState.value.heroDownloadState)

        // 2. Trigger Download All Songs -> Transitions to InProgress
        vm.downloadAllSongs()

        // Wait for heroDownloadState to transition to InProgress
        val inProgressState = vm.uiState.first { it.heroDownloadState is HeroDownloadState.InProgress }.heroDownloadState
        assertTrue("Hero state must be InProgress when batch is running", inProgressState is HeroDownloadState.InProgress)

        // 3. Complete download and add songs to library
        lookup.setSongs(listOf(
            dummySong("s_a", "Song A", "Rockers", "Album A", "v_a"),
            dummySong("s_b", "Song B", "Rockers", "Album A", "v_b"),
        ))
        downloadHold.complete(Unit)

        // Wait for heroDownloadState to transition to Completed
        val completedState = vm.uiState.first { it.heroDownloadState == HeroDownloadState.Completed }.heroDownloadState
        assertEquals(HeroDownloadState.Completed, completedState)

        testScope.cancel()
    }
}
