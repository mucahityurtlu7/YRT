package com.yrt.music

import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yrt.music.data.playback.StreamMetadataExtractor
import com.yrt.music.data.playback.StreamMetadataParser
import com.yrt.music.data.playback.YtDlpStreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RemoteStreamResolverTest {
    @Test
    fun selectedAudioUrlAndItsRequiredHeadersArePreserved() {
        val result = StreamMetadataParser.parse(
            """{
              "url":"https://media.example/audio?token=one&expire=2000000000",
              "ext":"m4a", "acodec":"mp4a.40.2", "vcodec":"none",
              "http_headers":{"User-Agent":"YouTube Client","Referer":"https://youtube.com/"}
            }""",
        )

        assertEquals("https://media.example/audio?token=one&expire=2000000000", result.url)
        assertEquals("audio/mp4", result.mimeType)
        assertEquals("YouTube Client", result.httpHeaders["User-Agent"])
        assertEquals("https://youtube.com/", result.httpHeaders["Referer"])
    }

    @Test
    fun requestedAudioMergesHeadersAndOverridesThemCaseInsensitively() {
        val result = StreamMetadataParser.parse(
            """{
              "http_headers":{"User-Agent":"default","Referer":"https://youtube.com/"},
              "requested_formats":[
                {"url":"https://media.example/video","acodec":"none","vcodec":"avc1"},
                {"url":"https://media.example/audio","acodec":"opus","vcodec":"none",
                 "ext":"webm","http_headers":{"user-agent":"audio-client"}}
              ]
            }""",
        )

        assertEquals("https://media.example/audio", result.url)
        assertEquals(2, result.httpHeaders.size)
        assertEquals("audio-client", result.httpHeaders["user-agent"])
        assertEquals("https://youtube.com/", result.httpHeaders["Referer"])
        assertEquals("audio/webm", result.mimeType)
    }

    @Test
    fun fallbackPrefersAudioOnlyAndThenTheHighestAudioBitrate() {
        val result = StreamMetadataParser.parse(
            """{"formats":[
              {"url":"https://media.example/video","vcodec":"avc1","acodec":"aac","tbr":2000},
              {"url":"https://media.example/low","vcodec":"none","acodec":"opus","abr":64},
              {"url":"https://media.example/high","vcodec":"none","acodec":"opus","abr":160}
            ]}""",
        )
        assertEquals("https://media.example/high", result.url)
    }

    @Test
    fun localFilesAndVideoOnlyFormatsCannotBecomeRemoteAudioStreams() {
        assertThrows(IllegalStateException::class.java) {
            StreamMetadataParser.parse(
                """{"formats":[
                  {"url":"file:///storage/Music/audio.m4a","acodec":"aac"},
                  {"url":"https://media.example/video","acodec":"none"}
                ]}""",
            )
        }
    }

    @Test
    fun requestOnlyExtractsMetadataAndDisablesPersistentCache() {
        val request = YtDlpStreamResolver.buildStreamRequest("song_123")
        assertTrue(request.hasOption("--skip-download"))
        assertTrue(request.hasOption("--dump-single-json"))
        assertTrue(request.hasOption("--no-cache-dir"))
        assertTrue(request.hasOption("--no-playlist"))
        assertFalse(request.hasOption("--extract-audio"))
        assertFalse(request.hasOption("--output"))
        assertFalse(request.hasOption("--write-info-json"))
        assertEquals("bestaudio[ext=m4a]/bestaudio/best", request.getOption("--format"))
        assertTrue(request.buildCommand().contains("https://www.youtube.com/watch?v=song_123"))
    }

    @Test
    fun reopenReusesStreamInMemoryAndRefreshesAfterFiveMinutes() = runBlocking {
        var nowMs = 1_000_000L
        val extractor = FakeExtractor()
        val resolver = YtDlpStreamResolver(extractor, nowEpochMs = { nowMs })
        try {
            assertEquals(resolver.resolve("song"), resolver.resolve("song"))
            assertEquals(1, extractor.extractions.get())
            nowMs += 5 * 60_000L
            resolver.resolve("song")
            assertEquals(2, extractor.extractions.get())
        } finally {
            resolver.close()
        }
    }

    @Test
    fun cacheRefreshesThirtySecondsBeforeSignedUrlExpires() = runBlocking {
        var nowMs = 1_000_000L
        val extractor = FakeExtractor("""{"url":"https://media.example/audio?expire=1100"}""")
        val resolver = YtDlpStreamResolver(extractor, nowEpochMs = { nowMs })
        try {
            resolver.resolve("song")
            nowMs = 1_069_999
            resolver.resolve("song")
            assertEquals(1, extractor.extractions.get())
            nowMs = 1_070_000
            resolver.resolve("song")
            assertEquals(2, extractor.extractions.get())
        } finally {
            resolver.close()
        }
    }

    @Test
    fun readinessCallbackReusesTheExistingEngineInitialization() = runBlocking {
        var readinessCalls = 0
        val extractor = FakeExtractor()
        val resolver = YtDlpStreamResolver(extractor, awaitReady = { readinessCalls++ })
        try {
            resolver.resolve("song")
            assertEquals(1, readinessCalls)
            assertEquals(0, extractor.initializations.get())
        } finally {
            resolver.close()
        }
    }

    @Test
    fun failedExtractionIsNotCached() = runBlocking {
        val extractor = FakeExtractor("""{"formats":[]}""")
        val resolver = YtDlpStreamResolver(extractor)
        try {
            repeat(2) {
                try {
                    resolver.resolve("song")
                    throw AssertionError("Expected missing audio error")
                } catch (_: IllegalStateException) {
                    // A fresh resolution must still be attempted next time.
                }
            }
            assertEquals(2, extractor.extractions.get())
            assertEquals(2, extractor.cancellations.get())
        } finally {
            resolver.close()
        }
    }

    @Test
    fun cancellationInterruptsNativeWorkAndCleansUpItsProcess() = runBlocking {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val extractor = object : FakeExtractor() {
            override fun extract(request: YoutubeDLRequest, processId: String): String {
                started.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (error: InterruptedException) {
                    interrupted.countDown()
                    throw error
                }
                throw AssertionError("Blocking extraction unexpectedly returned")
            }
        }
        val resolver = YtDlpStreamResolver(extractor)
        try {
            val pending = async(Dispatchers.Default) { resolver.resolve("song") }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            pending.cancel()
            withTimeout(3_000) { pending.join() }
            assertTrue(interrupted.await(3, TimeUnit.SECONDS))
            assertEquals(1, extractor.cancellations.get())
        } finally {
            resolver.close()
        }
    }

    @Test
    fun closingResolverCancelsActiveResolutionAndRejectsFurtherRequests() = runBlocking {
        val started = CountDownLatch(1)
        val extractor = object : FakeExtractor() {
            override fun extract(request: YoutubeDLRequest, processId: String): String {
                started.countDown()
                CountDownLatch(1).await()
                throw AssertionError("Blocking extraction unexpectedly returned")
            }
        }
        val resolver = YtDlpStreamResolver(extractor)
        val pending = async(Dispatchers.Default) { resolver.resolve("song") }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        resolver.close()
        withTimeout(3_000) { pending.join() }
        assertTrue(pending.isCancelled)
        try {
            resolver.resolve("song")
            throw AssertionError("A closed resolver accepted new work")
        } catch (_: CancellationException) {
            // Closing owns the resolution child job, and leaves this caller alive.
        }
        assertTrue(extractor.cancellations.get() >= 1)
    }

    private open class FakeExtractor(
        private val json: String = """{"url":"https://media.example/audio","ext":"m4a"}""",
    ) : StreamMetadataExtractor {
        val initializations = AtomicInteger()
        val extractions = AtomicInteger()
        val cancellations = AtomicInteger()

        override fun initialize() {
            initializations.incrementAndGet()
        }

        override fun extract(request: YoutubeDLRequest, processId: String): String {
            extractions.incrementAndGet()
            return json
        }

        override fun cancel(processId: String) {
            cancellations.incrementAndGet()
        }
    }
}
