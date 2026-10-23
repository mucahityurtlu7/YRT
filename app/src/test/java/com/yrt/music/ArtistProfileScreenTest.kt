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
import com.yrt.music.ui.artist.ArtistViewModel
import com.yrt.music.ui.components.HeroDownloadState
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistProfileScreenTest {

    private class FakeYouTubeMusicService(
        var profileResult: Result<ArtistProfile>? = null,
    ) : YouTubeMusicService {
        var profileCallCount = 0
        var lastBrowseId: String? = null

        override suspend fun getArtistProfile(browseId: String): Result<ArtistProfile> {
            profileCallCount++
            lastBrowseId = browseId
            return profileResult ?: Result.failure(IOException("Profile not found"))
        }

        override suspend fun searchArtists(query: String): Result<List<ArtistSearchResult>> = Result.success(emptyList())
        override suspend fun searchAlbums(query: String): Result<List<AlbumSearchResult>> = Result.success(emptyList())
        override suspend fun getAlbumDetails(browseId: String): Result<AlbumDetails> = Result.failure(UnsupportedOperationException())
    }

    private class FakeSongLookup(
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
    }

    private fun sampleArtist(
        browseId: String = "art_123",
        name: String = "Daft Punk",
        tracks: List<RemoteTrack> = listOf(
            RemoteTrack("vid_1", "One More Time", "Daft Punk", "Discovery", 320000),
            RemoteTrack("vid_2", "Harder Better Faster Stronger", "Daft Punk", "Discovery", 224000),
            RemoteTrack("vid_3", "Get Lucky", "Daft Punk", "RAM", 248000),
        ),
        albums: List<AlbumSearchResult> = listOf(
            AlbumSearchResult("alb_1", "Discovery", "Daft Punk", "2001"),
            AlbumSearchResult("alb_2", "Random Access Memories", "Daft Punk", "2013"),
        ),
    ) = ArtistProfile(
        browseId = browseId,
        name = name,
        heroArtworkUrl = "https://example.com/banner.jpg",
        description = "Legendary electronic duo.",
        subscribers = "8.1M",
        topSongs = tracks,
        albums = albums,
    )

    @Test
    fun testInitialState() {
        val service = FakeYouTubeMusicService()
        val lookup = FakeSongLookup()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(service, manager, lookup)
        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.artist)
        assertNull(state.errorMessage)
        assertEquals(HeroDownloadState.Idle, state.heroDownloadState)
        testScope.cancel()
    }

    @Test
    fun testLoadArtist_Success_PopulatesProfileAndTopSongs() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val artist = sampleArtist()
        val service = FakeYouTubeMusicService(profileResult = Result.success(artist))
        val lookup = FakeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(
            youTubeMusicRepository = service,
            downloadManager = manager,
            songRepository = lookup,
            ioDispatcher = Dispatchers.Default,
            scopeOverride = testScope,
        )

        vm.loadArtist("art_123").join()

        assertEquals(1, service.profileCallCount)
        assertEquals("art_123", service.lastBrowseId)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.artist)
        assertEquals("Daft Punk", state.artist?.name)
        assertEquals(3, state.artist?.topSongs?.size)
        assertEquals(2, state.artist?.albums?.size)
        assertNull(state.errorMessage)
        testScope.cancel()
    }

    @Test
    fun testLoadArtist_RedundantCall_SkipsNetwork() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val artist = sampleArtist()
        val service = FakeYouTubeMusicService(profileResult = Result.success(artist))
        val lookup = FakeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)

        vm.loadArtist("art_123").join()
        assertEquals(1, service.profileCallCount)

        // Calling loadArtist again for the same id without forceRefresh
        vm.loadArtist("art_123", forceRefresh = false).join()
        assertEquals(1, service.profileCallCount) // skipped!

        // With forceRefresh = true
        vm.loadArtist("art_123", forceRefresh = true).join()
        assertEquals(2, service.profileCallCount)
        testScope.cancel()
    }

    @Test
    fun testLoadArtist_BlankId_SetsErrorMessage() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = FakeYouTubeMusicService()
        val lookup = FakeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)

        vm.loadArtist("   ").join()

        assertEquals(0, service.profileCallCount)
        assertEquals("Geçersiz sanatçı kimliği", vm.uiState.value.errorMessage)
        testScope.cancel()
    }

    @Test
    fun testLoadArtist_NetworkFailure_SetsErrorState() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = FakeYouTubeMusicService(profileResult = Result.failure(IOException("Server timeout")))
        val lookup = FakeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)

        vm.loadArtist("art_fail").join()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.artist)
        assertEquals("Server timeout", state.errorMessage)
        testScope.cancel()
    }

    @Test
    fun testDownloadAllSongs_EnqueuesBatchWithExactBatchIdAndTitle() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val artist = sampleArtist("art_dp", "Daft Punk")
        val service = FakeYouTubeMusicService(profileResult = Result.success(artist))
        val lookup = FakeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadArtist("art_dp").join()

        vm.downloadAllSongs()

        val batch = manager.batches.value.find { it.id == "artist_art_dp" }
        assertNotNull(batch)
        assertEquals("artist_art_dp", batch?.id)
        assertEquals("Sanatçı: Daft Punk", batch?.title)
        assertEquals(3, batch?.totalCount)
        testScope.cancel()
    }

    private fun testSong(
        id: String,
        title: String,
        artist: String = "Daft Punk",
        videoId: String? = null,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        durationMs = 200000L,
        file = java.io.File("/music/$id.m4a"),
        artworkFile = null,
        lastModified = 0L,
        videoId = videoId,
    )

    @Test
    fun testDownloadAllSongs_Deduplication_SkipsAlreadyDownloaded() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localSong = testSong("s1", "One More Time", "Daft Punk", "vid_1")
        val lookup = FakeSongLookup(listOf(localSong))
        val artist = sampleArtist("art_dp", "Daft Punk")
        val service = FakeYouTubeMusicService(profileResult = Result.success(artist))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadArtist("art_dp").join()

        vm.downloadAllSongs()

        val batch = manager.batches.value.find { it.id == "artist_art_dp" }
        assertNotNull(batch)
        // 1 was skipped because it was already in lookup, so only 2 enqueued
        assertEquals(2, batch?.totalCount)
        testScope.cancel()
    }

    @Test
    fun testDownloadAllSongs_AllTracksAlreadyDownloaded_CompletesImmediately() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val songs = listOf(
            testSong("1", "T1", "Daft Punk", "vid_1"),
            testSong("2", "T2", "Daft Punk", "vid_2"),
            testSong("3", "T3", "Daft Punk", "vid_3"),
        )
        val lookup = FakeSongLookup(songs)
        val artist = sampleArtist("art_dp", "Daft Punk")
        val service = FakeYouTubeMusicService(profileResult = Result.success(artist))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadArtist("art_dp").join()

        vm.downloadAllSongs()

        val batch = manager.batches.value.find { it.id == "artist_art_dp" }
        assertNotNull(batch)
        assertTrue(batch?.isCompleted == true)
        assertEquals(100f, batch?.progress)
        testScope.cancel()
    }

    @Test
    fun testDownloadTrack_EnqueuesSingleTrackBatch() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val artist = sampleArtist()
        val service = FakeYouTubeMusicService(profileResult = Result.success(artist))
        val lookup = FakeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadArtist("art_123").join()

        val track = artist.topSongs[0]
        vm.downloadTrack(track)

        val task = manager.tasks.value.find { it.videoId == "vid_1" }
        assertNotNull(task)
        assertEquals("One More Time", task?.title)
        assertEquals("track_vid_1", task?.batchId)
        testScope.cancel()
    }

    @Test
    fun testHeroDownloadState_CompletedWhenAllSongsPresentInLibrary() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val songs = listOf(
            testSong("1", "T1", "Daft Punk", "vid_1"),
            testSong("2", "T2", "Daft Punk", "vid_2"),
            testSong("3", "T3", "Daft Punk", "vid_3"),
        )
        val lookup = FakeSongLookup(songs)
        val artist = sampleArtist("art_dp", "Daft Punk")
        val service = FakeYouTubeMusicService(profileResult = Result.success(artist))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadArtist("art_dp").join()

        // Since all songs are already in lookup, heroDownloadState should transition to Completed
        val heroState = vm.uiState.first { it.heroDownloadState == HeroDownloadState.Completed }.heroDownloadState
        assertEquals(HeroDownloadState.Completed, heroState)
        testScope.cancel()
    }

    @Test
    fun testPlaybackHelpers() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val song1 = testSong("1", "One More Time", "Daft Punk", "vid_1")
        val lookup = FakeSongLookup(listOf(song1))
        val artist = sampleArtist()
        val service = FakeYouTubeMusicService(profileResult = Result.success(artist))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = ArtistViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadArtist("art_123").join()

        val downloaded = vm.getDownloadedSongsForArtist()
        assertEquals(1, downloaded.size)
        assertEquals("One More Time", downloaded.first().title)

        val foundSong = vm.getSongForTrack("vid_1")
        assertNotNull(foundSong)
        assertEquals("One More Time", foundSong?.title)

        val notFoundSong = vm.getSongForTrack("vid_not_found")
        assertNull(notFoundSong)
        testScope.cancel()
    }
}
