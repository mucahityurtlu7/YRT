package com.yrt.music.data.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Local song entity scanned from device storage or downloaded from YouTube.
 * Extended with album metadata and extracted videoId for O(1) deduplication.
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val file: File,
    val artworkFile: File?,
    val lastModified: Long,
    val album: String? = null,
    val videoId: String? = null,
)

enum class AudioFormat(val ext: String, val label: String) {
    M4A("m4a", "M4A (hızlı)"),
    MP3("mp3", "MP3"),
}

enum class DownloadStatus { Queued, Running, Done, Failed, Cancelled }

/**
 * Download item tracking yt-dlp execution and progress.
 * Extended with batchId, videoId, and metadata for bulk download coordination.
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val format: AudioFormat,
    val progress: Float = 0f,
    val etaSec: Long = -1,
    val status: DownloadStatus = DownloadStatus.Queued,
    val message: String? = null,
    val batchId: String? = null,
    val videoId: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
)

/**
 * Represents an aggregate bulk download batch (e.g. artist top songs or an entire album).
 */
data class DownloadBatch(
    val id: String,
    val title: String,
    val totalCount: Int,
    val completedCount: Int = 0,
    val runningCount: Int = 0,
    val queuedCount: Int = 0,
    val failedCount: Int = 0,
    val cancelledCount: Int = 0,
    val progress: Float = 0f,
    val isCompleted: Boolean = false,
    val isCancelled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Input request item for bulk downloading.
 */
data class BulkDownloadRequest(
    val videoId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
)

/**
 * Result returned upon enqueueing a batch.
 */
data class BatchEnqueueResult(
    val batchId: String,
    val totalRequested: Int,
    val enqueuedCount: Int,
    val alreadyDownloadedCount: Int,
)

/**
 * Bulk download manager interface for queueing and observing batches.
 */
interface BulkDownloadController {
    fun enqueueBatch(
        batchId: String,
        batchTitle: String,
        items: List<BulkDownloadRequest>,
        format: AudioFormat = AudioFormat.M4A,
    ): BatchEnqueueResult

    fun observeBatch(batchId: String): Flow<DownloadBatch?>
    val batches: StateFlow<List<DownloadBatch>>
    fun cancelBatch(batchId: String)
}
