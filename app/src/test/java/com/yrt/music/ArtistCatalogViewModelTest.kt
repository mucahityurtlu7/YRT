package com.yrt.music

import com.yrt.music.data.downloader.YtDlpDownloadManager
import com.yrt.music.data.model.AlbumDetails
import com.yrt.music.data.model.AlbumSearchResult
import com.yrt.music.data.model.ArtistProfile
import com.yrt.music.data.model.ArtistSearchResult
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.data.repository.YouTubeMusicService
import com.yrt.music.ui.artist.ArtistViewModel
import com.yrt.music.ui.components.HeroDownloadState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistCatalogViewModelTest {
    private val preview = ArtistProfile(
        browseId = "artist", name = "Sanatçı",
        topSongs = (1..5).map { RemoteTrack("v$it", "Parça $it") },
        isCatalogComplete = false,
    )
    private val complete = preview.copy(
        allSongs = (1..18).map { RemoteTrack("v$it", "Parça $it") },
        isCatalogComplete = true,
    )

    private class Service(var profile: ArtistProfile, var catalog: ArtistProfile) : YouTubeMusicService {
        var catalogCalls = 0
        var catalogResult: Result<ArtistProfile>? = null
        var hold: CompletableDeferred<Unit>? = null
        override suspend fun getArtistProfile(browseId: String) = Result.success(profile)
        override suspend fun getArtistCatalog(browseId: String): Result<ArtistProfile> {
            catalogCalls++
            hold?.await()
            return catalogResult ?: Result.success(catalog)
        }
        override suspend fun searchArtists(query: String) = Result.success(emptyList<ArtistSearchResult>())
        override suspend fun searchAlbums(query: String) = Result.success(emptyList<AlbumSearchResult>())
        override suspend fun getAlbumDetails(browseId: String) = Result.failure<AlbumDetails>(UnsupportedOperationException())
    }

    private class Lookup(ids: List<String>) : LocalSongLookup {
        override val songs = MutableStateFlow(ids.map { id ->
            Song(id, id, "Sanatçı", 1000, File("$id.m4a"), null, 0, videoId = id)
        })
        override fun hasVideoId(videoId: String) = songs.value.any { it.videoId == videoId }
        override fun getSongByVideoId(videoId: String) = songs.value.find { it.videoId == videoId }
        override suspend fun refresh() = Unit
    }

    private inner class Harness(dispatcher: TestDispatcher, localIds: List<String> = emptyList()) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = Service(preview, complete)
        val lookup = Lookup(localIds)
        val manager = YtDlpDownloadManager(null, lookup, scope) { _, _, _, _ -> awaitCancellation() }
        val vm = ArtistViewModel(service, manager, lookup, dispatcher, scope)
        fun close() = scope.cancel()
    }

    @Test fun downloadAllFetchesCatalogAndQueuesSongsBeyondFiveSongPreview() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        try {
            h.vm.loadArtist("artist").join()
            h.vm.downloadAllSongs()
            advanceUntilIdle()
            assertEquals(1, h.service.catalogCalls)
            assertEquals((1..18).map { "v$it" }.toSet(), h.manager.tasks.value.map { it.videoId }.toSet())
            assertEquals(18, h.vm.uiState.value.allSongs.size)
            assertEquals(5, h.vm.uiState.value.artist!!.topSongs.size)
            assertFalse(h.vm.uiState.value.isDownloadingAllSongs)
        } finally { h.close() }
    }

    @Test fun downloadedPreviewDoesNotMarkWholeArtistComplete() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler), (1..5).map { "v$it" })
        try {
            h.vm.loadArtist("artist").join()
            runCurrent()
            assertEquals(HeroDownloadState.Idle, h.vm.uiState.value.heroDownloadState)
            h.vm.downloadAllSongs()
            advanceUntilIdle()
            assertEquals((6..18).map { "v$it" }.toSet(), h.manager.tasks.value.map { it.videoId }.toSet())
            assertTrue(h.vm.uiState.value.heroDownloadState is HeroDownloadState.InProgress)
        } finally { h.close() }
    }

    @Test fun moreAndRepeatedDownloadShareOneCatalogRequest() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        try {
            h.service.hold = CompletableDeferred()
            h.vm.loadArtist("artist").join()
            val more = h.vm.loadAllSongs()
            h.vm.downloadAllSongs()
            h.vm.downloadAllSongs()
            runCurrent()
            assertEquals(1, h.service.catalogCalls)
            assertTrue(h.vm.uiState.value.isLoadingAllSongs)
            assertTrue(h.vm.uiState.value.isDownloadingAllSongs)
            assertTrue(h.manager.tasks.value.isEmpty())
            h.service.hold!!.complete(Unit)
            more.join()
            advanceUntilIdle()
            assertEquals(18, h.manager.tasks.value.size)
            h.vm.downloadAllSongs()
            assertEquals(18, h.manager.tasks.value.size)
            assertEquals(18, h.manager.batches.value.single().totalCount)
        } finally { h.close() }
    }

    @Test fun catalogFailureDoesNotQueuePreviewAndRetryFetchesFullCatalog() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        try {
            h.service.catalogResult = Result.failure(IOException("Bağlantı kesildi"))
            h.vm.loadArtist("artist").join()
            h.vm.downloadAllSongs()
            advanceUntilIdle()
            assertEquals("Bağlantı kesildi", h.vm.uiState.value.allSongsError)
            assertTrue(h.manager.tasks.value.isEmpty())
            assertFalse(h.vm.uiState.value.isDownloadingAllSongs)
            assertFalse(h.vm.uiState.value.isLoadingAllSongs)
            h.service.catalogResult = null
            h.vm.downloadAllSongs()
            advanceUntilIdle()
            assertEquals(2, h.service.catalogCalls)
            assertEquals(18, h.manager.tasks.value.size)
            assertNull(h.vm.uiState.value.allSongsError)
        } finally { h.close() }
    }

    @Test fun incompleteCatalogResultIsRejectedAndCanBeRetried() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        try {
            h.service.catalogResult = Result.success(preview)
            h.vm.loadArtist("artist").join()
            h.vm.downloadAllSongs()
            advanceUntilIdle()
            assertNotNull(h.vm.uiState.value.allSongsError)
            assertTrue(h.vm.uiState.value.allSongs.isEmpty())
            assertTrue(h.manager.tasks.value.isEmpty())
            h.service.catalogResult = null
            h.vm.loadAllSongs().join()
            assertEquals(18, h.vm.uiState.value.allSongs.size)
        } finally { h.close() }
    }

    @Test fun morePreservesPopularityOrderAndPlaybackUsesFullCatalog() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler), listOf("v18", "v3", "v9"))
        try {
            val ranked = listOf(18, 3, 9) + (1..18).filter { it !in listOf(18, 3, 9) }
            h.service.catalog = complete.copy(allSongs = ranked.map { RemoteTrack("v$it", "Parça $it") })
            h.vm.loadArtist("artist").join()
            h.vm.loadAllSongs().join()
            assertEquals(ranked.map { "v$it" }, h.vm.uiState.value.allSongs.map { it.videoId })
            assertEquals(listOf("v18", "v3", "v9"), h.vm.getDownloadedSongsForArtist().map { it.videoId })
            h.vm.loadAllSongs().join()
            assertEquals(1, h.service.catalogCalls)
        } finally { h.close() }
    }

    @Test fun changingArtistCancelsPendingCatalogAndNeverQueuesPreviousArtist() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        try {
            h.service.hold = CompletableDeferred()
            h.vm.loadArtist("artist").join()
            h.vm.downloadAllSongs()
            runCurrent()
            h.service.profile = ArtistProfile("other", "Başka Sanatçı", topSongs = listOf(RemoteTrack("new", "Yeni")))
            h.vm.loadArtist("other").join()
            h.service.hold!!.complete(Unit)
            advanceUntilIdle()
            assertEquals("other", h.vm.uiState.value.artist!!.browseId)
            assertEquals(listOf("new"), h.vm.uiState.value.allSongs.map { it.videoId })
            assertTrue(h.manager.tasks.value.isEmpty())
            assertFalse(h.vm.uiState.value.isDownloadingAllSongs)
        } finally { h.close() }
    }
}
