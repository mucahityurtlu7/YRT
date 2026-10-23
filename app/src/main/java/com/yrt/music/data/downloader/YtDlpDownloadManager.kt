package com.yrt.music.data.downloader

import android.content.Context
import android.os.Environment
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.BatchEnqueueResult
import com.yrt.music.data.model.BulkDownloadController
import com.yrt.music.data.model.BulkDownloadRequest
import com.yrt.music.data.model.DownloadBatch
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.data.repository.SongRepository
import com.yrt.music.service.DownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface EngineState {
    data object Initializing : EngineState
    data class Ready(val version: String?) : EngineState
    data object Updating : EngineState
    data class Error(val message: String) : EngineState
}

/**
 * Bulk-capable download engine wrapping youtubedl-android with throttled concurrency (Semaphore(2)),
 * progress rate-limiting, deduplication, and aggregate batch lifecycle management.
 */
class YtDlpDownloadManager(
    private val context: Context?,
    val songRepository: LocalSongLookup,
    private val scope: CoroutineScope,
    internal var downloadRunner: (suspend (taskId: String, url: String, format: AudioFormat, onProgress: (Float, Long, String) -> Unit) -> Unit)? = null,
) : BulkDownloadController {

    constructor(
        context: Context,
        songRepository: SongRepository,
        scope: CoroutineScope,
    ) : this(
        context = context as Context?,
        songRepository = songRepository as LocalSongLookup,
        scope = scope,
        downloadRunner = null,
    )

    private val ytdl get() = YoutubeDL.getInstance()
    private val prefs by lazy { context?.getSharedPreferences("ytdlp", Context.MODE_PRIVATE) }

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Initializing)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _batches = MutableStateFlow<List<DownloadBatch>>(emptyList())
    override val batches: StateFlow<List<DownloadBatch>> = _batches.asStateFlow()

    private val initDone = CompletableDeferred<Boolean>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val parallel = Semaphore(2)
    private val enqueueLock = Any()

    private class ProgressThrottleState(
        @Volatile var lastEmitTimeMs: Long = 0L,
        @Volatile var lastEmittedProgress: Float = -1f,
        @Volatile var lastEmittedStage: String? = null,
        @Volatile var lastEmittedEta: Long = -1L,
    )

    private val progressThrottles = ConcurrentHashMap<String, ProgressThrottleState>()

    fun initAsync() {
        scope.launch(Dispatchers.IO) {
            songRepository.refresh()
            val ctx = context
            if (ctx == null) {
                initDone.complete(true)
                _engineState.value = EngineState.Ready("mock-version")
                return@launch
            }
            try {
                ytdl.init(ctx)
                FFmpeg.getInstance().init(ctx)
                initDone.complete(true)
                _engineState.value = EngineState.Ready(ytdl.version(ctx))
                autoUpdateIfStale()
            } catch (e: Exception) {
                logError("yt-dlp init failed", e)
                initDone.complete(false)
                _engineState.value = EngineState.Error(e.message ?: "yt-dlp başlatılamadı")
            }
        }
    }

    /** Reuse the shared extractor initialization for playback without creating a download task. */
    suspend fun awaitReady() {
        check(initDone.await()) { "yt-dlp başlatılamadı" }
    }

    private suspend fun autoUpdateIfStale() {
        val p = prefs ?: return
        val last = p.getLong(KEY_LAST_UPDATE, 0L)
        if (System.currentTimeMillis() - last > 24 * 60 * 60 * 1000L) {
            updateYtDlp()
        }
    }

    suspend fun updateYtDlp(): String = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext "Android context bulunamadı"
        if (!initDone.await()) return@withContext "yt-dlp başlatılamadı"
        val before = _engineState.value
        _engineState.value = EngineState.Updating
        try {
            val status = ytdl.updateYoutubeDL(ctx)
            prefs?.edit()?.putLong(KEY_LAST_UPDATE, System.currentTimeMillis())?.apply()
            val version = ytdl.version(ctx)
            _engineState.value = EngineState.Ready(version)
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "yt-dlp güncellendi: $version"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp zaten güncel ($version)"
                else -> "Güncelleme durumu: $status"
            }
        } catch (e: Exception) {
            logError("update failed", e)
            _engineState.value = if (before is EngineState.Ready) before else EngineState.Ready(null)
            "Güncelleme başarısız: ${e.message}"
        }
    }

    /**
     * Legacy single-url download entry point.
     * Routes through the same throttled Semaphore(2) concurrency engine.
     */
    fun download(url: String, format: AudioFormat) {
        val id = UUID.randomUUID().toString()
        val task = DownloadTask(
            id = id,
            url = url,
            title = url,
            format = format,
            status = DownloadStatus.Queued,
        )
        _tasks.update { listOf(task) + it }
        launchTaskExecution(task)
        notifyKeepaliveService()
    }

    /**
     * Enqueues a bulk download batch with intra-batch deduplication, O(1) repository lookup,
     * in-flight task deduplication, and atomic aggregate batch creation.
     */
    override fun enqueueBatch(
        batchId: String,
        batchTitle: String,
        items: List<BulkDownloadRequest>,
        format: AudioFormat,
    ): BatchEnqueueResult = synchronized(enqueueLock) {
        val totalRequested = items.size
        if (items.isEmpty()) {
            val emptyBatch = DownloadBatch(
                id = batchId,
                title = batchTitle,
                totalCount = 0,
                progress = 100f,
                isCompleted = true,
                isCancelled = false,
            )
            _batches.update { list -> listOf(emptyBatch) + list.filterNot { it.id == batchId } }
            return BatchEnqueueResult(
                batchId = batchId,
                totalRequested = 0,
                enqueuedCount = 0,
                alreadyDownloadedCount = 0,
            )
        }

        // 1. Intra-batch deduplication by videoId
        val distinctItems = items.distinctBy { it.videoId }

        // 2. In-flight active task deduplication
        val activeVideoIds = _tasks.value
            .filter { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Running }
            .mapNotNull { it.videoId }
            .toSet()

        // 3. Partition into items to enqueue vs already downloaded / in-flight
        val toEnqueue = distinctItems.filter { item ->
            !songRepository.hasVideoId(item.videoId) && !activeVideoIds.contains(item.videoId)
        }

        val enqueuedCount = toEnqueue.size
        val alreadyDownloadedCount = totalRequested - enqueuedCount
        val isImmediatelyDone = enqueuedCount == 0

        // 4. Register batch state
        val batch = DownloadBatch(
            id = batchId,
            title = batchTitle,
            totalCount = enqueuedCount,
            queuedCount = enqueuedCount,
            progress = if (isImmediatelyDone) 100f else 0f,
            isCompleted = isImmediatelyDone,
            isCancelled = false,
        )
        _batches.update { list -> listOf(batch) + list.filterNot { it.id == batchId } }

        if (isImmediatelyDone) {
            return BatchEnqueueResult(
                batchId = batchId,
                totalRequested = totalRequested,
                enqueuedCount = 0,
                alreadyDownloadedCount = alreadyDownloadedCount,
            )
        }

        // 5. Create and enqueue tasks
        val newTasks = toEnqueue.map { item ->
            DownloadTask(
                id = UUID.randomUUID().toString(),
                url = "https://www.youtube.com/watch?v=${item.videoId}",
                title = item.title,
                format = format,
                status = DownloadStatus.Queued,
                batchId = batchId,
                videoId = item.videoId,
                artist = item.artist,
                album = item.album,
                thumbnailUrl = item.thumbnailUrl,
                durationMs = item.durationMs,
            )
        }

        _tasks.update { current -> newTasks + current }

        for (task in newTasks) {
            launchTaskExecution(task)
        }

        notifyKeepaliveService()

        return BatchEnqueueResult(
            batchId = batchId,
            totalRequested = totalRequested,
            enqueuedCount = enqueuedCount,
            alreadyDownloadedCount = alreadyDownloadedCount,
        )
    }

    override fun observeBatch(batchId: String): Flow<DownloadBatch?> {
        return _batches.map { list -> list.find { it.id == batchId } }.distinctUntilChanged()
    }

    override fun cancelBatch(batchId: String) {
        val existing = _batches.value.find { it.id == batchId } ?: return
        if (existing.isCompleted) return

        _batches.update { list ->
            list.map { b ->
                if (b.id == batchId) b.copy(isCancelled = true, isCompleted = false) else b
            }
        }

        val batchTasks = _tasks.value.filter {
            it.batchId == batchId && (it.status == DownloadStatus.Queued || it.status == DownloadStatus.Running)
        }

        for (task in batchTasks) {
            if (task.status == DownloadStatus.Running) {
                runCatching { ytdl.destroyProcessById(task.id) }
            }
            jobs.remove(task.id)?.cancel()
            progressThrottles.remove(task.id)
            updateTask(task.id) { it.copy(status = DownloadStatus.Cancelled, message = null) }
        }

        recalculateBatch(batchId)
        notifyKeepaliveService()
    }

    fun cancel(id: String) {
        runCatching { ytdl.destroyProcessById(id) }
        progressThrottles.remove(id)
        updateTask(id) { it.copy(status = DownloadStatus.Cancelled, message = null) }
        val task = _tasks.value.find { it.id == id }
        recalculateBatch(task?.batchId)
        jobs.remove(id)?.cancel()
        notifyKeepaliveService()
    }

    fun clearFinished() {
        _tasks.update { list ->
            list.filter { it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued }
        }
    }

    private fun launchTaskExecution(task: DownloadTask) {
        val id = task.id
        jobs[id] = scope.launch(Dispatchers.IO) {
            var permitAcquired = false
            try {
                if (downloadRunner == null) {
                    if (!initDone.await()) {
                        updateTask(id) { it.copy(status = DownloadStatus.Failed, message = "yt-dlp başlatılamadı") }
                        recalculateBatch(task.batchId)
                        return@launch
                    }
                }

                parallel.acquire()
                permitAcquired = true
                // Keep the permit until a terminal state is published, including failure/cancellation.
                currentCoroutineContext().ensureActive()
                val currentTask = _tasks.value.find { it.id == id }
                if (currentTask == null || currentTask.status == DownloadStatus.Cancelled) {
                    return@launch
                }
                val batchId = task.batchId
                if (batchId != null) {
                    val batch = _batches.value.find { it.id == batchId }
                    if (batch?.isCancelled == true) {
                        updateTask(id) { it.copy(status = DownloadStatus.Cancelled, message = null) }
                        recalculateBatch(batchId)
                        return@launch
                    }
                }

                updateTask(id) { it.copy(status = DownloadStatus.Running, message = "Bilgi alınıyor…") }
                recalculateBatch(batchId)

                val runner = downloadRunner
                if (runner != null) {
                    runner(id, task.url, task.format) { progress, eta, line ->
                        handleProgressCallback(id, batchId, progress, eta, line)
                    }
                } else {
                    val request = buildRequest(task.url, task.format)
                    ytdl.execute(request, id) { progress, eta, line ->
                        handleProgressCallback(id, batchId, progress, eta, line)
                    }
                }

                // If not cancelled, mark completed
                val finalTask = _tasks.value.find { it.id == id }
                if (finalTask?.status != DownloadStatus.Cancelled) {
                    progressThrottles.remove(id)
                    updateTask(id) { it.copy(status = DownloadStatus.Done, progress = 100f, message = null, etaSec = -1) }
                    recalculateBatch(task.batchId)
                    songRepository.refresh()
                }
            } catch (e: YoutubeDL.CanceledException) {
                progressThrottles.remove(id)
                updateTask(id) { it.copy(status = DownloadStatus.Cancelled, message = null) }
                recalculateBatch(task.batchId)
            } catch (e: CancellationException) {
                progressThrottles.remove(id)
                updateTask(id) { it.copy(status = DownloadStatus.Cancelled, message = null) }
                recalculateBatch(task.batchId)
                throw e
            } catch (e: Throwable) {
                progressThrottles.remove(id)
                if (_tasks.value.find { it.id == id }?.status == DownloadStatus.Cancelled) return@launch
                logError("download failed for task $id", e)
                val msg = e.message?.lineSequence()?.lastOrNull { it.contains("ERROR") }
                    ?: e.message?.take(300) ?: "Bilinmeyen hata"
                updateTask(id) { it.copy(status = DownloadStatus.Failed, message = msg) }
                recalculateBatch(task.batchId)
            } finally {
                if (permitAcquired) parallel.release()
                jobs.remove(id)
                progressThrottles.remove(id)
                recalculateBatch(task.batchId)
                notifyKeepaliveService()
            }
        }
    }

    private fun handleProgressCallback(
        id: String,
        batchId: String?,
        progress: Float,
        eta: Long,
        line: String,
    ) {
        val destination = DEST_REGEX.find(line)?.groupValues?.get(1)
        val stage = when {
            line.startsWith("[ExtractAudio]") -> "Ses dönüştürülüyor…"
            line.startsWith("[Metadata]") || line.startsWith("[EmbedThumbnail]") -> "Etiketler yazılıyor…"
            line.startsWith("[download]") -> null
            else -> null
        }

        val throttle = progressThrottles.getOrPut(id) { ProgressThrottleState() }
        val now = System.currentTimeMillis()

        val isStageChange = stage != null && stage != throttle.lastEmittedStage
        val isDestinationFound = destination != null
        val isTerminalProgress = progress >= 100f
        val timeDeltaMs = now - throttle.lastEmitTimeMs
        val progressDelta = if (progress >= 0f && throttle.lastEmittedProgress >= 0f) {
            kotlin.math.abs(progress - throttle.lastEmittedProgress)
        } else if (progress >= 0f) {
            progress
        } else 0f

        // Rate-limiting logic: max 4 updates/sec (>=250ms) or >=1% delta or stage/destination change
        val shouldEmit = throttle.lastEmitTimeMs == 0L ||
            isStageChange ||
            isDestinationFound ||
            isTerminalProgress ||
            progressDelta >= 1.0f ||
            (timeDeltaMs >= 250L && (progressDelta >= 0.1f || eta != throttle.lastEmittedEta))

        if (shouldEmit) {
            throttle.lastEmitTimeMs = now
            if (progress >= 0f) throttle.lastEmittedProgress = progress
            if (stage != null) throttle.lastEmittedStage = stage
            throttle.lastEmittedEta = eta

            updateTask(id) { t ->
                val validProgress = if (progress.isNaN() || progress < 0f) t.progress else progress
                t.copy(
                    progress = validProgress,
                    etaSec = eta,
                    title = destination?.let { SongRepository.cleanName(File(it).nameWithoutExtension) } ?: t.title,
                    message = stage ?: if (validProgress > 0) null else t.message,
                )
            }
            recalculateBatch(batchId)
        }
    }

    private fun recalculateBatch(batchId: String?) {
        if (batchId == null) return
        _batches.update { list ->
            list.map { batch ->
                if (batch.id != batchId) return@map batch
                val batchTasks = _tasks.value.filter { it.batchId == batchId }
                if (batchTasks.isEmpty()) {
                    return@map if (batch.totalCount == 0) {
                        batch.copy(isCompleted = true, progress = 100f)
                    } else {
                        batch
                    }
                }

                val total = batchTasks.size
                val completed = batchTasks.count { it.status == DownloadStatus.Done }
                val running = batchTasks.count { it.status == DownloadStatus.Running }
                val queued = batchTasks.count { it.status == DownloadStatus.Queued }
                val failed = batchTasks.count { it.status == DownloadStatus.Failed }
                val cancelled = batchTasks.count { it.status == DownloadStatus.Cancelled }

                val sumProgress = batchTasks.sumOf { task ->
                    when (task.status) {
                        DownloadStatus.Done -> 100.0
                        DownloadStatus.Running -> task.progress.toDouble().coerceIn(0.0, 100.0)
                        else -> 0.0
                    }
                }
                val aggregateProgress = if (total > 0) {
                    (sumProgress / total).toFloat().coerceIn(0f, 100f)
                } else 100f

                val isTerminal = (running == 0 && queued == 0)
                val isCancelled = batch.isCancelled || (cancelled == total && total > 0)

                batch.copy(
                    totalCount = total,
                    completedCount = completed,
                    runningCount = running,
                    queuedCount = queued,
                    failedCount = failed,
                    cancelledCount = cancelled,
                    progress = aggregateProgress,
                    isCompleted = isTerminal && !isCancelled,
                    isCancelled = isCancelled,
                )
            }
        }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun notifyKeepaliveService() {
        val ctx = context ?: return
        try {
            val hasActiveTasks = _tasks.value.any { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Running }
            if (hasActiveTasks) {
                DownloadService.start(ctx)
            }
        } catch (_: Throwable) {
            // JVM / headless execution guard
        }
    }

    private fun buildRequest(url: String, format: AudioFormat): YoutubeDLRequest {
        val ctx = context ?: throw IllegalStateException("Android Context required for native yt-dlp execution")
        val musicDir = (songRepository as? SongRepository)?.musicDir
            ?: ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(ctx.filesDir, "music")
        val outTemplate = File(musicDir, "%(title).150B [%(id)s].%(ext)s").absolutePath
        return YoutubeDLRequest(url).apply {
            when (format) {
                AudioFormat.M4A -> addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                AudioFormat.MP3 -> addOption("-f", "bestaudio/best")
            }
            addOption("-x")
            addOption("--audio-format", format.ext)
            addOption("--audio-quality", "0")
            addOption("--embed-metadata")
            addOption("--embed-thumbnail")
            addOption("--convert-thumbnails", "jpg")
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("-o", outTemplate)
            addOption("--cache-dir", File(ctx.cacheDir, "yt-dlp").absolutePath)
        }
    }

    companion object {
        private const val TAG = "YtDlpDownloadManager"
        private const val KEY_LAST_UPDATE = "last_update"
        private val DEST_REGEX = Regex("""Destination:\s+(.+)$""")

        private fun logError(message: String, throwable: Throwable? = null) {
            runCatching {
                if (throwable != null) Log.e(TAG, message, throwable)
                else Log.e(TAG, message)
            }
        }
    }
}
