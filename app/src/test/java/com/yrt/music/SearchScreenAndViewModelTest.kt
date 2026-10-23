package com.yrt.music

import com.yrt.music.data.model.AlbumSearchResult
import com.yrt.music.data.model.ArtistProfile
import com.yrt.music.data.model.ArtistSearchResult
import com.yrt.music.data.model.AlbumDetails
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.data.repository.YouTubeMusicService
import com.yrt.music.navigation.Screen
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SearchScreenAndViewModelTest {

    private class FakeYouTubeMusicService(
        var artistResults: List<ArtistSearchResult> = emptyList(),
        var albumResults: List<AlbumSearchResult> = emptyList(),
        var shouldFailArtists: Boolean = false,
        var shouldFailAlbums: Boolean = false,
    ) : YouTubeMusicService {
        var searchArtistsQueryCallCount = 0
        var searchAlbumsQueryCallCount = 0
        var lastArtistQuery: String? = null
        var lastAlbumQuery: String? = null

        override suspend fun searchArtists(query: String): Result<List<ArtistSearchResult>> {
            searchArtistsQueryCallCount++
            lastArtistQuery = query
            if (shouldFailArtists) return Result.failure(IOException("Network error fetching artists"))
            return Result.success(artistResults)
        }

        override suspend fun searchAlbums(query: String): Result<List<AlbumSearchResult>> {
            searchAlbumsQueryCallCount++
            lastAlbumQuery = query
            if (shouldFailAlbums) return Result.failure(IOException("Network error fetching albums"))
            return Result.success(albumResults)
        }

        override suspend fun getArtistProfile(browseId: String): Result<ArtistProfile> {
            return Result.failure(UnsupportedOperationException())
        }

        override suspend fun getAlbumDetails(browseId: String): Result<AlbumDetails> {
            return Result.failure(UnsupportedOperationException())
        }
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

    @Test
    fun testInitialUiState() {
        val service = FakeYouTubeMusicService()
        val lookup = FakeSongLookup()
        val vm = SearchViewModel(service, lookup)

        val state = vm.uiState.value
        assertEquals("", state.query)
        assertEquals(SearchFilter.ALL, state.filter)
        assertFalse(state.isLoading)
        assertTrue(state.artists.isEmpty())
        assertTrue(state.albums.isEmpty())
        assertTrue(state.localTracks.isEmpty())
        assertNull(state.errorMessage)
        assertFalse(state.hasSearched)
        assertFalse(state.isEmptyResult)
    }

    @Test
    fun testDebounceBehavior_RapidTypingTriggersOnlySingleNetworkCall() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val service = FakeYouTubeMusicService(
            artistResults = listOf(ArtistSearchResult("art1", "Daft Punk")),
            albumResults = listOf(AlbumSearchResult("alb1", "Discovery", "Daft Punk")),
        )
        val lookup = FakeSongLookup()
        val vm = SearchViewModel(
            repository = service,
            localSongLookup = lookup,
            ioDispatcher = testDispatcher,
            scopeOverride = backgroundScope,
        )

        // Rapid typing within debounce window
        vm.onQueryChange("D")
        advanceTimeBy(100)
        vm.onQueryChange("Daf")
        advanceTimeBy(100)
        vm.onQueryChange("Daft")
        advanceTimeBy(100)
        vm.onQueryChange("Daft Punk")

        // Before debounce expires (300ms)
        assertEquals(0, service.searchArtistsQueryCallCount)
        assertEquals(0, service.searchAlbumsQueryCallCount)

        // Advance debounce time
        advanceTimeBy(350)
        advanceUntilIdle()

        // Exactly one call should have been dispatched for the final query
        assertEquals(1, service.searchArtistsQueryCallCount)
        assertEquals(1, service.searchAlbumsQueryCallCount)
        assertEquals("Daft Punk", service.lastArtistQuery)
        assertEquals("Daft Punk", service.lastAlbumQuery)

        val state = vm.uiState.value
        assertEquals("Daft Punk", state.query)
        assertFalse(state.isLoading)
        assertEquals(1, state.artists.size)
        assertEquals(1, state.albums.size)
        assertTrue(state.hasSearched)
    }

    @Test
    fun testBlankQuery_ClearsStateImmediatelyWithoutNetworkCalls() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val service = FakeYouTubeMusicService()
        val lookup = FakeSongLookup()
        val vm = SearchViewModel(service, lookup, testDispatcher, backgroundScope)

        vm.onQueryChange("   ")
        advanceUntilIdle()

        assertEquals(0, service.searchArtistsQueryCallCount)
        assertEquals(0, service.searchAlbumsQueryCallCount)
        assertFalse(vm.uiState.value.hasSearched)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun testClearQuery_ResetsSearchState() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val service = FakeYouTubeMusicService(
            artistResults = listOf(ArtistSearchResult("a1", "Artist 1")),
        )
        val lookup = FakeSongLookup()
        val vm = SearchViewModel(service, lookup, testDispatcher, backgroundScope)

        vm.onQueryChange("Artist")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.artists.size)

        vm.clearQuery()

        val state = vm.uiState.value
        assertEquals("", state.query)
        assertFalse(state.isLoading)
        assertTrue(state.artists.isEmpty())
        assertTrue(state.albums.isEmpty())
        assertFalse(state.hasSearched)
    }

    @Test
    fun testFilterSelection_DifferentialQueryDispatch() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val service = FakeYouTubeMusicService(
            artistResults = listOf(ArtistSearchResult("a1", "Queen")),
            albumResults = listOf(AlbumSearchResult("alb1", "A Night at the Opera", "Queen")),
        )
        val lookup = FakeSongLookup()
        val vm = SearchViewModel(service, lookup, testDispatcher, backgroundScope)

        // 1. Filter ALL: Dispatches both
        vm.onQueryChange("Queen")
        advanceTimeBy(350)
        advanceUntilIdle()
        assertEquals(1, service.searchArtistsQueryCallCount)
        assertEquals(1, service.searchAlbumsQueryCallCount)

        // 2. Filter ARTISTS: Only dispatches artists
        vm.onFilterSelected(SearchFilter.ARTISTS)?.join()
        advanceUntilIdle()
        assertEquals(2, service.searchArtistsQueryCallCount)
        assertEquals(1, service.searchAlbumsQueryCallCount) // unchanged

        // 3. Filter ALBUMS: Only dispatches albums
        vm.onFilterSelected(SearchFilter.ALBUMS)?.join()
        advanceUntilIdle()
        assertEquals(2, service.searchArtistsQueryCallCount) // unchanged
        assertEquals(2, service.searchAlbumsQueryCallCount)
    }

    private fun testSong(id: String, title: String, artist: String, album: String) = Song(
        id = id,
        title = title,
        artist = artist,
        durationMs = 200000L,
        file = java.io.File("/music/$id.m4a"),
        artworkFile = null,
        lastModified = 0L,
        album = album,
    )

    @Test
    fun testLocalTrackMatching_MatchesQueryInAllFilter() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val localSongs = listOf(
            testSong("1", "Get Lucky", "Daft Punk", "RAM"),
            testSong("2", "One More Time", "Daft Punk", "Discovery"),
            testSong("3", "Starboy", "The Weeknd", "Starboy"),
        )
        val service = FakeYouTubeMusicService()
        val lookup = FakeSongLookup(localSongs)
        val vm = SearchViewModel(service, lookup, testDispatcher, backgroundScope)

        vm.onQueryChange("Lucky")
        advanceTimeBy(350)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.localTracks.size)
        assertEquals("Get Lucky", state.localTracks.first().title)

        // Matching on artist
        vm.onQueryChange("Daft")
        advanceTimeBy(350)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.localTracks.size)
    }

    @Test
    fun testErrorHandling_PopulatesErrorMessageAndEnablesRetry() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val service = FakeYouTubeMusicService(shouldFailArtists = true, shouldFailAlbums = true)
        val lookup = FakeSongLookup()
        val vm = SearchViewModel(service, lookup, testDispatcher, backgroundScope)

        vm.onQueryChange("ErrorTest")
        advanceTimeBy(350)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)

        // Fix failure and retry
        service.shouldFailArtists = false
        service.shouldFailAlbums = false
        service.artistResults = listOf(ArtistSearchResult("a_fixed", "Fixed Artist"))

        vm.retry()?.join()
        advanceUntilIdle()

        val retryState = vm.uiState.value
        assertFalse(retryState.isLoading)
        assertNull(retryState.errorMessage)
        assertEquals(1, retryState.artists.size)
    }

    @Test
    fun testEmptyResultState_EvaluatesCorrectly() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val service = FakeYouTubeMusicService(artistResults = emptyList(), albumResults = emptyList())
        val lookup = FakeSongLookup(emptyList())
        val vm = SearchViewModel(service, lookup, testDispatcher, backgroundScope)

        vm.onQueryChange("NonExistentArtistXYZ123")
        advanceTimeBy(350)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.hasSearched)
        assertFalse(state.isLoading)
        assertTrue(state.isEmptyResult)
    }

    @Test
    fun testNavigationRoutesContract() {
        assertEquals("artist/browse_123", Screen.Artist.createRoute("browse_123"))
        assertEquals("album/album_456", Screen.Album.createRoute("album_456"))
    }
}
