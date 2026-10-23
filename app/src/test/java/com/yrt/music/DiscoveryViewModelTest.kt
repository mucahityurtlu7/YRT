package com.yrt.music

import com.yrt.music.data.downloader.YtDlpDownloadManager
import com.yrt.music.data.model.DiscoveryPage
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.DiscoveryService
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.ui.discover.DiscoveryViewModel
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
class DiscoveryViewModelTest {
    private class Service : DiscoveryService {
        data class Request(val id: String, val params: String?, val force: Boolean)
        val requests = mutableListOf<Request>()
        var response: Result<DiscoveryPage>? = null
        var hold: CompletableDeferred<Unit>? = null
        override suspend fun getPage(browseId: String, params: String?, forceRefresh: Boolean): Result<DiscoveryPage> {
            requests += Request(browseId, params, forceRefresh)
            hold?.await()
            return response ?: Result.success(DiscoveryPage(browseId, emptyList()))
        }
    }

    private class Lookup : LocalSongLookup {
        override val songs = MutableStateFlow(emptyList<Song>())
        override fun hasVideoId(videoId: String) = false
        override fun getSongByVideoId(videoId: String): Song? = null
        override suspend fun refresh() = Unit
    }

    private class Harness(dispatcher: TestDispatcher) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = Service()
        val lookup = Lookup()
        val manager = YtDlpDownloadManager(null, lookup, scope) { _, _, _, _ -> awaitCancellation() }
        val vm = DiscoveryViewModel(service, manager, lookup, dispatcher, scope)
        fun close() = scope.cancel()
    }

    @Test fun initialPageLoadsFromYouTubeMusicAndIsNotFetchedOnEveryComposition() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        try {
            h.vm.ensureLoaded().join()
            h.vm.ensureLoaded().join()
            assertEquals(listOf(Service.Request("FEmusic_explore", null, false)), h.service.requests)
            assertFalse(h.vm.uiState.value.isLoading)
            assertNotNull(h.vm.uiState.value.page)
            assertFalse(h.vm.uiState.value.canGoBack)
        } finally { h.close() }
    }

    @Test fun categoryNavigationRetainsEndpointParamsAndBackRestoresParent() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        try {
            h.vm.ensureLoaded().join()
            h.vm.openPage("FEmusic_moods_and_genres", "mood-filter", "Ruh halleri").join()
            h.vm.openPage("VL_playlist", "playlist-filter", "Liste").join()
            assertTrue(h.vm.uiState.value.canGoBack)
            h.vm.goBack().join()
            assertEquals("FEmusic_moods_and_genres", h.vm.uiState.value.page!!.title)
            assertEquals("mood-filter", h.service.requests.last().params)
            h.vm.goBack().join()
            assertEquals("FEmusic_explore", h.vm.uiState.value.page!!.title)
            assertFalse(h.vm.uiState.value.canGoBack)
        } finally { h.close() }
    }

    @Test fun refreshRetainsVisiblePageAndForcesNewNetworkRequest() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        try {
            h.vm.ensureLoaded().join()
            val original = h.vm.uiState.value.page
            h.service.hold = CompletableDeferred()
            h.vm.refresh()
            runCurrent()
            assertEquals(original, h.vm.uiState.value.page)
            assertTrue(h.vm.uiState.value.isLoading)
            h.service.hold!!.complete(Unit)
            advanceUntilIdle()
            assertTrue(h.service.requests.last().force)
            assertFalse(h.vm.uiState.value.isLoading)
        } finally { h.close() }
    }

    @Test fun networkFailureDoesNotPretendThereAreRecommendationsAndRetryRecovers() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        try {
            h.service.response = Result.failure(IOException("offline"))
            h.vm.ensureLoaded().join()
            assertNull(h.vm.uiState.value.page)
            assertNotNull(h.vm.uiState.value.errorMessage)
            assertFalse(h.vm.uiState.value.isLoading)
            h.service.response = null
            h.vm.retry().join()
            assertNotNull(h.vm.uiState.value.page)
            assertNull(h.vm.uiState.value.errorMessage)
            assertTrue(h.service.requests.last().force)
        } finally { h.close() }
    }

    @Test fun navigatingBackDuringChildLoadCannotOverwriteParentWithLateResults() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        try {
            h.vm.ensureLoaded().join()
            h.service.hold = CompletableDeferred()
            h.vm.openPage("VL_playlist", title = "Liste")
            runCurrent()
            h.service.hold = null
            h.vm.goBack().join()
            advanceUntilIdle()
            assertEquals("FEmusic_explore", h.vm.uiState.value.page!!.title)
            assertFalse(h.vm.uiState.value.canGoBack)
        } finally { h.close() }
    }

    @Test fun discoveryTrackUsesSharedDownloadQueueAndSkipsDuplicateRequests() = runTest {
        val h = Harness(StandardTestDispatcher(testScheduler))
        try {
            h.vm.downloadTrack(RemoteTrack("video", "Yeni şarkı", "Sanatçı"))
            h.vm.downloadTrack(RemoteTrack("video", "Yeni şarkı", "Sanatçı"))
            assertEquals(1, h.manager.tasks.value.size)
            assertEquals("video", h.manager.tasks.value.single().videoId)
            assertEquals("Sanatçı", h.manager.tasks.value.single().artist)
            h.vm.downloadTrack(RemoteTrack("", "Geçersiz"))
            assertEquals(1, h.manager.tasks.value.size)
        } finally { h.close() }
    }
}
