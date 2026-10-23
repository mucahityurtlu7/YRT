package com.yrt.music

import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.model.Song
import com.yrt.music.data.model.ArtistProfile
import com.yrt.music.data.model.AlbumDetails
import com.yrt.music.data.model.ArtistSearchResult
import com.yrt.music.data.model.AlbumSearchResult
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.toBulkDownloadRequest
import com.yrt.music.data.repository.SongRepository
import com.yrt.music.data.repository.YouTubeMusicRepository
import com.yrt.music.ui.downloader.DownloaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial empirical tests verifying:
 * 1. Thread safety of YouTubeMusicRepository caching under concurrent reads, writes, and evictions.
 * 2. Thread safety of SongRepository videoIdIndex under high-concurrency reads and writes.
 * 3. Atomic videoIdIndex refresh and absence of stale entries when files are deleted directly from disk.
 * 4. Non-regression of local playback models, single download tasks, and URL/cleaning utilities.
 */
class ConcurrencyAndRegressionStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // Category 1: SongRepository Disk Deletion & Atomic Index Refresh
    // =========================================================================

    @Test
    fun testDiskDeletion_refreshCleansStaleEntriesFromVideoIdIndexAtomically() = runBlocking {
        val musicDir = tempFolder.newFolder("music_deletion_test")
        val artDir = tempFolder.newFolder("art_deletion_test")
        val repo = SongRepository(musicDir = musicDir, artDir = artDir)

        // 1. Create 10 files with video IDs
        val files = (1..10).map { i ->
            val vId = String.format("%011d", i)
            val file = File(musicDir, "Track $i [$vId].mp3")
            file.writeBytes("dummy audio content $i".toByteArray())
            Pair(vId, file)
        }

        repo.refresh()

        // Verify initial state: all 10 video IDs exist
        assertEquals(10, repo.songs.value.size)
        files.forEach { (vId, _) ->
            assertTrue("Expected videoId $vId to exist initially", repo.hasVideoId(vId))
            assertNotNull(repo.getSongByVideoId(vId))
        }

        // 2. Directly delete odd-numbered files from DISK (bypassing repo.delete)
        val deletedFiles = files.filterIndexed { index, _ -> index % 2 == 0 }
        val remainingFiles = files.filterIndexed { index, _ -> index % 2 != 0 }

        deletedFiles.forEach { (_, file) ->
            assertTrue("File should be successfully deleted from disk", file.delete())
            assertFalse(file.exists())
        }

        // 3. Concurrently query index while refresh is executing
        val stopFlag = AtomicBoolean(false)
        val readCounter = AtomicInteger(0)
        val concurrentReader = launch(Dispatchers.Default) {
            while (!stopFlag.get()) {
                // Continuous reads must never crash or throw ConcurrentModificationException
                for ((vId, _) in files) {
                    repo.hasVideoId(vId)
                    repo.getSongByVideoId(vId)
                    readCounter.incrementAndGet()
                }
            }
        }

        // Trigger refresh
        repo.refresh()
        stopFlag.set(true)
        concurrentReader.join()

        // 4. Assert videoIdIndex and songs state post-refresh
        assertEquals(5, repo.songs.value.size)

        // Stale entries MUST be purged from videoIdIndex
        deletedFiles.forEach { (vId, _) ->
            assertFalse("Stale videoId $vId must not exist in videoIdIndex after refresh", repo.hasVideoId(vId))
            assertNull("Stale videoId $vId lookup must return null", repo.getSongByVideoId(vId))
        }

        // Remaining entries MUST be preserved
        remainingFiles.forEach { (vId, file) ->
            assertTrue("Remaining videoId $vId must exist in videoIdIndex", repo.hasVideoId(vId))
            val song = repo.getSongByVideoId(vId)
            assertNotNull(song)
            assertEquals(file.absolutePath, song?.file?.absolutePath)
        }

        assertTrue("Concurrent reader performed reads during refresh", readCounter.get() > 0)
    }

    // =========================================================================
    // Category 2: SongRepository High-Concurrency Reads & Writes
    // =========================================================================

    @Test
    fun testSongRepository_concurrentRefreshesAndReads_noCrashOrInconsistency() = runBlocking {
        val musicDir = tempFolder.newFolder("music_concurrent_test")
        val artDir = tempFolder.newFolder("art_concurrent_test")
        val repo = SongRepository(musicDir = musicDir, artDir = artDir)

        // Create 20 audio files
        val trackIds = (1..20).map { i ->
            val vId = String.format("VID%08d", i)
            File(musicDir, "Song $i [$vId].m4a").apply { writeBytes("audio$i".toByteArray()) }
            vId
        }

        repo.refresh()
        assertEquals(20, repo.songs.value.size)

        // Launch 20 concurrent coroutines executing refresh() and 20 querying hasVideoId() simultaneously
        val numWorkers = 20
        val coroutines = (1..numWorkers).map { workerId ->
            async(Dispatchers.IO) {
                if (workerId % 2 == 0) {
                    repo.refresh()
                } else {
                    for (vId in trackIds) {
                        assertTrue(repo.hasVideoId(vId))
                        assertNotNull(repo.getSongByVideoId(vId))
                    }
                }
            }
        }

        // Await all coroutines - no exception must be thrown
        coroutines.awaitAll()

        // Post-concurrency sanity verification
        assertEquals(20, repo.songs.value.size)
        for (vId in trackIds) {
            assertTrue(repo.hasVideoId(vId))
            assertNotNull(repo.getSongByVideoId(vId))
        }
    }

    @Test
    fun testSongRepository_duplicateVideoIds_preservesLatestFile() = runBlocking {
        val musicDir = tempFolder.newFolder("music_duplicates_test")
        val artDir = tempFolder.newFolder("art_duplicates_test")
        val repo = SongRepository(musicDir = musicDir, artDir = artDir)

        val vId = "DUP12345678"
        val oldFile = File(musicDir, "Track Old [$vId].mp3").apply {
            writeBytes("old".toByteArray())
            setLastModified(1000L)
        }
        val newFile = File(musicDir, "Track New [$vId].m4a").apply {
            writeBytes("new".toByteArray())
            setLastModified(5000L)
        }

        repo.refresh()

        assertTrue(repo.hasVideoId(vId))
        val resolvedSong = repo.getSongByVideoId(vId)
        assertNotNull(resolvedSong)
        assertEquals(newFile.absolutePath, resolvedSong?.file?.absolutePath)
        assertEquals(5000L, resolvedSong?.lastModified)
    }

    @Test
    fun testSongRepository_concurrentDeletesAndRefreshes() = runBlocking {
        val musicDir = tempFolder.newFolder("music_concurrent_delete")
        val artDir = tempFolder.newFolder("art_concurrent_delete")
        val repo = SongRepository(musicDir = musicDir, artDir = artDir)

        // Create 20 files
        (1..20).forEach { i ->
            val vId = String.format("DEL%08d", i)
            File(musicDir, "Track $i [$vId].mp3").apply { writeBytes("audio$i".toByteArray()) }
        }

        repo.refresh()
        assertEquals(20, repo.songs.value.size)

        // Half the songs will be deleted via repo.delete(song), while refreshes and reads run concurrently
        val songsToDelete = repo.songs.value.take(10)

        val deleteJobs = songsToDelete.map { song ->
            async(Dispatchers.IO) {
                repo.delete(song)
            }
        }

        val refreshJobs = (1..10).map {
            async(Dispatchers.IO) {
                repo.refresh()
            }
        }

        (deleteJobs + refreshJobs).awaitAll()

        // Sanity: Exactly 10 songs remain
        assertEquals(10, repo.songs.value.size)
        songsToDelete.forEach { song ->
            val vId = song.videoId ?: return@forEach
            assertFalse(repo.hasVideoId(vId))
            assertNull(repo.getSongByVideoId(vId))
        }
    }

    @Test
    fun testSongRepository_unicodeAndSpecialCharsInFilenames() = runBlocking {
        val musicDir = tempFolder.newFolder("music_unicode_test")
        val artDir = tempFolder.newFolder("art_unicode_test")
        val repo = SongRepository(musicDir = musicDir, artDir = artDir)

        val vId1 = "tr_12345678"
        val vId2 = "emo_1234567"
        val vId3 = "multi_12345"

        File(musicDir, "Şebnem Ferah - Sil Baştan [$vId1].opus").apply { writeBytes("tr".toByteArray()) }
        File(musicDir, "🎧 Pop Track 🎵 [$vId2].flac").apply { writeBytes("emo".toByteArray()) }
        File(musicDir, "Band [Album] [Remix 2024] [Live] [$vId3].m4a").apply { writeBytes("multi".toByteArray()) }

        repo.refresh()

        assertEquals(3, repo.songs.value.size)
        assertTrue(repo.hasVideoId(vId1))
        assertTrue(repo.hasVideoId(vId2))
        assertTrue(repo.hasVideoId(vId3))

        val song1 = repo.getSongByVideoId(vId1)
        assertEquals("Şebnem Ferah - Sil Baştan", song1?.title)

        val song3 = repo.getSongByVideoId(vId3)
        assertEquals("Band [Album] [Remix 2024] [Live]", song3?.title)
    }

    // =========================================================================
    // Category 3: YouTubeMusicRepository Cache Thread Safety & Eviction
    // =========================================================================

    @Test
    fun testYouTubeMusicRepository_blankQueriesAndValidation() = runBlocking {
        val repo = YouTubeMusicRepository()

        // Blank query must return empty list without hitting network or throwing
        val emptyArtistResult = repo.searchArtists("   ")
        assertTrue(emptyArtistResult.isSuccess)
        assertTrue(emptyArtistResult.getOrNull()?.isEmpty() == true)

        val emptyAlbumResult = repo.searchAlbums("")
        assertTrue(emptyAlbumResult.isSuccess)
        assertTrue(emptyAlbumResult.getOrNull()?.isEmpty() == true)

        // Blank browseId must return failure with IllegalArgumentException
        val invalidArtist = repo.getArtistProfile("   ")
        assertTrue(invalidArtist.isFailure)
        assertTrue(invalidArtist.exceptionOrNull() is IllegalArgumentException)

        val invalidAlbum = repo.getAlbumDetails("")
        assertTrue(invalidAlbum.isFailure)
        assertTrue(invalidAlbum.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun testYouTubeMusicRepository_cacheConcurrencyAndEviction() = runBlocking {
        val repo = YouTubeMusicRepository()

        // Access internal caches via reflection to seed known values
        val artistProfileCacheField = YouTubeMusicRepository::class.java.getDeclaredField("artistProfileCache").apply {
            isAccessible = true
        }
        val albumDetailsCacheField = YouTubeMusicRepository::class.java.getDeclaredField("albumDetailsCache").apply {
            isAccessible = true
        }
        val artistSearchCacheField = YouTubeMusicRepository::class.java.getDeclaredField("artistSearchCache").apply {
            isAccessible = true
        }
        val albumSearchCacheField = YouTubeMusicRepository::class.java.getDeclaredField("albumSearchCache").apply {
            isAccessible = true
        }

        @Suppress("UNCHECKED_CAST")
        val artistProfileCache = artistProfileCacheField.get(repo) as ConcurrentHashMap<String, ArtistProfile>
        @Suppress("UNCHECKED_CAST")
        val albumDetailsCache = albumDetailsCacheField.get(repo) as ConcurrentHashMap<String, AlbumDetails>
        @Suppress("UNCHECKED_CAST")
        val artistSearchCache = artistSearchCacheField.get(repo) as ConcurrentHashMap<String, List<ArtistSearchResult>>
        @Suppress("UNCHECKED_CAST")
        val albumSearchCache = albumSearchCacheField.get(repo) as ConcurrentHashMap<String, List<AlbumSearchResult>>

        // Seed caches
        val sampleProfile = ArtistProfile(
            browseId = "UC1234567890",
            name = "Test Artist",
            subscribers = "1M",
            description = "Bio",
            heroArtworkUrl = null,
            topSongs = emptyList(),
            albums = emptyList(),
        )
        artistProfileCache["UC1234567890"] = sampleProfile

        val sampleAlbum = AlbumDetails(
            browseId = "MPRE12345678",
            title = "Test Album",
            artist = "Test Artist",
            year = "2024",
            artworkUrl = null,
            tracks = emptyList(),
        )
        albumDetailsCache["MPRE12345678"] = sampleAlbum

        val sampleArtistSearch = listOf(ArtistSearchResult(browseId = "UC1234567890", name = "Test Artist", thumbnailUrl = null, subtitle = null))
        artistSearchCache["test artist"] = sampleArtistSearch

        val sampleAlbumSearch = listOf(AlbumSearchResult(browseId = "MPRE12345678", title = "Test Album", artist = "Test Artist", year = "2024", artworkUrl = null))
        albumSearchCache["test album"] = sampleAlbumSearch

        // Verify cached reads return immediately without network
        val profileRes = repo.getArtistProfile("UC1234567890")
        assertTrue(profileRes.isSuccess)
        assertEquals("Test Artist", profileRes.getOrNull()?.name)

        val albumRes = repo.getAlbumDetails("MPRE12345678")
        assertTrue(albumRes.isSuccess)
        assertEquals("Test Album", albumRes.getOrNull()?.title)

        val artistSearchRes = repo.searchArtists("Test Artist")
        assertTrue(artistSearchRes.isSuccess)
        assertEquals(1, artistSearchRes.getOrNull()?.size)

        val albumSearchRes = repo.searchAlbums("Test Album")
        assertTrue(albumSearchRes.isSuccess)
        assertEquals(1, albumSearchRes.getOrNull()?.size)

        // Stress test: 30 concurrent coroutines reading caches while clearing caches in parallel
        val stopFlag = AtomicBoolean(false)
        val readSuccesses = AtomicInteger(0)

        val readers = (1..30).map {
            async(Dispatchers.Default) {
                while (!stopFlag.get()) {
                    repo.searchArtists("   ") // blank fast-path
                    val p = repo.getArtistProfile("UC1234567890")
                    if (p.isSuccess) readSuccesses.incrementAndGet()
                    val a = repo.getAlbumDetails("MPRE12345678")
                    if (a.isSuccess) readSuccesses.incrementAndGet()
                }
            }
        }

        // Cache clearer running in parallel
        val clearer = launch(Dispatchers.Default) {
            repeat(10) {
                delay(5)
                repo.clearCache()
                // Re-seed to simulate concurrent write/read cycles
                artistProfileCache["UC1234567890"] = sampleProfile
                albumDetailsCache["MPRE12345678"] = sampleAlbum
            }
        }

        clearer.join()
        stopFlag.set(true)
        readers.awaitAll()

        assertTrue(readSuccesses.get() > 0)

        // Clear cache and verify caches are empty
        repo.clearCache()
        assertTrue(artistProfileCache.isEmpty())
        assertTrue(albumDetailsCache.isEmpty())
        assertTrue(artistSearchCache.isEmpty())
        assertTrue(albumSearchCache.isEmpty())
    }

    // =========================================================================
    // Category 4: Baseline Playback & Single Download Non-Regression
    // =========================================================================

    @Test
    fun testSongModel_backwardCompatibilityAndImmutability() {
        val dummyFile = File("/storage/emulated/0/Music/track.mp3")

        // 1. Instantiation with only pre-M1 fields (album & videoId default to null)
        val legacySong = Song(
            id = dummyFile.absolutePath,
            title = "Legacy Track",
            artist = "Legacy Artist",
            durationMs = 180_000L,
            file = dummyFile,
            artworkFile = null,
            lastModified = 1700000000L,
        )

        assertNull(legacySong.album)
        assertNull(legacySong.videoId)
        assertEquals("Legacy Track", legacySong.title)
        assertEquals(180_000L, legacySong.durationMs)

        // 2. Instantiation with new M1 fields
        val modernSong = legacySong.copy(
            album = "Discovery",
            videoId = "5NV6Rdv1a3I",
        )
        assertEquals("Discovery", modernSong.album)
        assertEquals("5NV6Rdv1a3I", modernSong.videoId)
        assertEquals(legacySong.id, modernSong.id)

        // 3. Equality semantics
        val identicalSong = Song(
            id = dummyFile.absolutePath,
            title = "Legacy Track",
            artist = "Legacy Artist",
            durationMs = 180_000L,
            file = dummyFile,
            artworkFile = null,
            lastModified = 1700000000L,
        )
        assertEquals(legacySong, identicalSong)
    }

    @Test
    fun testDownloadTask_singleDownloadTaskDefaultsAndImmutability() {
        // 1. Single download task as created by YtDlpDownloadManager.download(url, format)
        val task = DownloadTask(
            id = "task-uuid-1",
            url = "https://youtube.com/watch?v=5NV6Rdv1a3I",
            title = "https://youtube.com/watch?v=5NV6Rdv1a3I",
            format = AudioFormat.M4A,
        )

        // Verify pre-existing defaults are intact
        assertEquals(0f, task.progress, 0.001f)
        assertEquals(-1L, task.etaSec)
        assertEquals(DownloadStatus.Queued, task.status)
        assertNull(task.message)

        // Verify M1 batch-tracking extensions default to null
        assertNull(task.batchId)
        assertNull(task.videoId)
        assertNull(task.artist)
        assertNull(task.album)
        assertNull(task.thumbnailUrl)
        assertNull(task.durationMs)

        // 2. Lifecycle status transitions
        val runningTask = task.copy(status = DownloadStatus.Running, progress = 45.5f, message = "Downloading...")
        assertEquals(DownloadStatus.Running, runningTask.status)
        assertEquals(45.5f, runningTask.progress, 0.001f)
        assertEquals("Downloading...", runningTask.message)

        val completedTask = runningTask.copy(status = DownloadStatus.Done, progress = 100f, message = null)
        assertEquals(DownloadStatus.Done, completedTask.status)
        assertEquals(100f, completedTask.progress, 0.001f)
        assertNull(completedTask.message)
    }

    @Test
    fun testDownloaderViewModel_urlExtractionAndCleaningNonRegression() {
        // Extract URL from various share strings and direct links
        assertEquals(
            "https://youtu.be/5NV6Rdv1a3I",
            DownloaderViewModel.extractUrl("Check out this song: https://youtu.be/5NV6Rdv1a3I"),
        )
        assertEquals(
            "http://music.youtube.com/watch?v=12345",
            DownloaderViewModel.extractUrl("http://music.youtube.com/watch?v=12345"),
        )
        assertNull(DownloaderViewModel.extractUrl("No url here"))
        assertNull(DownloaderViewModel.extractUrl(""))

        // Audio format extensions and labels
        assertEquals("m4a", AudioFormat.M4A.ext)
        assertEquals("mp3", AudioFormat.MP3.ext)
        assertEquals("M4A (hızlı)", AudioFormat.M4A.label)
        assertEquals("MP3", AudioFormat.MP3.label)

        // Clean name utility preserves bracketed terms with < 6 chars and removes yt-dlp 11-char IDs
        assertEquals("Track", SongRepository.cleanName("Track [5NV6Rdv1a3I]"))
        assertEquals("Track [Remix]", SongRepository.cleanName("Track [Remix]"))
        assertEquals("Track", SongRepository.cleanName("Track"))
    }

    @Test
    fun testRemoteTrack_toBulkDownloadRequest_mapping() {
        val track = RemoteTrack(
            videoId = "5NV6Rdv1a3I",
            title = "Get Lucky",
            artist = null, // fallback to default
            album = null, // fallback to default
            durationMs = 248_000L,
            durationText = "4:08",
            thumbnailUrl = "https://lh3.googleusercontent.com/track.jpg",
            index = 1,
        )

        val req = track.toBulkDownloadRequest(defaultArtist = "Daft Punk", defaultAlbum = "Random Access Memories")
        assertEquals("5NV6Rdv1a3I", req.videoId)
        assertEquals("Get Lucky", req.title)
        assertEquals("Daft Punk", req.artist)
        assertEquals("Random Access Memories", req.album)
        assertEquals("https://lh3.googleusercontent.com/track.jpg", req.thumbnailUrl)
        assertEquals(248_000L, req.durationMs)
    }
}
