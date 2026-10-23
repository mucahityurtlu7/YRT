package com.yrt.music

import com.yrt.music.data.downloader.YtDlpDownloadManager
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.BulkDownloadRequest
import com.yrt.music.data.model.DownloadBatch
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.LocalSongLookup
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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Empirical Challenge Suite for Milestone 2:
 * Batch Lifecycle, Deduplication & Cancellation Stress Testing.
 *
 * Verifies under high concurrency, resource pressure, race conditions, and boundary inputs:
 * 1. Intra-batch deduplication (massive duplicate storms, metadata retention, conservation laws).
 * 2. Local library deduplication against SongRepository.hasVideoId (static and dynamic mutation).
 * 3. In-flight task deduplication (Queued & Running tasks blocked; Failed & Cancelled allowed for retry).
 * 4. Multi-threaded concurrent enqueue bursts (zero duplicate tasks across threads).
 * 5. Prompt cancellation of running and queued tasks with full Semaphore(2) permit restoration.
 * 6. Repeated enqueue-cancel cycles with zero permit leakage or deadlocks.
 * 7. Aggregate batch progress and status conservation invariants across all lifecycle stages.
 */
class BatchLifecycleAndCancellationStressTest {

    private class ThreadSafeSongLookup(
        initialVideoIds: Set<String> = emptySet(),
    ) : LocalSongLookup {
        private val indexedIds = ConcurrentHashMap.newKeySet<String>().apply { addAll(initialVideoIds) }
        private val _songs = MutableStateFlow<List<Song>>(emptyList())
        override val songs: StateFlow<List<Song>> = _songs.asStateFlow()

        override fun hasVideoId(videoId: String): Boolean = indexedIds.contains(videoId)
        override fun getSongByVideoId(videoId: String): Song? = null
        override suspend fun refresh() { /* no-op in headless test */ }

        fun addVideoId(id: String) { indexedIds.add(id) }
        fun removeVideoId(id: String) { indexedIds.remove(id) }
    }

    // =========================================================================
    // SECTION 1: Intra-Batch Deduplication Stress & Edge Cases
    // =========================================================================

    @Test
    fun testIntraBatchDeduplication_100IdenticalVideoIds_enqueuesExactlyOne() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        val requests = (1..100).map { i ->
            BulkDownloadRequest(
                videoId = "identical_vid",
                title = "Duplicate Title $i",
                artist = "Artist $i",
                album = "Album $i",
            )
        }

        val result = manager.enqueueBatch("batch_100_dup", "100 Duplicates", requests)

        assertEquals("batch_100_dup", result.batchId)
        assertEquals(100, result.totalRequested)
        assertEquals(1, result.enqueuedCount)
        assertEquals(99, result.alreadyDownloadedCount)
        assertEquals(result.totalRequested, result.enqueuedCount + result.alreadyDownloadedCount)

        val batchTasks = manager.tasks.value.filter { it.batchId == "batch_100_dup" }
        assertEquals(1, batchTasks.size)
        // Verify metadata from the first occurrence was retained
        assertEquals("Duplicate Title 1", batchTasks.first().title)
        assertEquals("Artist 1", batchTasks.first().artist)

