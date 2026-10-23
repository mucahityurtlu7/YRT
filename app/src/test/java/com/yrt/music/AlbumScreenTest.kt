package com.yrt.music

import com.yrt.music.data.downloader.YtDlpDownloadManager
import com.yrt.music.data.model.AlbumDetails
import com.yrt.music.data.model.AlbumSearchResult
import com.yrt.music.data.model.ArtistProfile
import com.yrt.music.data.model.ArtistSearchResult
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.data.repository.YouTubeMusicService
import com.yrt.music.navigation.Screen
import com.yrt.music.ui.album.AlbumUiState
import com.yrt.music.ui.album.AlbumViewModel
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
class AlbumScreenTest {

    private class FakeYouTubeMusicService(
        var albumResult: Result<AlbumDetails>? = null,
    ) : YouTubeMusicService {
        var albumCallCount = 0
        var lastBrowseId: String? = null

        override suspend fun getAlbumDetails(browseId: String): Result<AlbumDetails> {
            albumCallCount++
            lastBrowseId = browseId
            return albumResult ?: Result.failure(IOException("Album not found"))
        }

        override suspend fun searchArtists(query: String): Result<List<ArtistSearchResult>> = Result.success(emptyList())
        override suspend fun searchAlbums(query: String): Result<List<AlbumSearchResult>> = Result.success(emptyList())
        override suspend fun getArtistProfile(browseId: String): Result<ArtistProfile> = Result.failure(UnsupportedOperationException())
    }

    private class FakeSongLookup(
        initialSongs: List<Song> = emptyList(),
    ) : LocalSongLookup {
        private val _songs = MutableStateFlow(initialSongs)
        override val songs: StateFlow<List<Song>> = _songs.asStateFlow()
        override fun hasVideoId(videoId: String): Boolean = _songs.value.any { it.videoId == videoId }
        override fun getSongByVideoId(videoId: String): Song? = _songs.value.find { it.videoId == videoId }
        override suspend fun refresh() {}
    }

    private fun sampleAlbum(
        browseId: String = "alb_ram",
        title: String = "Random Access Memories",
        artist: String = "Daft Punk",
        artistBrowseId: String? = "art_dp",
        tracks: List<RemoteTrack> = listOf(
            RemoteTrack("vid_give_life", "Give Life Back to Music", "Daft Punk", "RAM", 274000, "4:34", index = 1),
            RemoteTrack("vid_game_love", "The Game of Love", "Daft Punk", "RAM", 321000, "5:21", index = 2),
            RemoteTrack("vid_moroder", "Giorgio by Moroder", "Daft Punk", "RAM", 544000, "9:04", index = 3),
            RemoteTrack("vid_get_lucky", "Get Lucky", "Daft Punk", "RAM", 367000, "6:07", index = 4),
        ),
    ) = AlbumDetails(
        browseId = browseId,
        title = title,
        artist = artist,
        artistBrowseId = artistBrowseId,
        year = "2013",
        artworkUrl = "https://example.com/ram.jpg",
        trackCount = tracks.size,
        durationText = "1 saat 14 dakika",
        tracks = tracks,
    )

