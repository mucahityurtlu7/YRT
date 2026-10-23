package com.yrt.music

import com.yrt.music.data.model.DiscoveryPage
import com.yrt.music.data.repository.DiscoveryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DiscoveryRepositoryTest {
    @Test
    fun categoryParamsAreDistinctCacheKeysAndRefreshRequestsCurrentContent() = runBlocking {
        var calls = 0
        var now = 1_000L
        val repo = DiscoveryRepository(Dispatchers.Unconfined, { _, params ->
            calls++
            DiscoveryPage("$params-$calls", emptyList())
        }, { now })
        assertEquals("workout-1", repo.getPage("category", "workout").getOrThrow().title)
        assertEquals("sleep-2", repo.getPage("category", "sleep").getOrThrow().title)
        assertEquals("workout-1", repo.getPage("category", "workout").getOrThrow().title)
        assertEquals(2, calls)
        assertEquals("workout-3", repo.getPage("category", "workout", forceRefresh = true).getOrThrow().title)
        now += 5 * 60 * 1_000
        assertEquals("workout-4", repo.getPage("category", "workout").getOrThrow().title)
        repo.clearCache()
        repo.getPage("category", "workout").getOrThrow()
        assertEquals(5, calls)
    }

    @Test
    fun incompleteNetworkResultsFailAndDoNotReplaceCachedCompletePage() = runBlocking {
        var fail = false
        val repo = DiscoveryRepository(Dispatchers.Unconfined, { _, _ ->
            if (fail) throw IOException("missing continuation")
            DiscoveryPage("Complete", emptyList())
        })
        assertEquals("Complete", repo.getPage().getOrThrow().title)
        fail = true
        assertTrue(repo.getPage(forceRefresh = true).isFailure)
        assertEquals("Complete", repo.getPage().getOrThrow().title)
        assertTrue(repo.getPage(" ").isFailure)
    }

    @Test
    fun cancellationIsNotConvertedToAnErrorPage() = runBlocking {
        val repo = DiscoveryRepository(Dispatchers.Unconfined, { _, _ -> throw CancellationException("cancel") })
        try {
            repo.getPage()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