        testScope.cancel()
    }

    @Test
    fun testIntraBatchDeduplication_interleavedDuplicateGroups_preservesFirstOccurrenceMetadata() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        // Sequence: A1, B1, C1, A2, B2, C2, D1, A3, E1, B3
        val requests = listOf(
            BulkDownloadRequest("vid_A", "Title A1", "Artist A1"),
            BulkDownloadRequest("vid_B", "Title B1", "Artist B1"),
            BulkDownloadRequest("vid_C", "Title C1", "Artist C1"),
            BulkDownloadRequest("vid_A", "Title A2", "Artist A2"),
            BulkDownloadRequest("vid_B", "Title B2", "Artist B2"),
            BulkDownloadRequest("vid_C", "Title C2", "Artist C2"),
            BulkDownloadRequest("vid_D", "Title D1", "Artist D1"),
            BulkDownloadRequest("vid_A", "Title A3", "Artist A3"),
            BulkDownloadRequest("vid_E", "Title E1", "Artist E1"),
            BulkDownloadRequest("vid_B", "Title B3", "Artist B3"),
        )

        val result = manager.enqueueBatch("batch_interleaved", "Interleaved", requests)
        assertEquals(10, result.totalRequested)
        assertEquals(5, result.enqueuedCount) // Unique: A, B, C, D, E
        assertEquals(5, result.alreadyDownloadedCount)

        val tasks = manager.tasks.value.filter { it.batchId == "batch_interleaved" }
        assertEquals(5, tasks.size)

        val taskA = tasks.find { it.videoId == "vid_A" }
        assertEquals("Title A1", taskA?.title)
        assertEquals("Artist A1", taskA?.artist)

        val taskB = tasks.find { it.videoId == "vid_B" }
        assertEquals("Title B1", taskB?.title)
        assertEquals("Artist B1", taskB?.artist)

        testScope.cancel()
    }

    @Test
    fun testIntraBatchDeduplication_allDuplicatesAlreadyInLocalLibrary_zeroEnqueued() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup(setOf("in_lib_1", "in_lib_2"))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        // Multiple duplicates of items already in local library
        val requests = listOf(
            BulkDownloadRequest("in_lib_1", "Lib 1"),
            BulkDownloadRequest("in_lib_1", "Lib 1 copy"),
            BulkDownloadRequest("in_lib_2", "Lib 2"),
            BulkDownloadRequest("in_lib_2", "Lib 2 copy 1"),
            BulkDownloadRequest("in_lib_2", "Lib 2 copy 2"),
        )

        val result = manager.enqueueBatch("b_all_in_lib_dup", "In Lib Dup", requests)
        assertEquals(5, result.totalRequested)
        assertEquals(0, result.enqueuedCount)
        assertEquals(5, result.alreadyDownloadedCount)

        val batch = manager.batches.value.find { it.id == "b_all_in_lib_dup" }
        assertNotNull(batch)
        assertTrue(batch?.isCompleted == true)
        assertEquals(100f, batch?.progress ?: 0f, 0.01f)
        assertEquals(0, batch?.totalCount)
        assertEquals(0, manager.tasks.value.size)

        testScope.cancel()
    }

    @Test
    fun testIntraBatchDeduplication_conservationLawHoldsWithVariedDuplicates() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup(setOf("lib_x", "lib_y"))
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        // 3 in lib (with 2 duplicates = 5 requests) + 4 new unique (with 3 duplicates = 7 requests) = 12 total
        val requests = listOf(
            BulkDownloadRequest("lib_x", "LX"),
            BulkDownloadRequest("lib_x", "LX dup"),
            BulkDownloadRequest("lib_y", "LY"),
            BulkDownloadRequest("lib_y", "LY dup"),
            BulkDownloadRequest("lib_y", "LY tri"),
            BulkDownloadRequest("new_1", "N1"),
            BulkDownloadRequest("new_2", "N2"),
            BulkDownloadRequest("new_2", "N2 dup"),
            BulkDownloadRequest("new_3", "N3"),
            BulkDownloadRequest("new_3", "N3 dup 1"),
            BulkDownloadRequest("new_3", "N3 dup 2"),
            BulkDownloadRequest("new_4", "N4"),
        )

        val res = manager.enqueueBatch("b_conserve", "Conserve", requests)
        assertEquals(12, res.totalRequested)
        assertEquals(4, res.enqueuedCount) // new_1, new_2, new_3, new_4
        assertEquals(8, res.alreadyDownloadedCount) // 5 lib requests + 3 duplicate new requests
        assertEquals(res.totalRequested, res.enqueuedCount + res.alreadyDownloadedCount)

        val batch = manager.batches.value.find { it.id == "b_conserve" }
        assertNotNull(batch)
        assertEquals(4, batch?.totalCount)

        testScope.cancel()
    }

    @Test
    fun testIntraBatchDeduplication_emptyAndSingleItemList_boundsVerified() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        // 1. Empty list
        val emptyResult = manager.enqueueBatch("b_empty", "Empty", emptyList())
        assertEquals(0, emptyResult.totalRequested)
        assertEquals(0, emptyResult.enqueuedCount)
        assertEquals(0, emptyResult.alreadyDownloadedCount)
        val emptyBatch = manager.batches.value.find { it.id == "b_empty" }
        assertTrue(emptyBatch?.isCompleted == true)
        assertEquals(100f, emptyBatch?.progress ?: 0f, 0.01f)

        // 2. Single item
        val singleResult = manager.enqueueBatch("b_single", "Single", listOf(BulkDownloadRequest("solo_1", "Solo")))
        assertEquals(1, singleResult.totalRequested)
        assertEquals(1, singleResult.enqueuedCount)
        assertEquals(0, singleResult.alreadyDownloadedCount)
        val singleBatch = manager.batches.value.find { it.id == "b_single" }
        assertEquals(1, singleBatch?.totalCount)

        testScope.cancel()
    }

    // =========================================================================
    // SECTION 2: Local Library & In-Flight Deduplication Stress
    // =========================================================================

    @Test
    fun testLocalLibraryDeduplication_largeLibrary_exactMatchSkipping() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val existingLibraryIds = (1..50).map { "existing_lib_vid_$it" }.toSet()
        val lookup = ThreadSafeSongLookup(existingLibraryIds)
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> }

        // 50 existing + 50 brand new = 100 requests
        val requests = (1..50).map { BulkDownloadRequest("existing_lib_vid_$it", "Existing $it") } +
            (1..50).map { BulkDownloadRequest("brand_new_vid_$it", "Brand New $it") }

        val result = manager.enqueueBatch("b_half_in_lib", "Half In Lib", requests)
        assertEquals(100, result.totalRequested)
        assertEquals(50, result.enqueuedCount)
        assertEquals(50, result.alreadyDownloadedCount)

        val enqueuedVideoIds = manager.tasks.value
            .filter { it.batchId == "b_half_in_lib" }
            .map { it.videoId }
            .toSet()

        val expectedNewIds = (1..50).map { "brand_new_vid_$it" }.toSet()
        assertEquals(expectedNewIds, enqueuedVideoIds)

        testScope.cancel()
    }

    @Test
    fun testLocalLibraryDeduplication_dynamicLibraryMutationDuringEnqueue() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> delay(10) }

        // Enqueue batch 1 with 3 items
        manager.enqueueBatch("b_dyn_1", "Dyn 1", listOf(
            BulkDownloadRequest("dyn_1", "Dyn 1"),
            BulkDownloadRequest("dyn_2", "Dyn 2"),
        ))

        // Dynamically add a song to the local library
        lookup.addVideoId("dyn_3")

        // Enqueue batch 2 containing dyn_1 (in-flight), dyn_3 (now in library), and dyn_4 (new)
        val res2 = manager.enqueueBatch("b_dyn_2", "Dyn 2", listOf(
            BulkDownloadRequest("dyn_1", "Dyn 1"),
            BulkDownloadRequest("dyn_3", "Dyn 3"),
            BulkDownloadRequest("dyn_4", "Dyn 4"),
        ))

        assertEquals(3, res2.totalRequested)
        assertEquals(1, res2.enqueuedCount) // Only dyn_4 enqueued
        assertEquals(2, res2.alreadyDownloadedCount) // dyn_1 (in-flight) + dyn_3 (in lib)

        val b2Tasks = manager.tasks.value.filter { it.batchId == "b_dyn_2" }
        assertEquals(1, b2Tasks.size)
        assertEquals("dyn_4", b2Tasks.first().videoId)

        testScope.cancel()
    }

    @Test
    fun testInFlightDeduplication_queuedTaskInPriorBatch_blocksDuplicateEnqueue() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        val holdRunningTasks = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            holdRunningTasks.await()
        }

        // Enqueue 4 tasks in batch 1. Max concurrency is 2, so 2 will be Running, and 2 will be Queued.
        manager.enqueueBatch("b_prior", "Prior", (1..4).map { BulkDownloadRequest("item_$it", "Item $it") })

        // Wait until 2 are running and 2 are queued
        withTimeout(3000) {
            while (manager.tasks.value.count { it.status == DownloadStatus.Running } < 2 ||
                manager.tasks.value.count { it.status == DownloadStatus.Queued } < 2) {
                delay(10)
            }
        }

        val queuedVideoIds = manager.tasks.value
            .filter { it.status == DownloadStatus.Queued }
            .mapNotNull { it.videoId }
            .toSet()
        assertTrue(queuedVideoIds.isNotEmpty())

        // Now try to enqueue a second batch requesting one of the QUEUED video IDs + 1 new ID
        val targetQueuedId = queuedVideoIds.first()
        val res2 = manager.enqueueBatch("b_second", "Second", listOf(
            BulkDownloadRequest(targetQueuedId, "Duplicate of Queued"),
            BulkDownloadRequest("truly_new", "Truly New"),
        ))

        assertEquals(2, res2.totalRequested)
        assertEquals(1, res2.enqueuedCount) // Only truly_new
        assertEquals(1, res2.alreadyDownloadedCount)

        val b2Tasks = manager.tasks.value.filter { it.batchId == "b_second" }
        assertEquals(1, b2Tasks.size)
        assertEquals("truly_new", b2Tasks.first().videoId)

        holdRunningTasks.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testInFlightDeduplication_runningTaskInPriorBatch_blocksDuplicateEnqueue() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        val holdRunning = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            holdRunning.await()
        }

        manager.enqueueBatch("b_run", "Running Batch", listOf(BulkDownloadRequest("running_vid", "Running Song")))

        withTimeout(3000) {
            while (manager.tasks.value.none { it.videoId == "running_vid" && it.status == DownloadStatus.Running }) {
                delay(10)
            }
        }

        // Try to enqueue running_vid in another batch
        val res2 = manager.enqueueBatch("b_run_2", "Running Batch 2", listOf(
            BulkDownloadRequest("running_vid", "Running Song Duplicate"),
            BulkDownloadRequest("other_new", "Other New"),
        ))

        assertEquals(2, res2.totalRequested)
        assertEquals(1, res2.enqueuedCount)
        assertEquals(1, res2.alreadyDownloadedCount)

        val b2Tasks = manager.tasks.value.filter { it.batchId == "b_run_2" }
        assertEquals(1, b2Tasks.size)
        assertEquals("other_new", b2Tasks.first().videoId)

        holdRunning.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testInFlightDeduplication_terminalStates_allowsReEnqueueAfterCancelOrFailure() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        var shouldFail = true
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            if (url.contains("retry_vid") && shouldFail) {
                throw RuntimeException("Temporary simulated failure")
            }
            delay(10)
        }

        // 1. Enqueue batch with retry_vid which will fail
        manager.enqueueBatch("b_fail_first", "Fail First", listOf(BulkDownloadRequest("retry_vid", "Retry Song")))

        withTimeout(3000) {
            while (manager.tasks.value.find { it.videoId == "retry_vid" }?.status != DownloadStatus.Failed) {
                delay(10)
            }
        }

        // Verify task failed
        assertEquals(DownloadStatus.Failed, manager.tasks.value.find { it.videoId == "retry_vid" }?.status)

        // 2. Now re-enqueue retry_vid in a new batch: since it's Failed (not Queued or Running), it MUST be accepted!
        shouldFail = false
        val resRetry = manager.enqueueBatch("b_retry_batch", "Retry Batch", listOf(BulkDownloadRequest("retry_vid", "Retry Song")))
        assertEquals(1, resRetry.totalRequested)
        assertEquals(1, resRetry.enqueuedCount)
        assertEquals(0, resRetry.alreadyDownloadedCount)

        withTimeout(3000) {
            while (manager.batches.value.find { it.id == "b_retry_batch" }?.isCompleted != true) {
                delay(10)
            }
        }

        val retryBatch = manager.batches.value.find { it.id == "b_retry_batch" }
        assertTrue(retryBatch?.isCompleted == true)
        assertEquals(1, retryBatch?.completedCount)

        testScope.cancel()
    }

    @Test
    fun testConcurrentEnqueue_50Coroutines_zeroDuplicateTasksEnqueued() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> delay(10) }

        // 50 concurrent coroutines attempting to enqueue batches with high degree of overlapping videoIds
        // There are only 10 unique shared videoIds, each requested across multiple threads
        val jobs = (1..50).map { workerId ->
            async(Dispatchers.Default) {
                val targetId = "shared_pool_${workerId % 10}"
                val uniqueId = "worker_${workerId}_exclusive"
                val requests = listOf(
                    BulkDownloadRequest(targetId, "Shared $targetId"),
                    BulkDownloadRequest(uniqueId, "Exclusive $uniqueId"),
                )
                manager.enqueueBatch("batch_concurrent_$workerId", "Batch $workerId", requests)
            }
        }

        val results = jobs.awaitAll()
        assertEquals(50, results.size)

        // Verify: Every shared videoId in the pool must have been enqueued at most once!
        for (i in 0 until 10) {
            val sharedId = "shared_pool_$i"
            val matchingTasks = manager.tasks.value.filter { it.videoId == sharedId }
            assertTrue(
                "Shared videoId $sharedId should be enqueued at most once across 50 concurrent batches, but was: ${matchingTasks.size}",
                matchingTasks.size <= 1
            )
        }

        // Total enqueued tasks must equal unique videoIds requested
        val allVideoIds = manager.tasks.value.mapNotNull { it.videoId }
        val uniqueVideoIds = allVideoIds.toSet()
        assertEquals("No duplicate videoIds should exist across active tasks", uniqueVideoIds.size, allVideoIds.size)

        testScope.cancel()
    }

    // =========================================================================
    // SECTION 3: Batch Cancellation & Semaphore Permit Restoration
    // =========================================================================

    @Test
    fun testCancelBatch_whileTasksRunning_promptlyCancelsAndRestoresPermits() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        val runningEntered = CountDownLatch(2)
        val holdRunning = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            runningEntered.countDown()
            holdRunning.await()
        }

        // Enqueue batch of 4 tasks (2 running, 2 queued)
        manager.enqueueBatch("b_cancel_run", "Cancel Running", (1..4).map {
            BulkDownloadRequest("can_run_$it", "Task $it")
        })

        // Wait until exactly 2 are actively executing inside the runner
        assertTrue(runningEntered.await(3, TimeUnit.SECONDS))
        assertEquals(2, manager.tasks.value.count { it.status == DownloadStatus.Running })

        // Cancel the entire batch
        manager.cancelBatch("b_cancel_run")

        val batch = manager.batches.value.find { it.id == "b_cancel_run" }
        assertNotNull(batch)
        assertTrue("Batch should be marked cancelled", batch?.isCancelled == true)
        assertFalse("Batch should not be marked completed", batch?.isCompleted == true)
        assertEquals(4, batch?.cancelledCount)

        // All 4 tasks must be Cancelled
        val tasks = manager.tasks.value.filter { it.batchId == "b_cancel_run" }
        assertTrue("All tasks in batch must be cancelled", tasks.all { it.status == DownloadStatus.Cancelled })

        // Now test semaphore permit restoration: enqueue 2 new tasks on a fresh batch.
        // If permits were leaked, these tasks would never acquire permits!
        val newRanLatch = CountDownLatch(2)
        manager.downloadRunner = { _, _, _, _ ->
            newRanLatch.countDown()
        }

        manager.enqueueBatch("b_restored_check", "Restored Check", listOf(
            BulkDownloadRequest("restore_1", "R1"),
            BulkDownloadRequest("restore_2", "R2"),
        ))

        assertTrue(
            "Both new tasks must immediately acquire the restored semaphore permits",
            newRanLatch.await(3, TimeUnit.SECONDS)
        )

        testScope.cancel()
    }

    @Test
    fun testCancelBatch_whileTasksQueued_cancelsWithoutConsumingPermits() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        val holdPermits = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            if (url.contains("blocker")) {
                holdPermits.await()
            }
        }

        // 1. Fill both semaphore permits with blocker tasks
        manager.enqueueBatch("b_blockers", "Blockers", listOf(
            BulkDownloadRequest("blocker_1", "B1"),
            BulkDownloadRequest("blocker_2", "B2"),
        ))

        withTimeout(3000) {
            while (manager.tasks.value.count { it.status == DownloadStatus.Running } < 2) {
                delay(10)
            }
        }

        // 2. Enqueue batch 2 where all tasks will be QUEUED waiting for permits
        manager.enqueueBatch("b_queued_only", "Queued Only", (1..3).map {
            BulkDownloadRequest("queued_item_$it", "Q $it")
        })

        val queuedTasks = manager.tasks.value.filter { it.batchId == "b_queued_only" }
        assertEquals(3, queuedTasks.size)
        assertTrue(queuedTasks.all { it.status == DownloadStatus.Queued })

        // 3. Cancel the queued batch while still queued
        manager.cancelBatch("b_queued_only")

        val queuedBatch = manager.batches.value.find { it.id == "b_queued_only" }
        assertTrue(queuedBatch?.isCancelled == true)
        assertEquals(3, queuedBatch?.cancelledCount)
        assertTrue(manager.tasks.value.filter { it.batchId == "b_queued_only" }.all { it.status == DownloadStatus.Cancelled })

        // 4. Release blockers and verify they complete normally without queued tasks accidentally running
        holdPermits.complete(Unit)

        withTimeout(3000) {
            while (manager.batches.value.find { it.id == "b_blockers" }?.isCompleted != true) {
                delay(10)
            }
        }

        val blockerBatch = manager.batches.value.find { it.id == "b_blockers" }
        assertTrue(blockerBatch?.isCompleted == true)
        assertEquals(2, blockerBatch?.completedCount)

        testScope.cancel()
    }

    @Test
    fun testCancelBatch_permitLeakStress_20RapidEnqueueCancelCycles() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            delay(50)
        }

        // Execute 20 rapid enqueue-and-cancel cycles in sequence.
        // If even a single permit is leaked, by cycle 2 or 3 the semaphore will be depleted and subsequent downloads will hang.
        for (cycle in 1..20) {
            val batchId = "stress_cycle_$cycle"
            manager.enqueueBatch(batchId, "Cycle $cycle", (1..5).map {
                BulkDownloadRequest("c_${cycle}_v_$it", "Track $it")
            })

            // Let it run for a brief jitter (0-15ms) so tasks are in various states (Running / Queued)
            delay((cycle % 4) * 5L)

            // Cancel batch
            manager.cancelBatch(batchId)

            val batch = manager.batches.value.find { it.id == batchId }
            assertTrue("Cycle $cycle batch must be cancelled", batch?.isCancelled == true)
        }

        // Final sanity check: Enqueue a final batch of 2 tasks and ensure both run and complete to Done
        val finalBatchId = "final_restoration_check"
        manager.enqueueBatch(finalBatchId, "Final Check", listOf(
            BulkDownloadRequest("final_1", "Final 1"),
            BulkDownloadRequest("final_2", "Final 2"),
        ))

        withTimeout(5000) {
            while (manager.batches.value.find { it.id == finalBatchId }?.isCompleted != true) {
                delay(10)
            }
        }

        val finalBatch = manager.batches.value.find { it.id == finalBatchId }
        assertNotNull(finalBatch)
        assertTrue("Final batch must complete after 20 cancel cycles", finalBatch?.isCompleted == true)
        assertEquals(2, finalBatch?.completedCount)
        assertEquals(100f, finalBatch?.progress ?: 0f, 0.01f)

        testScope.cancel()
    }

    @Test
    fun testCancelBatch_largeBatch100Tasks_allPromptlyCancelledWithoutDeadlock() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        val holdTasks = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            holdTasks.await()
        }

        // Enqueue 100 items (2 running, 98 queued)
        val requests = (1..100).map { BulkDownloadRequest("huge_$it", "Huge Track $it") }
        manager.enqueueBatch("b_huge_100", "Huge 100", requests)

        withTimeout(3000) {
            while (manager.tasks.value.count { it.status == DownloadStatus.Running } < 2) {
                delay(10)
            }
        }

        // Cancel batch of 100
        val startTime = System.currentTimeMillis()
        manager.cancelBatch("b_huge_100")
        val cancelDuration = System.currentTimeMillis() - startTime

        assertTrue("Cancelling 100 tasks must complete quickly (< 300ms), took: ${cancelDuration}ms", cancelDuration < 300)

        val batch = manager.batches.value.find { it.id == "b_huge_100" }
        assertNotNull(batch)
        assertTrue(batch?.isCancelled == true)
        assertEquals(100, batch?.cancelledCount)
        assertEquals(0, batch?.runningCount)
        assertEquals(0, batch?.queuedCount)

        val batchTasks = manager.tasks.value.filter { it.batchId == "b_huge_100" }
        assertEquals(100, batchTasks.size)
        assertTrue(batchTasks.all { it.status == DownloadStatus.Cancelled })

        testScope.cancel()
    }

    @Test
    fun testCancelBatch_independentBatches_cancellingOneLeavesOtherRunning() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        val holdB1 = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            if (url.contains("b1_vid")) {
                holdB1.await()
            } else {
                delay(15)
            }
        }

        // Enqueue batch 1 and batch 2
        manager.enqueueBatch("batch_alpha", "Alpha", listOf(BulkDownloadRequest("b1_vid", "B1 Track")))
        manager.enqueueBatch("batch_beta", "Beta", listOf(
            BulkDownloadRequest("b2_vid_1", "B2 Track 1"),
            BulkDownloadRequest("b2_vid_2", "B2 Track 2"),
        ))

        // Wait until Alpha is running
        withTimeout(3000) {
            while (manager.tasks.value.find { it.videoId == "b1_vid" }?.status != DownloadStatus.Running) {
                delay(10)
            }
        }

        // Cancel Alpha
        manager.cancelBatch("batch_alpha")

        val alpha = manager.batches.value.find { it.id == "batch_alpha" }
        assertTrue(alpha?.isCancelled == true)
        assertEquals(DownloadStatus.Cancelled, manager.tasks.value.find { it.videoId == "b1_vid" }?.status)

        // Beta must proceed and complete successfully
        withTimeout(3000) {
            while (manager.batches.value.find { it.id == "batch_beta" }?.isCompleted != true) {
                delay(10)
            }
        }

        val beta = manager.batches.value.find { it.id == "batch_beta" }
        assertTrue(beta?.isCompleted == true)
        assertFalse(beta?.isCancelled == true)
        assertEquals(2, beta?.completedCount)

        testScope.cancel()
    }

    @Test
    fun testCancelBatch_concurrentSingleDownload_unaffectedByBatchCancel() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        val singleGate = CompletableDeferred<Unit>()
        val batchGate = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            if (url.contains("single_solo")) {
                singleGate.await()
            } else {
                batchGate.await()
            }
        }

        // 1. Start single download (uses 1 permit)
        manager.download("https://youtu.be/single_solo", AudioFormat.M4A)

        // 2. Enqueue batch (uses second permit + queues rest)
        manager.enqueueBatch("b_with_single", "Batch With Single", listOf(
            BulkDownloadRequest("batch_task_1", "BT1"),
            BulkDownloadRequest("batch_task_2", "BT2"),
        ))

        withTimeout(3000) {
            while (manager.tasks.value.count { it.status == DownloadStatus.Running } < 2) {
                delay(10)
            }
        }

        // 3. Cancel the batch
        manager.cancelBatch("b_with_single")

        val batch = manager.batches.value.find { it.id == "b_with_single" }
        assertTrue(batch?.isCancelled == true)

        // Single download must STILL be Running!
        val singleTask = manager.tasks.value.find { it.url.contains("single_solo") }
        assertEquals(DownloadStatus.Running, singleTask?.status)

        // Complete single download
        singleGate.complete(Unit)

        withTimeout(3000) {
            while (manager.tasks.value.find { it.url.contains("single_solo") }?.status != DownloadStatus.Done) {
                delay(10)
            }
        }

        val finishedSingle = manager.tasks.value.find { it.url.contains("single_solo") }
        assertEquals(DownloadStatus.Done, finishedSingle?.status)

        testScope.cancel()
    }

    @Test
    fun testCancelBatch_idempotency_multipleCancelCallsSafe() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> delay(100) }

        manager.enqueueBatch("b_idem", "Idempotency", listOf(BulkDownloadRequest("idem_1", "Idem 1")))

        // Multiple cancel calls in rapid succession
        manager.cancelBatch("b_idem")
        manager.cancelBatch("b_idem")
        manager.cancelBatch("b_idem")

        val batch = manager.batches.value.find { it.id == "b_idem" }
        assertTrue(batch?.isCancelled == true)
        assertEquals(1, batch?.cancelledCount)

        testScope.cancel()
    }

    @Test
    fun testCancelBatch_alreadyCompletedBatch_remainsCompletedAndNotCancelled() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> delay(10) }

        manager.enqueueBatch("b_fast_done", "Fast Done", listOf(BulkDownloadRequest("fast_1", "Fast 1")))

        withTimeout(3000) {
            while (manager.batches.value.find { it.id == "b_fast_done" }?.isCompleted != true) {
                delay(10)
            }
        }

        val completedBatch = manager.batches.value.find { it.id == "b_fast_done" }
        assertTrue(completedBatch?.isCompleted == true)

        // Call cancelBatch on the finished batch
        manager.cancelBatch("b_fast_done")

        val batchAfter = manager.batches.value.find { it.id == "b_fast_done" }
        assertTrue("Already completed batch must remain isCompleted = true", batchAfter?.isCompleted == true)
        assertFalse("Already completed batch must NOT be marked isCancelled = true", batchAfter?.isCancelled == true)

        testScope.cancel()
    }

    @Test
    fun testCancelBatch_cancelWhileRunnerActivelyEmittingProgress_noStatusOverwrite() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        val cancelTriggered = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, onProgress ->
            // Emit progress continuously
            for (p in 1..20) {
                onProgress(p.toFloat(), 100L, "[download] $p%")
                if (p == 10) {
                    cancelTriggered.complete(Unit)
                }
                delay(10)
            }
        }

        manager.enqueueBatch("b_prog_cancel", "Progress Cancel", listOf(BulkDownloadRequest("pc_vid", "PC Song")))

        // Wait until it reaches 10%
        cancelTriggered.await()

        // Cancel while callbacks are still firing
        manager.cancelBatch("b_prog_cancel")

        // Wait 100ms for runner to exit
        delay(100)

        val task = manager.tasks.value.find { it.videoId == "pc_vid" }
        assertEquals("Task status must remain Cancelled, not overwritten by trailing progress emissions", DownloadStatus.Cancelled, task?.status)

        val batch = manager.batches.value.find { it.id == "b_prog_cancel" }
        assertTrue(batch?.isCancelled == true)

        testScope.cancel()
    }

    @Test
    fun testCancelBatch_raceCondition_cancelDuringEnqueue_handledGracefully() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> delay(30) }

        // Start 2 concurrent coroutines: one enqueueing, one immediately cancelling the same batchId
        val targetBatchId = "b_race_enqueue_cancel"

        val enqueueJob = async(Dispatchers.Default) {
            manager.enqueueBatch(targetBatchId, "Race Batch", (1..10).map {
                BulkDownloadRequest("race_item_$it", "Race Item $it")
            })
        }

        val cancelJob = async(Dispatchers.Default) {
            delay(1) // slight jitter
            manager.cancelBatch(targetBatchId)
        }

        enqueueJob.await()
        cancelJob.await()

        // Eventually, batch must settle cleanly into either Completed or Cancelled without hanging or throwing
        delay(150)

        val batch = manager.batches.value.find { it.id == targetBatchId }
        assertNotNull(batch)
        assertTrue(batch?.isCancelled == true || batch?.isCompleted == true)

        testScope.cancel()
    }

    // =========================================================================
    // SECTION 4: Batch Lifecycle & Aggregate Progress Invariants
    // =========================================================================

    @Test
    fun testBatchLifecycle_conservationLaw_holdsAtAllStages() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        val gate = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            if (url.contains("stage_hold")) {
                gate.await()
            } else {
                delay(10)
            }
        }

        val requests = listOf(
            BulkDownloadRequest("stage_fast_1", "Fast 1"),
            BulkDownloadRequest("stage_fast_2", "Fast 2"),
            BulkDownloadRequest("stage_hold_1", "Hold 1"),
            BulkDownloadRequest("stage_hold_2", "Hold 2"),
        )

        manager.enqueueBatch("b_conserve_stage", "Conserve Stage", requests)

        // Sample state at 5 consecutive intervals
        for (i in 1..5) {
            val batch = manager.batches.value.find { it.id == "b_conserve_stage" }
            if (batch != null) {
                val sum = batch.completedCount + batch.runningCount + batch.queuedCount + batch.failedCount + batch.cancelledCount
                assertEquals(
                    "Total count must equal sum of task counts at all times",
                    batch.totalCount,
                    sum
                )
            }
            delay(15)
        }

        gate.complete(Unit)
        testScope.cancel()
    }

    @Test
    fun testBatchLifecycle_progressComputation_allDoneReaches100Percent() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> delay(10) }

        manager.enqueueBatch("b_reach_100", "Reach 100", (1..3).map {
            BulkDownloadRequest("r100_$it", "Track $it")
        })

        withTimeout(3000) {
            while (manager.batches.value.find { it.id == "b_reach_100" }?.isCompleted != true) {
                delay(10)
            }
        }

        val batch = manager.batches.value.find { it.id == "b_reach_100" }
        assertNotNull(batch)
        assertTrue(batch?.isCompleted == true)
        assertEquals(3, batch?.completedCount)
        assertEquals(100f, batch?.progress ?: 0f, 0.01f)

        testScope.cancel()
    }

    @Test
    fun testBatchLifecycle_allFailed_marksCompletedWithFailedCountAndZeroProgress() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            throw RuntimeException("Network down")
        }

        manager.enqueueBatch("b_all_fail_inv", "All Fail", listOf(
            BulkDownloadRequest("fail_t1", "Fail 1"),
            BulkDownloadRequest("fail_t2", "Fail 2"),
        ))

        withTimeout(3000) {
            while (manager.batches.value.find { it.id == "b_all_fail_inv" }?.isCompleted != true) {
                delay(10)
            }
        }

        val batch = manager.batches.value.find { it.id == "b_all_fail_inv" }
        assertNotNull(batch)
        assertTrue(batch?.isCompleted == true)
        assertFalse(batch?.isCancelled == true)
        assertEquals(2, batch?.failedCount)
        assertEquals(0, batch?.completedCount)
        assertEquals(0f, batch?.progress ?: -1f, 0.01f)

        testScope.cancel()
    }

    @Test
    fun testBatchLifecycle_partialCancelIndividualTasks_aggregateProgressAccurate() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        val hold = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            if (url.contains("to_cancel")) {
                hold.await()
            } else {
                delay(10)
            }
        }

        manager.enqueueBatch("b_partial_indiv", "Partial Indiv", listOf(
            BulkDownloadRequest("to_finish", "Finish"),
            BulkDownloadRequest("to_cancel", "Cancel"),
        ))

        withTimeout(3000) {
            while (manager.tasks.value.find { it.videoId == "to_cancel" }?.status != DownloadStatus.Running) {
                delay(10)
            }
        }

        val cancelTaskId = manager.tasks.value.find { it.videoId == "to_cancel" }?.id
        assertNotNull(cancelTaskId)
        manager.cancel(cancelTaskId!!)

        withTimeout(3000) {
            while (manager.batches.value.find { it.id == "b_partial_indiv" }?.isCompleted != true) {
                delay(10)
            }
        }

        val batch = manager.batches.value.find { it.id == "b_partial_indiv" }
        assertNotNull(batch)
        assertTrue(batch?.isCompleted == true)
        assertEquals(1, batch?.completedCount)
        assertEquals(1, batch?.cancelledCount)
        assertEquals(2, batch?.totalCount)
        // (100 + 0) / 2 = 50%
        assertEquals(50f, batch?.progress ?: 0f, 0.1f)

        testScope.cancel()
    }

    @Test
    fun testBatchLifecycle_reEnqueueAfterBatchCancel_runsToCompletion() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> delay(10) }

        // 1. Enqueue and immediately cancel batch 1
        manager.enqueueBatch("b_attempt_1", "Attempt 1", listOf(BulkDownloadRequest("vid_retry_2", "Song")))
        manager.cancelBatch("b_attempt_1")

        val b1 = manager.batches.value.find { it.id == "b_attempt_1" }
        assertTrue(b1?.isCancelled == true)

        // 2. Enqueue batch 2 with the same videoId
        val res2 = manager.enqueueBatch("b_attempt_2", "Attempt 2", listOf(BulkDownloadRequest("vid_retry_2", "Song")))
        assertEquals(1, res2.totalRequested)
        assertEquals(1, res2.enqueuedCount)

        withTimeout(3000) {
            while (manager.batches.value.find { it.id == "b_attempt_2" }?.isCompleted != true) {
                delay(10)
            }
        }

        val b2 = manager.batches.value.find { it.id == "b_attempt_2" }
        assertTrue(b2?.isCompleted == true)
        assertEquals(1, b2?.completedCount)

        testScope.cancel()
    }

    @Test
    fun testBatchLifecycle_observeBatch_receivesAllStateTransitionsInOrder() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, onProgress ->
            onProgress(30f, 10L, "[download]")
            delay(10)
            onProgress(70f, 5L, "[download]")
            delay(10)
        }

        val observedStates = Collections.synchronizedList(ArrayList<DownloadBatch>())

        val collectorJob = testScope.launch {
            manager.observeBatch("b_flow_test").collect { b ->
                if (b != null) observedStates.add(b)
            }
        }

        manager.enqueueBatch("b_flow_test", "Flow Test", listOf(BulkDownloadRequest("flow_vid", "Flow Song")))

        withTimeout(3000) {
            while (observedStates.none { it.isCompleted }) {
                delay(10)
            }
        }

        collectorJob.cancel()

        assertTrue("Observed states must not be empty", observedStates.isNotEmpty())
        val firstState = observedStates.first()
        assertEquals("b_flow_test", firstState.id)
        assertFalse(firstState.isCompleted)

        val lastState = observedStates.last()
        assertTrue(lastState.isCompleted)
        assertEquals(100f, lastState.progress, 0.01f)

        testScope.cancel()
    }

    @Test
    fun testBatchLifecycle_highVolumeChurnStress_100RapidCyclesNoDeadlockNoLeaks() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = ThreadSafeSongLookup()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ -> delay(1) }

        // 100 rapid batch enqueue & complete cycles
        for (i in 1..100) {
            val batchId = "churn_$i"
            val res = manager.enqueueBatch(batchId, "Churn $i", listOf(
                BulkDownloadRequest("churn_${i}_a", "A"),
                BulkDownloadRequest("churn_${i}_b", "B"),
            ))
            assertEquals(2, res.enqueuedCount)
        }

        // Wait for all 100 batches to reach completed state
        withTimeout(15000) {
            while (manager.batches.value.count { it.isCompleted } < 100) {
                delay(50)
            }
        }

        assertEquals(100, manager.batches.value.filter { it.isCompleted }.size)

        testScope.cancel()
    }
}
