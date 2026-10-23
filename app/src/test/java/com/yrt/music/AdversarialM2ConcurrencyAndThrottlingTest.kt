package com.yrt.music

import com.yrt.music.data.downloader.YtDlpDownloadManager
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.BulkDownloadRequest
import com.yrt.music.data.model.DownloadBatch
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.LocalSongLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Concurrency & Throttling Stress Tests for Milestone 2:
 * 1. Strict adherence to Semaphore(2) under intense contention bursts (mixed single + bulk batches).
 * 2. High-frequency StateFlow sampling verifying running tasks <= 2 at every instant.
 * 3. Rapid cancellation storms and permit-leak resistance.
 * 4. Double-checked cancellation in queue preventing runner execution after cancel.
 * 5. UI StateFlow progress update throttling preventing listener flooding.
 * 6. Zero duplicate emissions for identical progress lines.
 * 7. Aggregate batch StateFlow emission bounding under concurrent dual-task progress.
 * 8. Immediate bypass for stage transitions, destination lines, and terminal states.
 */
class AdversarialM2ConcurrencyAndThrottlingTest {

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
    }

    // =========================================================================
    // Category 1: Concurrency Bounds & Contention Stress Testing
    // =========================================================================

    @Test
    fun testConcurrency_highContention_mixedBursts_strictSemaphoreBound() = runBlocking {
        val customThreadPool = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val testScope = CoroutineScope(SupervisorJob() + customThreadPool)
        val lookup = TestSongLookup()

        val activeCount = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val violationDetected = AtomicBoolean(false)
        val totalExecuted = AtomicInteger(0)
        val totalExpected = 60 // 10 single downloads + 10 batches of 5 items = 60 tasks

        val latch = CountDownLatch(totalExpected)

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            val cur = activeCount.incrementAndGet()
            maxObserved.updateAndGet { prev -> maxOf(prev, cur) }
            if (cur > 2) {
                violationDetected.set(true)
            }
            try {
                // Add variable jitter delay to create realistic contention overlapping
                val jitter = (5L..25L).random()
                delay(jitter)
                totalExecuted.incrementAndGet()
            } finally {
                activeCount.decrementAndGet()
                latch.countDown()
            }
        }

        // Concurrently launch bursts of single downloads and batch downloads from 20 coroutines
        val jobs = (1..20).map { i ->
            testScope.async {
                if (i % 2 == 0) {
                    // Single download
                    manager.download("https://youtu.be/single_$i", AudioFormat.M4A)
                } else {
                    // Batch download of 5 items
                    val requests = (1..5).map { j ->
                        BulkDownloadRequest("batch_${i}_track_$j", "Track $i-$j")
                    }
                    manager.enqueueBatch("batch_$i", "Batch $i", requests)
                }
            }
        }

        jobs.awaitAll()

        // Wait for all 60 tasks to complete
        assertTrue("All 60 downloads should complete within 15 seconds", latch.await(15, TimeUnit.SECONDS))
        assertFalse("At no point should concurrent downloads exceed 2! Observed peak: ${maxObserved.get()}", violationDetected.get())
        assertTrue("Max observed concurrency must be exactly 2 under saturation, was: ${maxObserved.get()}", maxObserved.get() == 2)
        assertEquals(totalExpected, totalExecuted.get())

        testScope.cancel()
        customThreadPool.close()
    }

    @Test
    fun testConcurrency_continuousStateFlowSampling_neverExceedsTwoRunning() = runBlocking {
        val customThreadPool = Executors.newFixedThreadPool(6).asCoroutineDispatcher()
        val testScope = CoroutineScope(SupervisorJob() + customThreadPool)
        val lookup = TestSongLookup()

        val maxRunningObserved = AtomicInteger(0)
        val stopSampling = AtomicBoolean(false)
        val totalTasks = 20
        val completionLatch = CountDownLatch(totalTasks)

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            delay((10L..30L).random())
            completionLatch.countDown()
        }

        // Sampling coroutine continuously querying tasks StateFlow every 1ms
        val samplerJob = testScope.launch {
            while (!stopSampling.get()) {
                val runningCount = manager.tasks.value.count { it.status == DownloadStatus.Running }
                maxRunningObserved.updateAndGet { prev -> maxOf(prev, runningCount) }
                delay(1)
            }
        }

        val requests = (1..totalTasks).map { i -> BulkDownloadRequest("sample_track_$i", "Track $i") }
        manager.enqueueBatch("b_sample", "Sample Batch", requests)

        assertTrue(completionLatch.await(10, TimeUnit.SECONDS))
        stopSampling.set(true)
        samplerJob.join()

        assertTrue("Tasks in Running state must never exceed 2. Observed: ${maxRunningObserved.get()}", maxRunningObserved.get() <= 2)
        assertEquals("Should reach full capacity of 2", 2, maxRunningObserved.get())

        testScope.cancel()
        customThreadPool.close()
    }

    @Test
    fun testConcurrency_rapidCancellationStorm_permitsNeverLeak() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val activeCount = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            val cur = activeCount.incrementAndGet()
            maxObserved.updateAndGet { prev -> maxOf(prev, cur) }
            try {
                delay((20L..50L).random())
            } finally {
                activeCount.decrementAndGet()
            }
        }

        // Enqueue 4 batches of 6 items each (24 items total)
        for (b in 1..4) {
            val requests = (1..6).map { i -> BulkDownloadRequest("storm_${b}_$i", "Storm $b-$i") }
            manager.enqueueBatch("b_storm_$b", "Storm Batch $b", requests)
        }

        // Concurrently cancel batches and tasks in a chaotic storm
        testScope.launch {
            delay(15)
            manager.cancelBatch("b_storm_1")
            delay(10)
            val t2 = manager.tasks.value.find { it.videoId == "storm_2_2" }
            t2?.let { manager.cancel(it.id) }
            delay(10)
            manager.cancelBatch("b_storm_3")
        }

        // Wait until all initial tasks settle into terminal states
        var attempts = 0
        while (attempts++ < 100) {
            val active = manager.tasks.value.count { it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued }
            if (active == 0) break
            delay(50)
        }

        assertTrue("All tasks should settle to Done or Cancelled", attempts < 100)
        assertTrue("Max observed concurrency during cancellation storm must be <= 2", maxObserved.get() <= 2)
        assertEquals(0, activeCount.get())

        // Critical Verification: Ensure semaphore permits did NOT leak!
        // Enqueue 4 new tasks. If permits leaked, these tasks would hang.
        val postLatch = CountDownLatch(4)
        val postActiveCount = AtomicInteger(0)
        val postMaxActive = AtomicInteger(0)

        manager.downloadRunner = { _, _, _, _ ->
            val c = postActiveCount.incrementAndGet()
            postMaxActive.updateAndGet { prev -> maxOf(prev, c) }
            try {
                delay(20)
            } finally {
                postActiveCount.decrementAndGet()
                postLatch.countDown()
            }
        }

        val postRequests = (1..4).map { i -> BulkDownloadRequest("post_storm_$i", "Post Storm $i") }
        manager.enqueueBatch("b_post", "Post Storm Batch", postRequests)

        assertTrue("Post-storm tasks must run and finish; permits must not have leaked!", postLatch.await(5, TimeUnit.SECONDS))
        assertEquals(2, postMaxActive.get())

        testScope.cancel()
    }

    @Test
    fun testConcurrency_exceptionInRunner_releasesPermitImmediately() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            when (url.substringAfter("v=")) {
                "crash_1" -> throw RuntimeException("Simulated native crash")
                "crash_2" -> throw IllegalStateException("Simulated memory allocation failure")
                "crash_3" -> throw IllegalArgumentException("Simulated invalid argument error")
                else -> delay(20)
            }
        }

        val requests = listOf(
            BulkDownloadRequest("crash_1", "Crash 1"),
            BulkDownloadRequest("crash_2", "Crash 2"),
            BulkDownloadRequest("crash_3", "Crash 3"),
            BulkDownloadRequest("survivor_1", "Survivor 1"),
            BulkDownloadRequest("survivor_2", "Survivor 2"),
        )

        manager.enqueueBatch("b_crash", "Crash Test", requests)

        // Wait until all tasks terminate
        var attempts = 0
        while (attempts++ < 100) {
            val active = manager.tasks.value.count { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Running }
            if (active == 0) break
            delay(50)
        }

        assertEquals(DownloadStatus.Failed, manager.tasks.value.find { it.videoId == "crash_1" }?.status)
        assertEquals(DownloadStatus.Failed, manager.tasks.value.find { it.videoId == "crash_2" }?.status)
        assertEquals(DownloadStatus.Failed, manager.tasks.value.find { it.videoId == "crash_3" }?.status)
        assertEquals(DownloadStatus.Done, manager.tasks.value.find { it.videoId == "survivor_1" }?.status)
        assertEquals(DownloadStatus.Done, manager.tasks.value.find { it.videoId == "survivor_2" }?.status)

        testScope.cancel()
    }

    @Test
    fun testConcurrency_cancelledWhileWaitingInQueue_neverExecutesRunner() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val runningTaskHold = CompletableDeferred<Unit>()
        val runnerInvocationCount = AtomicInteger(0)

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, _ ->
            runnerInvocationCount.incrementAndGet()
            if (url.contains("q_1") || url.contains("q_2")) {
                runningTaskHold.await()
            }
        }

        val requests = (1..10).map { i -> BulkDownloadRequest("q_$i", "Queued $i") }
        manager.enqueueBatch("b_queue_cancel", "Queue Cancel Batch", requests)

        // Wait until q_1 and q_2 are running
        var attempts = 0
        while (attempts++ < 50 && manager.tasks.value.count { it.status == DownloadStatus.Running } < 2) {
            delay(10)
        }

        assertEquals(2, manager.tasks.value.count { it.status == DownloadStatus.Running })

        // Now cancel the entire batch while tasks 3..10 are still waiting in the queue
        manager.cancelBatch("b_queue_cancel")

        // Release the running tasks
        runningTaskHold.complete(Unit)

        // Wait for all to finish
        attempts = 0
        while (attempts++ < 50 && manager.tasks.value.any { it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued }) {
            delay(10)
        }

        // Verify that runner was NEVER invoked for tasks 3..10!
        assertEquals("Runner should have been invoked exactly 2 times, never for cancelled queued tasks", 2, runnerInvocationCount.get())

        val tasks = manager.tasks.value.filter { it.batchId == "b_queue_cancel" }
        tasks.forEach { task ->
            assertEquals("Task ${task.title} should be Cancelled", DownloadStatus.Cancelled, task.status)
        }

        testScope.cancel()
    }

    // =========================================================================
    // Category 2: UI StateFlow Emission Throttling & Flooding Prevention
    // =========================================================================

    @Test
    fun testThrottling_rapidMicroProgressFlood_boundedEmissions() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val taskEmissions = Collections.synchronizedList(ArrayList<Float>())
        val downloadDone = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, onProgress ->
            // Rapidly fire 200 micro-updates (each 0.01% delta) in ~20ms
            for (i in 1..200) {
                onProgress(5.0f + (i * 0.01f), 120L, "[download] downloading")
            }
            delay(10)
            downloadDone.complete(Unit)
        }

        val collector = testScope.launch {
            manager.tasks.collect { tasks ->
                val p = tasks.firstOrNull()?.progress ?: return@collect
                taskEmissions.add(p)
            }
        }

        manager.download("https://youtu.be/flood_micro", AudioFormat.M4A)
        downloadDone.await()
        delay(30)
        collector.cancel()

        // Without throttling, 200 updates would emit 200 times.
        // With throttling (max 4/sec or >=1% delta), over a 2% total delta within 30ms:
        // Initial emission + delta jumps at ~6% and ~7% + final.
        // Task emissions must be strictly bounded (< 8).
        assertTrue("Emissions count (${taskEmissions.size}) must be strictly throttled (< 8) under 200 rapid callbacks", taskEmissions.size < 8)

        testScope.cancel()
    }

    @Test
    fun testThrottling_identicalProgressFlood_suppressedCompletely() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val emissionCount = AtomicInteger(0)
        val finishGate = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, onProgress ->
            // Fire 100 identical callbacks within 10ms
            for (i in 1..100) {
                onProgress(42.0f, 60L, "[download] 42.0% at 1.5MB/s ETA 01:00")
            }
            delay(10)
            finishGate.complete(Unit)
        }

        val collector = testScope.launch {
            manager.tasks.collect { tasks ->
                val task = tasks.firstOrNull() ?: return@collect
                if (task.progress in 41.9f..42.1f) {
                    emissionCount.incrementAndGet()
                }
            }
        }

        manager.download("https://youtu.be/identical_flood", AudioFormat.M4A)
        finishGate.await()
        delay(20)
        collector.cancel()

        // Exactly 1 emission for progress 42.0f should be received by listeners; the other 99 must be dropped!
        assertEquals("All identical progress updates must be suppressed", 1, emissionCount.get())

        testScope.cancel()
    }

    @Test
    fun testThrottling_batchStateFlow_neverFloodedByConcurrentProgress() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val batchProgressEmissions = Collections.synchronizedList(ArrayList<Float>())
        val task1Gate = CompletableDeferred<Unit>()
        val task2Gate = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, onProgress ->
            // Each task fires 50 rapid micro-updates within 15ms
            for (i in 1..50) {
                onProgress(10.0f + (i * 0.01f), 100L, "[download]")
            }
            if (url.contains("batch_concur_1")) task1Gate.complete(Unit)
            if (url.contains("batch_concur_2")) task2Gate.complete(Unit)
            delay(20)
        }

        val collector = testScope.launch {
            manager.observeBatch("b_concur_throttle").collect { batch ->
                batch?.let { batchProgressEmissions.add(it.progress) }
            }
        }

        manager.enqueueBatch("b_concur_throttle", "Throttled Batch", listOf(
            BulkDownloadRequest("batch_concur_1", "B1"),
            BulkDownloadRequest("batch_concur_2", "B2"),
        ))

        task1Gate.await()
        task2Gate.await()
        delay(50)
        collector.cancel()

        // 100 total callbacks across 2 concurrent tasks.
        // Batch progress emissions must be bounded (< 10), not flooding listeners with 100 emissions.
        assertTrue("Batch progress emissions (${batchProgressEmissions.size}) must be bounded (< 10)", batchProgressEmissions.size < 10)

        testScope.cancel()
    }

    @Test
    fun testThrottling_stageTransitionsAndDestination_bypassThrottlingImmediately() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val observedMessages = Collections.synchronizedList(ArrayList<String?>())
        val observedTitles = Collections.synchronizedList(ArrayList<String>())
        val finishGate = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, onProgress ->
            // Rapidly send progress, then immediately send stage lines within < 5ms
            onProgress(10f, 100L, "[download] 10%")
            delay(2)
            onProgress(10f, 100L, "[ExtractAudio] Destination: /music/Test Song [vid12345678].m4a")
            delay(2)
            onProgress(10f, 100L, "[EmbedThumbnail] ffmpeg embedding")
            delay(2)
            finishGate.complete(Unit)
        }

        val collector = testScope.launch {
            manager.tasks.collect { tasks ->
                val t = tasks.firstOrNull() ?: return@collect
                observedMessages.add(t.message)
                observedTitles.add(t.title)
            }
        }

        manager.download("https://youtu.be/stages", AudioFormat.M4A)
        finishGate.await()
        delay(20)
        collector.cancel()

        // Verify stage messages and title extraction bypass the 250ms throttling gate
        assertTrue("Should contain 'Ses dönüştürülüyor…'", observedMessages.contains("Ses dönüştürülüyor…"))
        assertTrue("Should contain 'Etiketler yazılıyor…'", observedMessages.contains("Etiketler yazılıyor…"))
        assertTrue("Title should be cleaned from destination line", observedTitles.contains("Test Song"))

        testScope.cancel()
    }

    @Test
    fun testThrottling_terminalStates_DoneAndFailed_neverDelayed() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val observedStatuses = Collections.synchronizedList(ArrayList<DownloadStatus>())
        val finishGate = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, onProgress ->
            // Fire progress at 99%, then immediately finish within 1ms
            onProgress(99.0f, 1L, "[download] 99%")
            delay(1)
            finishGate.complete(Unit)
        }

        val collector = testScope.launch {
            manager.tasks.collect { tasks ->
                tasks.firstOrNull()?.status?.let { observedStatuses.add(it) }
            }
        }

        manager.download("https://youtu.be/fast_done", AudioFormat.M4A)
        finishGate.await()

        // Wait up to 100ms for Done emission
        var attempts = 0
        while (attempts++ < 20 && !observedStatuses.contains(DownloadStatus.Done)) {
            delay(5)
        }

        collector.cancel()
        assertTrue("Done state must be emitted immediately without being throttled", observedStatuses.contains(DownloadStatus.Done))

        testScope.cancel()
    }

    @Test
    fun testContention_concurrentEnqueueBatchFromMultipleThreads() = runBlocking {
        val customThreadPool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val testScope = CoroutineScope(SupervisorJob() + customThreadPool)
        val lookup = TestSongLookup(setOf("pre_existing_1", "pre_existing_2"))

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            delay(10)
        }

        // 8 threads concurrently enqueuing batches with overlapping and pre-existing videoIds
        val jobs = (1..8).map { i ->
            testScope.async {
                val requests = listOf(
                    BulkDownloadRequest("pre_existing_1", "Pre 1"),
                    BulkDownloadRequest("shared_item", "Shared Item"),
                    BulkDownloadRequest("unique_$i", "Unique $i"),
                )
                manager.enqueueBatch("batch_thread_$i", "Batch Thread $i", requests)
            }
        }

        val results = jobs.awaitAll()
        assertEquals(8, results.size)

        // Across all 8 batches:
        // "pre_existing_1" must be skipped in all 8
        // "shared_item" must be enqueued in exactly 1 batch, and skipped in the other 7 due to in-flight deduplication
        // "unique_1".."unique_8" each enqueued in their respective batch
        val allTasks = manager.tasks.value
        val uniqueVideoIds = allTasks.mapNotNull { it.videoId }

        assertEquals("No duplicate videoIds should be in tasks list", uniqueVideoIds.size, uniqueVideoIds.toSet().size)
        assertTrue("shared_item should appear at most once", uniqueVideoIds.count { it == "shared_item" } <= 1)
        assertFalse("pre_existing_1 should never be in tasks", uniqueVideoIds.contains("pre_existing_1"))

        testScope.cancel()
        customThreadPool.close()
    }

    @Test
    fun testConcurrency_burstOf100SingleDownloads_strictlyThrottledToTwo() = runBlocking {
        val customThreadPool = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val testScope = CoroutineScope(SupervisorJob() + customThreadPool)
        val lookup = TestSongLookup()

        val activeCount = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val totalCompleted = AtomicInteger(0)
        val totalSingle = 100
        val latch = CountDownLatch(totalSingle)

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, _ ->
            val cur = activeCount.incrementAndGet()
            maxObserved.updateAndGet { prev -> maxOf(prev, cur) }
            try {
                delay((3L..12L).random())
                totalCompleted.incrementAndGet()
            } finally {
                activeCount.decrementAndGet()
                latch.countDown()
            }
        }

        // 10 coroutines dispatching 10 single downloads each concurrently
        val dispatchJobs = (1..10).map { batchIdx ->
            testScope.async {
                for (i in 1..10) {
                    manager.download("https://youtu.be/burst_${batchIdx}_$i", AudioFormat.M4A)
                }
            }
        }
        dispatchJobs.awaitAll()

        assertTrue("All 100 single downloads must complete within 20s", latch.await(20, TimeUnit.SECONDS))
        assertEquals(100, totalCompleted.get())
        assertEquals("Max concurrent downloads must strictly equal 2 under saturation", 2, maxObserved.get())

        testScope.cancel()
        customThreadPool.close()
    }

    @Test
    fun testThrottling_parallelTasksDoNotCrossContaminateThrottles() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val t1Observed = Collections.synchronizedList(ArrayList<Float>())
        val t2Observed = Collections.synchronizedList(ArrayList<Float>())
        val holdFinish = CompletableDeferred<Unit>()

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, url, _, onProgress ->
            if (url.contains("iso_1")) {
                // Task 1: progress updates
                onProgress(15f, 40L, "[download] 15%")
                delay(20)
                onProgress(35f, 30L, "[download] 35%")
                holdFinish.await()
            } else {
                // Task 2: progress updates interleaved
                delay(5)
                onProgress(20f, 50L, "[download] 20%")
                delay(20)
                onProgress(50f, 20L, "[download] 50%")
                holdFinish.await()
            }
        }

        val collector = testScope.launch {
            manager.tasks.collect { tasks ->
                tasks.find { it.videoId == "iso_1" }?.let { if (it.progress > 0) t1Observed.add(it.progress) }
                tasks.find { it.videoId == "iso_2" }?.let { if (it.progress > 0) t2Observed.add(it.progress) }
            }
        }

        manager.enqueueBatch("b_iso", "Isolation Batch", listOf(
            BulkDownloadRequest("iso_1", "Iso 1"),
            BulkDownloadRequest("iso_2", "Iso 2"),
        ))

        // Wait until both tasks have emitted their progress
        var attempts = 0
        while (attempts++ < 100) {
            val t1Has35 = manager.tasks.value.find { it.videoId == "iso_1" }?.progress == 35f
            val t2Has50 = manager.tasks.value.find { it.videoId == "iso_2" }?.progress == 50f
            if (t1Has35 && t2Has50) break
            delay(10)
        }

        // Verify that task 1 reached 35% and task 2 reached 50% concurrently without mutually suppressing each other
        val t1FinalProgress = manager.tasks.value.find { it.videoId == "iso_1" }?.progress
        val t2FinalProgress = manager.tasks.value.find { it.videoId == "iso_2" }?.progress

        assertEquals(35f, t1FinalProgress)
        assertEquals(50f, t2FinalProgress)
        assertTrue("Task 1 should have observed updates", t1Observed.isNotEmpty())
        assertTrue("Task 2 should have observed updates", t2Observed.isNotEmpty())

        holdFinish.complete(Unit)
        delay(30)
        collector.cancel()
        testScope.cancel()
    }

    @Test
    fun testThrottling_noMemoryLeakInThrottleState() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()
        val latch = CountDownLatch(20)

        val manager = YtDlpDownloadManager(null, lookup, testScope) { _, _, _, onProgress ->
            onProgress(10f, 100L, "[download] 10%")
            delay(5)
            onProgress(50f, 50L, "[download] 50%")
            delay(5)
            latch.countDown()
        }

        val requests = (1..20).map { i -> BulkDownloadRequest("leak_test_$i", "Leak Track $i") }
        manager.enqueueBatch("b_leak", "Leak Test Batch", requests)

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        delay(100)

        // Inspect private progressThrottles field via reflection
        val field = YtDlpDownloadManager::class.java.getDeclaredField("progressThrottles")
        field.isAccessible = true
        val throttlesMap = field.get(manager) as Map<*, *>

        assertEquals("progressThrottles map must be completely clean (size == 0) after all tasks finish", 0, throttlesMap.size)

        testScope.cancel()
    }

    @Test
    fun testConcurrency_cancellationDuringActiveProgressCallback() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lookup = TestSongLookup()

        val callbackStarted = CompletableDeferred<Unit>()
        val manager = YtDlpDownloadManager(null, lookup, testScope) { id, _, _, onProgress ->
            callbackStarted.complete(Unit)
            // Emit progress in tight loop until cancelled
            var p = 0f
            while (true) {
                p += 0.1f
                onProgress(p, 100L, "[download]")
                delay(2)
            }
        }

        manager.download("https://youtu.be/cancel_mid_progress", AudioFormat.M4A)
        callbackStarted.await()

        val task = manager.tasks.value.first()
        delay(15) // let a few progress updates fire
        manager.cancel(task.id)

        // Verify task settles to Cancelled
        var attempts = 0
        while (attempts++ < 50 && manager.tasks.value.first().status != DownloadStatus.Cancelled) {
            delay(10)
        }

        assertEquals(DownloadStatus.Cancelled, manager.tasks.value.first().status)

        // Verify subsequent download runs without hanging (permit was cleanly freed)
        val nextFinished = CompletableDeferred<Unit>()
        manager.downloadRunner = { _, _, _, _ ->
            nextFinished.complete(Unit)
        }
        manager.download("https://youtu.be/after_cancel", AudioFormat.M4A)
        nextFinished.await()

        testScope.cancel()
    }
}

