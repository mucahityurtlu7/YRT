package com.yrt.music.data.playback

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Extracts stream metadata only; audio is read by the player's HTTP data source. */
class YtDlpStreamResolver internal constructor(
    private val extractor: StreamMetadataExtractor,
    private val awaitReady: (suspend () -> Unit)? = null,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RemoteStreamResolver {
    constructor(context: Context, awaitReady: (suspend () -> Unit)? = null) : this(
        extractor = YoutubeDlMetadataExtractor(context.applicationContext),
        awaitReady = awaitReady,
    )

    private data class CacheEntry(val stream: ResolvedAudioStream, val validUntilMs: Long)

    private val closed = AtomicBoolean(false)
    private val resolutions = ConcurrentHashMap.newKeySet<Job>()
    private val processIds = ConcurrentHashMap.newKeySet<String>()
    private val cache = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)

    override suspend fun resolve(videoId: String): ResolvedAudioStream = withContext(ioDispatcher) {
        require(VIDEO_ID.matches(videoId)) { "Geçersiz şarkı kimliği" }
        val resolution = currentCoroutineContext().job
        resolutions.add(resolution)
        var processId: String? = null
        try {
            ensureOpen()
            currentCoroutineContext().ensureActive()
            synchronized(cache) {
                cache[videoId]?.let { entry ->
                    if (entry.validUntilMs > nowEpochMs()) return@withContext entry.stream
                    cache.remove(videoId)
                }
            }

            if (awaitReady != null) awaitReady.invoke()
            else runInterruptible { extractor.initialize() }
            currentCoroutineContext().ensureActive()
            ensureOpen()

            val id = "playback-${UUID.randomUUID()}"
            processId = id
            processIds.add(id)
            // The library destroys its Process when join()/waitFor() is interrupted. This also
            // covers cancellation between starting the process and registering its process ID.
            val json = runInterruptible { extractor.extract(buildStreamRequest(videoId), id) }
            currentCoroutineContext().ensureActive()
            ensureOpen()
            val stream = StreamMetadataParser.parse(json)
            synchronized(cache) {
                cache[videoId] = CacheEntry(stream, cacheExpiry(stream.url, nowEpochMs()))
                while (cache.size > MAX_CACHED_STREAMS) cache.remove(cache.keys.first())
            }
            stream
        } catch (cancelled: YoutubeDL.CanceledException) {
            throw CancellationException("Şarkı hazırlama iptal edildi", cancelled)
        } finally {
            processId?.let { id ->
                runCatching { extractor.cancel(id) }
                processIds.remove(id)
            }
            resolutions.remove(resolution)
        }
    }

    override fun close() {
        closed.set(true)
        resolutions.forEach { it.cancel(CancellationException("Oynatıcı kapatıldı")) }
        processIds.forEach { runCatching { extractor.cancel(it) } }
        synchronized(cache) { cache.clear() }
    }

    private fun ensureOpen() {
        if (closed.get()) throw CancellationException("Oynatıcı kapatıldı")
    }

    internal fun cacheExpiry(url: String, nowMs: Long): Long {
        val expiresAtSeconds = runCatching {
            URI(url).rawQuery.orEmpty().split('&').firstNotNullOfOrNull { parameter ->
                val parts = parameter.split('=', limit = 2)
                if (parts.size == 2 && parts[0] == "expire") {
                    URLDecoder.decode(parts[1], "UTF-8").toLongOrNull()
                } else null
            }
        }.getOrNull()
        val maximum = nowMs + CACHE_TTL_MS
        val remoteExpiry = expiresAtSeconds?.takeIf { it in 1..(Long.MAX_VALUE / 1000) }
            ?.let { it * 1000 - EXPIRY_MARGIN_MS }
        return if (remoteExpiry == null) maximum else minOf(maximum, remoteExpiry)
    }

    companion object {
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private const val CACHE_TTL_MS = 5 * 60_000L
        private const val EXPIRY_MARGIN_MS = 30_000L
        private const val MAX_CACHED_STREAMS = 32

        internal fun buildStreamRequest(videoId: String): YoutubeDLRequest =
            YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                addOption("--format", "bestaudio[ext=m4a]/bestaudio/best")
                addOption("--dump-single-json")
                addOption("--skip-download")
                addOption("--no-playlist")
                addOption("--no-cache-dir")
                addOption("--no-warnings")
                addOption("--socket-timeout", 20)
                addOption("--retries", 1)
            }
    }
}

internal interface StreamMetadataExtractor {
    fun initialize()
    fun extract(request: YoutubeDLRequest, processId: String): String
    fun cancel(processId: String)
}

private class YoutubeDlMetadataExtractor(private val context: Context) : StreamMetadataExtractor {
    private val youtubeDl get() = YoutubeDL.getInstance()

    override fun initialize() = youtubeDl.init(context)

    override fun extract(request: YoutubeDLRequest, processId: String): String =
        youtubeDl.execute(request, processId).out

    override fun cancel(processId: String) {
        youtubeDl.destroyProcessById(processId)
    }
}
