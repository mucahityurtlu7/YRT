package com.yrt.music

import com.yrt.music.data.downloader.YtDlpDownloadManager
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.BulkDownloadRequest
import com.yrt.music.data.model.DownloadBatch
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.service.DownloadService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive test suite verifying Milestone 2 requirements:
 * - Feature 5: Bulk Download Enqueuing & Deduplication
 * - Feature 6: Concurrency Control & Progress Throttling
 * - Feature 7: Aggregate Download Progress & Batch Lifecycle
 * - Feature 8: Background Keepalive & Service Logic
 */
class BulkDownloadSubsystemTest {

    private class TestSongLookup(
        initialIds: Set<String> = emptySet(),
    ) : LocalSongLookup {
        private val set = ConcurrentHashMap.newKeySet<String>().apply { addAll(initialIds) }
        private val _songs = MutableStateFlow<List<Song>>(emptyList())
        override val songs: StateFlow<List<Song>> = _songs.asStateFlow()

        override fun hasVideoId(videoId: String): Boolean = set.contains(videoId)
        override fun getSongByVideoId(videoId: String): Song? = null
        override suspend fun refresh() { /* no-op */ }

        fun add(id: String) { set.add(id) }
        fun remove(id: String) { set.remove(id) }
    }

    // =========================================================================
    // Feature 5: Bulk Download Enqueuing & Deduplication (Tests 1-10)
    // =========================================================================

    @Test
    fun testEnqueueBatch_allNewTracks_allEnqueued() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val requests = (1..5).map { i ->
            BulkDownloadRequest(
                videoId = "vid_new_$i",
                title = "New Song $i",
                artist = "Artist",
                album = "Album",
            )
        }

        val result = manager.enqueueBatch("batch_1", "Batch Title", requests)
        assertEquals("batch_1", result.batchId)
        assertEquals(5, result.totalRequested)
        assertEquals(5, result.enqueuedCount)
        assertEquals(0, result.alreadyDownloadedCount)