    @Test
    fun testInitialState() {
        val service = FakeYouTubeMusicService()
        val lookup = FakeSongLookup()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup)
        assertEquals(AlbumUiState.Loading, vm.uiState.value)
        assertEquals(HeroDownloadState.Idle, vm.heroDownloadState.value)
        testScope.cancel()
    }

    @Test
    fun testLoadAlbum_Success_PopulatesDetailsAndTracks() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = sampleAlbum()
        val service = FakeYouTubeMusicService(albumResult = Result.success(album))
        val lookup = FakeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(
            repository = service,
            downloadManager = manager,
            songRepository = lookup,
            ioDispatcher = Dispatchers.Default,
            scopeOverride = testScope,
        )

        vm.loadAlbum("alb_ram").join()

        assertEquals(1, service.albumCallCount)
        assertEquals("alb_ram", service.lastBrowseId)

        val state = vm.uiState.value
        assertTrue(state is AlbumUiState.Success)
        val successState = state as AlbumUiState.Success
        assertEquals("Random Access Memories", successState.album.title)
        assertEquals("Daft Punk", successState.album.artist)
        assertEquals("art_dp", successState.album.artistBrowseId)
        assertEquals(4, successState.album.tracks.size)
        testScope.cancel()
    }

    @Test
    fun testLoadAlbum_RedundantCall_SkipsNetwork() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = sampleAlbum()
        val service = FakeYouTubeMusicService(albumResult = Result.success(album))
        val lookup = FakeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)

        vm.loadAlbum("alb_ram").join()
        assertEquals(1, service.albumCallCount)

        // Repeat call with forceRefresh = false
        vm.loadAlbum("alb_ram", forceRefresh = false).join()
        assertEquals(1, service.albumCallCount) // skipped!

        // With forceRefresh = true
        vm.loadAlbum("alb_ram", forceRefresh = true).join()
        assertEquals(2, service.albumCallCount)
        testScope.cancel()
    }

    @Test
    fun testLoadAlbum_BlankId_SetsError() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = FakeYouTubeMusicService()
        val lookup = FakeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)

        vm.loadAlbum("   ").join()

        assertEquals(0, service.albumCallCount)
        assertTrue(vm.uiState.value is AlbumUiState.Error)
        val errorState = vm.uiState.value as AlbumUiState.Error
        assertEquals("Geçersiz albüm kimliği", errorState.message)
        testScope.cancel()
    }

    @Test
    fun testLoadAlbum_NetworkFailure_SetsErrorState() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = FakeYouTubeMusicService(albumResult = Result.failure(IOException("Server error 500")))
        val lookup = FakeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)

        vm.loadAlbum("alb_fail").join()

        assertTrue(vm.uiState.value is AlbumUiState.Error)
        val errorState = vm.uiState.value as AlbumUiState.Error
        assertEquals("Server error 500", errorState.message)
        testScope.cancel()
    }

    @Test
    fun testDownloadAlbum_EnqueuesBatchWithExactBatchIdAndTitle() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = sampleAlbum("alb_ram", "Random Access Memories")
        val service = FakeYouTubeMusicService(albumResult = Result.success(album))
        val lookup = FakeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_ram").join()

        val result = vm.downloadAlbum()

        assertNotNull(result)
        assertEquals("album_alb_ram", result?.batchId)
        assertEquals(4, result?.totalRequested)
        assertEquals(4, result?.enqueuedCount)
        assertEquals(0, result?.alreadyDownloadedCount)

        val batch = manager.batches.value.find { it.id == "album_alb_ram" }
        assertNotNull(batch)
        assertEquals("Albüm: Random Access Memories", batch?.title)
        assertEquals(4, batch?.totalCount)
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
    fun testDownloadAlbum_Deduplication_SkipsAlreadyDownloadedTracks() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localSong = testSong("s_lucky", "Get Lucky", "Daft Punk", "vid_get_lucky")
        val lookup = FakeSongLookup(listOf(localSong))
        val album = sampleAlbum()
        val service = FakeYouTubeMusicService(albumResult = Result.success(album))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_ram").join()

        val result = vm.downloadAlbum()

        assertNotNull(result)
        assertEquals(4, result?.totalRequested)
        assertEquals(3, result?.enqueuedCount)
        assertEquals(1, result?.alreadyDownloadedCount) // 1 skipped!
        testScope.cancel()
    }

    @Test
    fun testDownloadAlbum_AllTracksAlreadyDownloaded_CompletesImmediately() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = sampleAlbum()
        val localSongs = album.tracks.map {
            testSong("s_${it.videoId}", it.title, it.artist ?: "Daft Punk", it.videoId)
        }
        val lookup = FakeSongLookup(localSongs)
        val service = FakeYouTubeMusicService(albumResult = Result.success(album))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_ram").join()

        val result = vm.downloadAlbum()

        assertNotNull(result)
        assertEquals(0, result?.enqueuedCount)
        assertEquals(4, result?.alreadyDownloadedCount)

        val batch = manager.batches.value.find { it.id == "album_alb_ram" }
        assertNotNull(batch)
        assertTrue(batch?.isCompleted == true)
        assertEquals(100f, batch?.progress)
        testScope.cancel()
    }

    @Test
    fun testDownloadTrack_EnqueuesSingleTrackBatch() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = sampleAlbum()
        val service = FakeYouTubeMusicService(albumResult = Result.success(album))
        val lookup = FakeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_ram").join()

        val track = album.tracks[0]
        vm.downloadTrack(track)

        val task = manager.tasks.value.find { it.videoId == "vid_give_life" }
        assertNotNull(task)
        assertEquals("Give Life Back to Music", task?.title)
        assertEquals("track_vid_give_life", task?.batchId)
        testScope.cancel()
    }

    @Test
    fun testHeroDownloadState_CompletedWhenAllTracksPresentInLibrary() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val album = sampleAlbum()
        val localSongs = album.tracks.map {
            testSong("s_${it.videoId}", it.title, it.artist ?: "Daft Punk", it.videoId)
        }
        val lookup = FakeSongLookup(localSongs)
        val service = FakeYouTubeMusicService(albumResult = Result.success(album))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_ram").join()

        val heroState = vm.heroDownloadState.first { it == HeroDownloadState.Completed }
        assertEquals(HeroDownloadState.Completed, heroState)
        testScope.cancel()
    }

    @Test
    fun testOfflinePlaybackHelpers() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val localSong = testSong("s1", "Get Lucky", "Daft Punk", "vid_get_lucky")
        val lookup = FakeSongLookup(listOf(localSong))
        val album = sampleAlbum()
        val service = FakeYouTubeMusicService(albumResult = Result.success(album))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val vm = AlbumViewModel(service, manager, lookup, Dispatchers.Default, testScope)
        vm.loadAlbum("alb_ram").join()

        val found = vm.findLocalSong("vid_get_lucky")
        assertNotNull(found)
        assertEquals("Get Lucky", found?.title)

        val notFound = vm.findLocalSong("vid_non_existent")
        assertNull(notFound)

        val downloadedAlbumSongs = vm.getDownloadedAlbumSongs()
        assertEquals(1, downloadedAlbumSongs.size)
        assertEquals("Get Lucky", downloadedAlbumSongs[0].title)
        testScope.cancel()
    }

    @Test
    fun testNavigationRoutesContract() {
        assertEquals("album/alb_ram", Screen.Album.createRoute("alb_ram"))
        assertEquals("artist/art_dp", Screen.Artist.createRoute("art_dp"))
    }
}