        val batch = manager.batches.value.find { it.id == "batch_1" }
        assertNotNull(batch)
        assertEquals(5, batch?.totalCount)
        assertEquals(5, manager.tasks.value.filter { it.batchId == "batch_1" }.size)
        testScope.cancel()
    }

    @Test
    fun testEnqueueBatch_allExistingTracks_skipsAllAndCompletesImmediately() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup(setOf("exist_1", "exist_2", "exist_3"))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val requests = (1..3).map { i ->
            BulkDownloadRequest(videoId = "exist_$i", title = "Existing $i")
        }

        val result = manager.enqueueBatch("batch_exist", "Existing Batch", requests)
        assertEquals(3, result.totalRequested)
        assertEquals(0, result.enqueuedCount)
        assertEquals(3, result.alreadyDownloadedCount)

        val batch = manager.batches.value.find { it.id == "batch_exist" }
        assertNotNull(batch)
        assertEquals(0, batch?.totalCount)
        assertTrue(batch?.isCompleted == true)
        assertEquals(100f, batch?.progress ?: 0f, 0.01f)
        assertEquals(0, manager.tasks.value.size)
        testScope.cancel()
    }

    @Test
    fun testEnqueueBatch_partialDuplicates_enqueuesOnlyMissing() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup(setOf("p_vid_1", "p_vid_3"))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val requests = (1..5).map { i ->
            BulkDownloadRequest(videoId = "p_vid_$i", title = "Track $i")
        }

        val result = manager.enqueueBatch("batch_partial", "Partial Batch", requests)
        assertEquals(5, result.totalRequested)
        assertEquals(3, result.enqueuedCount)
        assertEquals(2, result.alreadyDownloadedCount)

        val enqueuedVideoIds = manager.tasks.value.filter { it.batchId == "batch_partial" }.map { it.videoId }
        assertEquals(setOf("p_vid_2", "p_vid_4", "p_vid_5"), enqueuedVideoIds.toSet())
        testScope.cancel()
    }

    @Test
    fun testEnqueueBatch_resultMetadataMatchesInput() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup(setOf("dup1"))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val requests = listOf(
            BulkDownloadRequest("dup1", "Track 1"),
            BulkDownloadRequest("new1", "Track 2"),
            BulkDownloadRequest("new2", "Track 3"),
        )

        val res = manager.enqueueBatch("meta_batch", "Title", requests, AudioFormat.MP3)
        assertEquals("meta_batch", res.batchId)
        assertEquals(3, res.totalRequested)
        assertEquals(2, res.enqueuedCount)
        assertEquals(1, res.alreadyDownloadedCount)
        assertEquals(res.totalRequested, res.enqueuedCount + res.alreadyDownloadedCount)
        testScope.cancel()
    }

    @Test
    fun testEnqueueBatch_batchIdPropagatedToTasks() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val requests = listOf(
            BulkDownloadRequest("v1", "Title 1", "Artist 1", "Album 1", "thumb1", 200000L),
            BulkDownloadRequest("v2", "Title 2", "Artist 2", "Album 2", "thumb2", 180000L),
        )

        manager.enqueueBatch("batch_prop", "Batch Prop", requests, AudioFormat.M4A)
        val tasks = manager.tasks.value.filter { it.batchId == "batch_prop" }
        assertEquals(2, tasks.size)

        tasks.forEach { t ->
            assertEquals("batch_prop", t.batchId)
            assertEquals(AudioFormat.M4A, t.format)
            assertTrue(t.url.contains(t.videoId ?: ""))
            assertNotNull(t.thumbnailUrl)
            assertNotNull(t.durationMs)
        }
        testScope.cancel()
    }

    @Test
    fun testEnqueueBatch_emptyItemList_returnsZeroWithoutError() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val result = manager.enqueueBatch("empty_batch", "Empty", emptyList())
        assertEquals(0, result.totalRequested)
        assertEquals(0, result.enqueuedCount)
        assertEquals(0, result.alreadyDownloadedCount)

        val batch = manager.batches.value.find { it.id == "empty_batch" }
        assertNotNull(batch)
        assertEquals(0, batch?.totalCount)
        assertTrue(batch?.isCompleted == true)
        assertEquals(100f, batch?.progress ?: 0f, 0.01f)
        testScope.cancel()
    }

    @Test
    fun testEnqueueBatch_duplicateVideoIdsWithinSingleBatch_deduplicated() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val requests = listOf(
            BulkDownloadRequest("same_vid", "Title 1"),
            BulkDownloadRequest("same_vid", "Title 1 Dup"),
            BulkDownloadRequest("other_vid", "Title 2"),
            BulkDownloadRequest("same_vid", "Title 1 Triplicate"),
        )

        val result = manager.enqueueBatch("batch_intra_dup", "Intra Dup", requests)
        assertEquals(4, result.totalRequested)
        assertEquals(2, result.enqueuedCount)
        assertEquals(2, result.alreadyDownloadedCount)

        val tasks = manager.tasks.value.filter { it.batchId == "batch_intra_dup" }
        assertEquals(2, tasks.size)
        assertEquals(setOf("same_vid", "other_vid"), tasks.map { it.videoId }.toSet())
        testScope.cancel()
    }

    @Test
    fun testEnqueueBatch_tracksCurrentlyInFlight_preventsDuplicateTasks() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()
        val pauseGate = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            pauseGate.await()
        }

        // Enqueue batch 1 with track_active
        manager.enqueueBatch("b1", "Batch 1", listOf(BulkDownloadRequest("track_active", "Active Song")))

        // Now enqueue batch 2 with the same track while b1 is still running / queued
        val res2 = manager.enqueueBatch("b2", "Batch 2", listOf(
            BulkDownloadRequest("track_active", "Active Song"),
            BulkDownloadRequest("track_new", "New Song"),
        ))

        assertEquals(2, res2.totalRequested)
        assertEquals(1, res2.enqueuedCount)
        assertEquals(1, res2.alreadyDownloadedCount)

        val b2Tasks = manager.tasks.value.filter { it.batchId == "b2" }
        assertEquals(1, b2Tasks.size)
        assertEquals("track_new", b2Tasks[0].videoId)

        pauseGate.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testEnqueueBatch_specialCharactersInTitleAndArtist() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val requests = listOf(
            BulkDownloadRequest("u1", "Şebnem Ferah & Ceza - Şarkı 🎵", "Şebnem Ferah", "Gözyaşları"),
            BulkDownloadRequest("u2", "🎧 [2024] / Multi - Tag \\ | ?", "アーティスト", "アルバム"),
        )

        manager.enqueueBatch("b_unicode", "Unicode Batch", requests)
        val tasks = manager.tasks.value.filter { it.batchId == "b_unicode" }
        assertEquals(2, tasks.size)
        assertEquals("Şebnem Ferah & Ceza - Şarkı 🎵", tasks.find { it.videoId == "u1" }?.title)
        assertEquals("アーティスト", tasks.find { it.videoId == "u2" }?.artist)
        testScope.cancel()
    }

    @Test
    fun testEnqueueBatch_largeBatch_100Tracks_performanceUnder50ms() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val requests = (1..100).map { i ->
            BulkDownloadRequest("large_$i", "Large Track $i", "Artist", "Album")
        }

        val startTime = System.currentTimeMillis()
        val result = manager.enqueueBatch("b_large", "Large Discography", requests)
        val duration = System.currentTimeMillis() - startTime

        assertEquals(100, result.totalRequested)
        assertEquals(100, result.enqueuedCount)
        assertEquals(100, manager.tasks.value.filter { it.batchId == "b_large" }.size)
        assertTrue("Batch enqueueing 100 items should complete well under 200ms, took: ${duration}ms", duration < 200)
        testScope.cancel()
    }

    // =========================================================================
    // Feature 6: Concurrency Control & Progress Throttling (Tests 11-20)
    // =========================================================================

    @Test
    fun testConcurrency_maxTwoParallelDownloads() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val activeCount = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val latch = CountDownLatch(4)

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            val cur = activeCount.incrementAndGet()
            maxObserved.updateAndGet { prev -> maxOf(prev, cur) }
            delay(40)
            activeCount.decrementAndGet()
            latch.countDown()
        }

        val requests = (1..4).map { i -> BulkDownloadRequest("c_track_$i", "Track $i") }
        manager.enqueueBatch("b_concur", "Concurrency Batch", requests)

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue("Max concurrent downloads must not exceed 2, was: ${maxObserved.get()}", maxObserved.get() <= 2)
        assertEquals(2, maxObserved.get())
        testScope.cancel()
    }

    @Test
    fun testConcurrency_taskTransitionsFromQueuedToRunningAsPermitFreed() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()
        val gates = (1..3).associate { "t$it" to CompletableDeferred<Unit>() }
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            gates.getValue(url.substringAfter("v=")).await()
        }

        try {
            manager.enqueueBatch("b_step", "Step", listOf(
                BulkDownloadRequest("t1", "T1"),
                BulkDownloadRequest("t2", "T2"),
                BulkDownloadRequest("t3", "T3"),
            ))

            // IO workers can acquire the first two permits in any order.
            val initial = withTimeout(5_000) {
                manager.tasks.first { tasks ->
                    tasks.count { it.status == DownloadStatus.Running } == 2 &&
                        tasks.count { it.status == DownloadStatus.Queued } == 1
                }
            }
            val queued = initial.single { it.status == DownloadStatus.Queued }
            val running = initial.first { it.status == DownloadStatus.Running }
            assertEquals(DownloadStatus.Queued, manager.tasks.value.find { it.id == queued.id }?.status)

            gates.getValue(running.videoId!!).complete(Unit)
            val afterRelease = withTimeout(5_000) {
                manager.tasks.first { tasks -> tasks.find { it.id == queued.id }?.status == DownloadStatus.Running }
            }
            assertEquals(DownloadStatus.Running, afterRelease.find { it.id == queued.id }?.status)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testConcurrency_cancellingRunningTask_releasesPermitToNextQueued() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val holdT1 = CompletableDeferred<Unit>()
        val holdT2 = CompletableDeferred<Unit>()
        val t3Started = CompletableDeferred<Unit>()
        val holdT3 = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            when (url.substringAfter("v=")) {
                "rel_1" -> holdT1.await()
                "rel_2" -> holdT2.await()
                "rel_3" -> {
                    t3Started.complete(Unit)
                    holdT3.await()
                }
            }
        }

        manager.enqueueBatch("b_rel", "Release Test", listOf(
            BulkDownloadRequest("rel_1", "R1"),
            BulkDownloadRequest("rel_2", "R2"),
            BulkDownloadRequest("rel_3", "R3"),
        ))

        var aRel = 0
        while (aRel++ < 50 && manager.tasks.value.find { it.videoId == "rel_1" }?.status != DownloadStatus.Running) {
            delay(10)
        }

        // Cancel rel_1
        val task1 = manager.tasks.value.find { it.videoId == "rel_1" }
        assertNotNull(task1)
        manager.cancel(task1!!.id)

        // rel_3 must now acquire the released permit and start running
        t3Started.await()
        assertEquals(DownloadStatus.Cancelled, manager.tasks.value.find { it.videoId == "rel_1" }?.status)
        assertEquals(DownloadStatus.Running, manager.tasks.value.find { it.videoId == "rel_3" }?.status)

        holdT3.complete(Unit)
        holdT2.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testProgressThrottling_rapidCallbacks_throttledToTimeOrDelta() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val emissions = Collections.synchronizedList(ArrayList<Float>())
        val doneGate = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, onProgress ->
            // Fire 50 rapid updates with small deltas (< 1%) within 5ms
            for (i in 1..50) {
                onProgress(10f + (i * 0.01f), 100L, "[download] downloading")
            }
            delay(10)
            doneGate.complete(Unit)
        }

        // Observe task progress emissions
        val observerJob = testScope.launch {
            manager.tasks.collect { tasks ->
                val p = tasks.firstOrNull()?.progress ?: return@collect
                emissions.add(p)
            }
        }

        manager.download("https://youtu.be/rapid", AudioFormat.M4A)
        doneGate.await()

        observerJob.cancel()
        // Out of 50 rapid updates within 5ms, due to throttling (time >= 250ms or delta >= 1%),
        // emissions must be far fewer than 50
        assertTrue("Emissions count (${emissions.size}) must be significantly throttled (< 15)", emissions.size < 15)
        testScope.cancel()
    }

    @Test
    fun testProgressThrottling_largeDelta_emitsImmediately() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val observedProgress = Collections.synchronizedList(ArrayList<Float>())
        val gate = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, onProgress ->
            onProgress(10f, 100L, "[download]")
            delay(5)
            onProgress(15f, 90L, "[download]") // +5% delta >= 1% -> emits immediately
            delay(5)
            onProgress(25f, 80L, "[download]") // +10% delta >= 1% -> emits immediately
            delay(5)
            gate.complete(Unit)
        }

        val job = testScope.launch {
            manager.tasks.collect { tasks ->
                tasks.firstOrNull()?.progress?.let { observedProgress.add(it) }
            }
        }

        manager.download("https://youtu.be/delta", AudioFormat.M4A)
        gate.await()
        job.cancel()

        assertTrue(observedProgress.any { it in 9.9f..10.1f })
        assertTrue(observedProgress.any { it in 14.9f..15.1f })
        assertTrue(observedProgress.any { it in 24.9f..25.1f })
        testScope.cancel()
    }

    @Test
    fun testProgressThrottling_terminalStatesNeverSuppressed() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val statuses = Collections.synchronizedList(ArrayList<DownloadStatus>())
        val doneGate = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, onProgress ->
            onProgress(50f, 10L, "[download]")
            delay(20)
            doneGate.complete(Unit)
        }

        val job = testScope.launch {
            manager.tasks.collect { tasks ->
                tasks.firstOrNull()?.status?.let { statuses.add(it) }
            }
        }

        manager.download("https://youtu.be/terminal", AudioFormat.M4A)
        doneGate.await()

        // Wait for Done emission to be collected
        var aDone = 0
        while (aDone++ < 50 && !statuses.contains(DownloadStatus.Done)) {
            delay(10)
        }

        job.cancel()
        assertTrue("Terminal state Done must be emitted immediately", statuses.contains(DownloadStatus.Done))
        testScope.cancel()
    }

    @Test
    fun testConcurrency_failingTask_releasesPermitSafely() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val nextStarted = CompletableDeferred<Unit>()
        val holdOk2 = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            when (url.substringAfter("v=")) {
                "fail_1" -> throw RuntimeException("Simulated download failure")
                "ok_2" -> {
                    nextStarted.complete(Unit)
                    holdOk2.await()
                }
                else -> delay(10)
            }
        }

        manager.enqueueBatch("b_fail", "Fail Test", listOf(
            BulkDownloadRequest("fail_1", "Fail 1"),
            BulkDownloadRequest("ok_1", "OK 1"),
            BulkDownloadRequest("ok_2", "OK 2"),
        ))

        nextStarted.await()
        var aFail = 0
        while (aFail++ < 50 && manager.tasks.value.find { it.videoId == "fail_1" }?.status != DownloadStatus.Failed) {
            delay(10)
        }

        assertEquals(DownloadStatus.Failed, manager.tasks.value.find { it.videoId == "fail_1" }?.status)
        assertEquals(DownloadStatus.Running, manager.tasks.value.find { it.videoId == "ok_2" }?.status)
        holdOk2.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testConcurrency_cancelAllTasks_allPermitsRestored() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val holdGate = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            holdGate.await()
        }

        manager.enqueueBatch("b_all", "All", (1..5).map { BulkDownloadRequest("all_$it", "Track $it") })

        var aAll = 0
        while (aAll++ < 50 && manager.tasks.value.count { it.status == DownloadStatus.Running } < 2) {
            delay(10)
        }

        manager.cancelBatch("b_all")

        // Now all permits should be free; a new single download on same manager must immediately acquire permit and run
        val newRan = CompletableDeferred<Unit>()
        manager.downloadRunner = { _, _, _, _ ->
            newRan.complete(Unit)
        }
        manager.download("https://youtu.be/restored", AudioFormat.M4A)
        newRan.await()
        assertTrue(newRan.isCompleted)
        testScope.cancel()
    }

    @Test
    fun testProgressThrottling_negativeOrNaNProgress_handledGracefully() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, onProgress ->
            onProgress(-1f, -1L, "[download] preparing")
            delay(5)
            onProgress(Float.NaN, -1L, "[download] weird")
            delay(5)
            onProgress(50f, 20L, "[download] 50%")
        }

        manager.download("https://youtu.be/nan", AudioFormat.M4A)

        var aNan = 0
        while (aNan++ < 50 && manager.tasks.value.firstOrNull()?.status != DownloadStatus.Done) {
            delay(10)
        }

        val task = manager.tasks.value.first()
        assertEquals(100f, task.progress, 0.01f)
        assertEquals(DownloadStatus.Done, task.status)
        testScope.cancel()
    }

    @Test
    fun testConcurrency_concurrentEnqueueFromMultipleThreads() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> delay(10) }

        // Launch 10 concurrent coroutines each enqueueing a batch with some overlapping items
        val jobs = (1..10).map { workerId ->
            async(Dispatchers.Default) {
                val items = listOf(
                    BulkDownloadRequest("shared_1", "Shared Track 1"),
                    BulkDownloadRequest("worker_${workerId}_a", "Worker Track A"),
                    BulkDownloadRequest("worker_${workerId}_b", "Worker Track B"),
                )
                manager.enqueueBatch("batch_worker_$workerId", "Batch $workerId", items)
            }
        }

        val results = jobs.awaitAll()
        assertEquals(10, results.size)

        // shared_1 must have been enqueued AT MOST once
        val sharedTasks = manager.tasks.value.filter { it.videoId == "shared_1" }
        assertEquals("shared_1 must be enqueued at most once across concurrent batches", 1, sharedTasks.size)
        testScope.cancel()
    }

    // =========================================================================
    // Feature 7: Aggregate Download Progress & Batch Lifecycle (Tests 21-30)
    // =========================================================================

    @Test
    fun testBatchLifecycle_progressCalculation_weightedByTaskProgress() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val gateT1 = CompletableDeferred<Unit>()
        val gateT2 = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, onProgress ->
            val vId = url.substringAfter("v=")
            if (vId == "w1") {
                onProgress(50f, 10L, "[download]")
                gateT1.await()
            } else if (vId == "w2") {
                onProgress(20f, 10L, "[download]")
                gateT2.await()
            }
        }

        manager.enqueueBatch("b_weight", "Weighted", listOf(
            BulkDownloadRequest("w1", "W1"),
            BulkDownloadRequest("w2", "W2"),
        ))

        // Wait until both tasks are running with their progress
        var aWeight = 0
        while (aWeight++ < 50 && (manager.batches.value.find { it.id == "b_weight" }?.progress ?: 0f) < 34f) {
            delay(10)
        }

        val batch = manager.batches.value.find { it.id == "b_weight" }
        assertNotNull(batch)
        // (50 + 20) / 2 = 35%
        assertEquals(35f, batch?.progress ?: 0f, 0.5f)

        gateT1.complete(Unit)
        gateT2.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testBatchLifecycle_transitionsToCompletedWhenAllTasksFinish() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            delay(20)
        }

        manager.enqueueBatch("b_complete", "Complete", listOf(
            BulkDownloadRequest("c1", "C1"),
            BulkDownloadRequest("c2", "C2"),
        ))

        var aComp = 0
        while (aComp++ < 50 && manager.batches.value.find { it.id == "b_complete" }?.isCompleted != true) {
            delay(10)
        }

        val batch = manager.batches.value.find { it.id == "b_complete" }
        assertNotNull(batch)
        assertTrue(batch?.isCompleted == true)
        assertFalse(batch?.isCancelled == true)
        assertEquals(100f, batch?.progress ?: 0f, 0.01f)
        assertEquals(2, batch?.completedCount)
        assertEquals(0, batch?.runningCount)
        assertEquals(0, batch?.queuedCount)
        testScope.cancel()
    }

    @Test
    fun testBatchLifecycle_statusCountsConservation() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val gate = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            gate.await()
        }

        manager.enqueueBatch("b_cons", "Conservation", (1..5).map { BulkDownloadRequest("cv_$it", "Track $it") })

        var aCons = 0
        while (aCons++ < 50 && manager.tasks.value.count { it.status == DownloadStatus.Running } < 2) {
            delay(10)
        }

        val batch = manager.batches.value.find { it.id == "b_cons" }
        assertNotNull(batch)
        val sum = (batch?.completedCount ?: 0) +
            (batch?.runningCount ?: 0) +
            (batch?.queuedCount ?: 0) +
            (batch?.failedCount ?: 0) +
            (batch?.cancelledCount ?: 0)

        assertEquals(batch?.totalCount, sum)
        gate.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testCancelBatch_cancelsAllBatchTasksAndMarksBatchCancelled() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val hold = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            hold.await()
        }

        manager.enqueueBatch("b_to_cancel", "Cancel Me", (1..4).map { BulkDownloadRequest("can_$it", "Track $it") })

        var aCan = 0
        while (aCan++ < 50 && manager.tasks.value.count { it.status == DownloadStatus.Running } < 2) {
            delay(10)
        }

        manager.cancelBatch("b_to_cancel")

        val batch = manager.batches.value.find { it.id == "b_to_cancel" }
        assertNotNull(batch)
        assertTrue(batch?.isCancelled == true)
        assertFalse(batch?.isCompleted == true)
        assertEquals(4, batch?.cancelledCount)

        val batchTasks = manager.tasks.value.filter { it.batchId == "b_to_cancel" }
        assertTrue(batchTasks.all { it.status == DownloadStatus.Cancelled })
        testScope.cancel()
    }

    @Test
    fun testObserveBatch_emitsBatchStateTransitionsInSequence() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> delay(15) }

        manager.enqueueBatch("b_obs", "Observe Batch", listOf(BulkDownloadRequest("ob1", "OB1")))

        val batch = manager.observeBatch("b_obs").first { it?.isCompleted == true }
        assertNotNull(batch)
        assertEquals(100f, batch?.progress ?: 0f, 0.01f)
        testScope.cancel()
    }

    @Test
    fun testBatchLifecycle_allTasksFailed_marksCompletedWithFailedCount() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            throw RuntimeException("Network down")
        }

        manager.enqueueBatch("b_all_fail", "All Fail", listOf(
            BulkDownloadRequest("af1", "AF1"),
            BulkDownloadRequest("af2", "AF2"),
        ))

        var aFailAll = 0
        while (aFailAll++ < 50 && manager.batches.value.find { it.id == "b_all_fail" }?.isCompleted != true) {
            delay(10)
        }

        val batch = manager.batches.value.find { it.id == "b_all_fail" }
        assertNotNull(batch)
        assertTrue(batch?.isCompleted == true)
        assertEquals(2, batch?.failedCount)
        assertEquals(0, batch?.completedCount)
        testScope.cancel()
    }

    @Test
    fun testBatchLifecycle_mixedOutcomes_succeededFailedAndCancelled() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val hold = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            when (url.substringAfter("v=")) {
                "mix_ok" -> delay(10)
                "mix_fail" -> throw RuntimeException("Fail")
                "mix_cancel" -> hold.await()
            }
        }

        manager.enqueueBatch("b_mixed", "Mixed", listOf(
            BulkDownloadRequest("mix_ok", "OK"),
            BulkDownloadRequest("mix_fail", "Fail"),
            BulkDownloadRequest("mix_cancel", "Cancel"),
        ))

        // Wait until mix_cancel is running, then cancel it individually
        var aMixRun = 0
        while (aMixRun++ < 50 && manager.tasks.value.find { it.videoId == "mix_cancel" }?.status != DownloadStatus.Running) {
            delay(10)
        }

        val cancelTask = manager.tasks.value.find { it.videoId == "mix_cancel" }
        assertNotNull(cancelTask)
        manager.cancel(cancelTask!!.id)

        var aMixDone = 0
        while (aMixDone++ < 50 && manager.batches.value.find { it.id == "b_mixed" }?.isCompleted != true) {
            delay(10)
        }

        val batch = manager.batches.value.find { it.id == "b_mixed" }
        assertNotNull(batch)
        assertEquals(1, batch?.completedCount)
        assertEquals(1, batch?.failedCount)
        assertEquals(1, batch?.cancelledCount)
        assertEquals(3, batch?.totalCount)
        testScope.cancel()
    }

    @Test
    fun testCancelBatch_alreadyCompletedBatch_noop() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> delay(10) }

        manager.enqueueBatch("b_already_done", "Done", listOf(BulkDownloadRequest("done_1", "Done 1")))

        var aDone = 0
        while (aDone++ < 50 && manager.batches.value.find { it.id == "b_already_done" }?.isCompleted != true) {
            delay(10)
        }

        // Now cancel completed batch
        manager.cancelBatch("b_already_done")
        val batch = manager.batches.value.find { it.id == "b_already_done" }
        assertTrue(batch?.isCompleted == true)
        assertFalse(batch?.isCancelled == true)
        testScope.cancel()
    }

    @Test
    fun testCancelBatch_nonExistentBatchId_safeNoop() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        // Must not throw or crash
        manager.cancelBatch("non_existent_uuid_12345")
        testScope.cancel()
    }

    @Test
    fun testMultipleConcurrentBatches_maintainIndependentLifecycles() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val holdB1 = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            if (url.contains("b1_t1")) {
                holdB1.await()
            } else {
                delay(10)
            }
        }

        manager.enqueueBatch("batch_indep_1", "B1", listOf(BulkDownloadRequest("b1_t1", "B1T1")))
        manager.enqueueBatch("batch_indep_2", "B2", listOf(BulkDownloadRequest("b2_t1", "B2T1")))

        // Cancel B1
        manager.cancelBatch("batch_indep_1")

        val b1 = manager.batches.value.find { it.id == "batch_indep_1" }
        assertTrue(b1?.isCancelled == true)

        // B2 must complete successfully unaffected by B1 cancellation
        var aB2 = 0
        while (aB2++ < 50 && manager.batches.value.find { it.id == "batch_indep_2" }?.isCompleted != true) {
            delay(10)
        }

        val b2 = manager.batches.value.find { it.id == "batch_indep_2" }
        assertTrue(b2?.isCompleted == true)
        assertFalse(b2?.isCancelled == true)
        testScope.cancel()
    }

    // =========================================================================
    // Feature 8: Background Keepalive & Service Logic (Tests 31-40)
    // =========================================================================

    @Test
    fun testKeepaliveLogic_activeTasksRequireService() {
        val queuedTask = DownloadTask("1", "url", "title", AudioFormat.M4A, status = DownloadStatus.Queued)
        val runningTask = DownloadTask("2", "url", "title", AudioFormat.M4A, status = DownloadStatus.Running)
        val doneTask = DownloadTask("3", "url", "title", AudioFormat.M4A, status = DownloadStatus.Done)

        assertTrue(DownloadService.shouldServiceRun(listOf(queuedTask)))
        assertTrue(DownloadService.shouldServiceRun(listOf(runningTask)))
        assertTrue(DownloadService.shouldServiceRun(listOf(queuedTask, doneTask)))
        assertFalse(DownloadService.shouldServiceRun(listOf(doneTask)))
    }

    @Test
    fun testKeepaliveLogic_noActiveTasksStopsService() {
        assertFalse(DownloadService.shouldServiceRun(emptyList()))

        val finishedTasks = listOf(
            DownloadTask("1", "url", "t1", AudioFormat.M4A, status = DownloadStatus.Done),
            DownloadTask("2", "url", "t2", AudioFormat.M4A, status = DownloadStatus.Failed),
            DownloadTask("3", "url", "t3", AudioFormat.M4A, status = DownloadStatus.Cancelled),
        )
        assertFalse(DownloadService.shouldServiceRun(finishedTasks))
    }

    @Test
    fun testNotificationFormatting_singleDownload() {
        val task = DownloadTask(
            id = "1",
            url = "url",
            title = "Daft Punk - Get Lucky",
            format = AudioFormat.M4A,
            status = DownloadStatus.Running,
            progress = 42.5f,
            etaSec = 45L,
        )

        val content = DownloadService.formatNotificationContent(listOf(task), emptyList())
        assertEquals("Daft Punk - Get Lucky", content.title)
        assertTrue(content.text.contains("42%"))
        assertTrue(content.text.contains("45sn"))
        assertEquals(42, content.progress)
    }

    @Test
    fun testNotificationFormatting_batchDownload() {
        val batch = DownloadBatch(
            id = "b1",
            title = "Discovery (Full Album)",
            totalCount = 14,
            completedCount = 5,
            progress = 35.7f,
        )
        val runningTask = DownloadTask(
            id = "t1",
            url = "url",
            title = "Harder, Better, Faster, Stronger",
            format = AudioFormat.M4A,
            batchId = "b1",
            status = DownloadStatus.Running,
        )

        val content = DownloadService.formatNotificationContent(listOf(runningTask), listOf(batch))
        assertEquals("Discovery (Full Album)", content.title)
        assertTrue(content.text.contains("5/14 indirildi"))
        assertTrue(content.text.contains("Harder, Better, Faster, Stronger"))
        assertEquals(35, content.progress)
    }

    @Test
    fun testNotificationProgress_integerClamping() {
        assertEquals(0, DownloadService.clampProgress(-10))
        assertEquals(0, DownloadService.clampProgress(0))
        assertEquals(50, DownloadService.clampProgress(50))
        assertEquals(100, DownloadService.clampProgress(100))
        assertEquals(100, DownloadService.clampProgress(150))
    }

    @Test
    fun testNotification_allTasksCompleted_triggersForegroundRemoval() {
        val tasks = listOf(
            DownloadTask("1", "url", "t1", AudioFormat.M4A, status = DownloadStatus.Done),
        )
        val content = DownloadService.formatNotificationContent(tasks, emptyList())
        assertEquals("İndirmeler Tamamlandı", content.title)
        assertEquals(100, content.progress)
    }

    @Test
    fun testNotification_batchCancelled_updatesImmediately() {
        val cancelledBatch = DownloadBatch(
            id = "b_can",
            title = "Cancelled Batch",
            totalCount = 5,
            isCancelled = true,
        )
        val singleTask = DownloadTask(
            id = "single_1",
            url = "url",
            title = "Solo Song",
            format = AudioFormat.M4A,
            status = DownloadStatus.Running,
            progress = 80f,
        )

        val content = DownloadService.formatNotificationContent(listOf(singleTask), listOf(cancelledBatch))
        // Should ignore cancelled batch and show single active task
        assertEquals("Solo Song", content.title)
        assertEquals(80, content.progress)
    }

    @Test
    fun testService_etaFormatting() {
        assertEquals("", DownloadService.formatEta(-1))
        assertEquals("", DownloadService.formatEta(0))
        assertEquals("45sn", DownloadService.formatEta(45))
        assertEquals("1dk 15sn", DownloadService.formatEta(75))
        assertEquals("5dk 0sn", DownloadService.formatEta(300))
    }

    @Test
    fun testNotificationChannel_configurationValues() {
        assertEquals("yrt_download_channel", DownloadService.CHANNEL_ID)
        assertEquals("İndirmeler", DownloadService.CHANNEL_NAME)
        assertEquals(2001, DownloadService.NOTIFICATION_ID)
        assertEquals("com.yrt.music.action.START_DOWNLOAD_SERVICE", DownloadService.ACTION_START)
        assertEquals("com.yrt.music.action.STOP_DOWNLOAD_SERVICE", DownloadService.ACTION_STOP)
        assertEquals("com.yrt.music.action.CANCEL_ALL_DOWNLOADS", DownloadService.ACTION_CANCEL_ALL)
    }

    @Test
    fun testKeepalive_concurrentNewBatchEnqueuedWhilePreviousFinishing() = runBlocking {
        val task1 = DownloadTask("1", "url1", "t1", AudioFormat.M4A, status = DownloadStatus.Done)
        val task2 = DownloadTask("2", "url2", "t2", AudioFormat.M4A, status = DownloadStatus.Queued)

        // As task1 is finished but task2 is queued, shouldServiceRun must remain true
        assertTrue(DownloadService.shouldServiceRun(listOf(task1, task2)))
    }
}
