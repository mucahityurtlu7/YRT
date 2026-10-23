package com.yrt.music

import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.SongRepository
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

/**
 * Unit tests verifying videoId extraction, filename cleaning, album metadata,
 * and O(1) deduplication lookup in SongRepository.
 */
class SongRepositoryIndexingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // Category 1: Video ID Regex Extraction
    // =========================================================================

    @Test
    fun testExtractVideoId_validStandard11Chars() {
        val filename = "Daft Punk - Get Lucky [5NV6Rdv1a3I]"
        val videoId = SongRepository.extractVideoId(filename)
        assertEquals("5NV6Rdv1a3I", videoId)
    }

    @Test
    fun testExtractVideoId_withHyphensAndUnderscores() {
        val filename = "Radiohead - Creep [a-B_1234567]"
        val videoId = SongRepository.extractVideoId(filename)
        assertEquals("a-B_1234567", videoId)
    }

    @Test
    fun testExtractVideoId_pureNumericOrSymbols() {
        val filename = "Track [12345678901]"
        assertEquals("12345678901", SongRepository.extractVideoId(filename))

        val filename2 = "Track [-_________-]"
        assertEquals("-_________-", SongRepository.extractVideoId(filename2))
    }

    @Test
    fun testExtractVideoId_multipleBracketedSections_matchesTrailingId() {
        val filename = "Daft Punk [Official Audio] [Live 2007] [5NV6Rdv1a3I]"
        val videoId = SongRepository.extractVideoId(filename)
        assertEquals("5NV6Rdv1a3I", videoId)
    }

    @Test
    fun testExtractVideoId_withTrailingWhitespace_isHandled() {
        val filename = "Track [5NV6Rdv1a3I]   "
        val videoId = SongRepository.extractVideoId(filename)
        assertEquals("5NV6Rdv1a3I", videoId)
    }

    @Test
    fun testExtractVideoId_invalidLength_returnsNull() {
        // 10 chars (too short)
        assertNull(SongRepository.extractVideoId("Track [1234567890]"))
        // 12 chars (too long)
        assertNull(SongRepository.extractVideoId("Track [123456789012]"))
        // 5 chars
        assertNull(SongRepository.extractVideoId("Track [Remix]"))
        // Empty brackets
        assertNull(SongRepository.extractVideoId("Track []"))
    }

    @Test
    fun testExtractVideoId_invalidCharacters_returnsNull() {
        // Contains space
        assertNull(SongRepository.extractVideoId("Track [12345 67890]"))
        // Contains exclamation mark
        assertNull(SongRepository.extractVideoId("Track [1234567890!]"))
        // Contains dot
        assertNull(SongRepository.extractVideoId("Track [12345678.01]"))
    }

    @Test
    fun testExtractVideoId_noBrackets_returnsNull() {
        assertNull(SongRepository.extractVideoId("Daft Punk - Get Lucky"))
        assertNull(SongRepository.extractVideoId(""))
    }

    // =========================================================================
    // Category 2: Filename Cleaning
    // =========================================================================

    @Test
    fun testCleanName_removesTrailingVideoId() {
        val raw = "Daft Punk - Get Lucky [5NV6Rdv1a3I]"
        assertEquals("Daft Punk - Get Lucky", SongRepository.cleanName(raw))
    }

    @Test
    fun testCleanName_preservesShortBracketedText() {
        // [Remix] is only 5 chars, regex strips >= 6 chars
        val raw = "Song Title [Remix]"
        assertEquals("Song Title [Remix]", SongRepository.cleanName(raw))
    }

    @Test
    fun testCleanName_leavesPlainTitleUntouched() {
        val raw = "Bohemian Rhapsody"
        assertEquals("Bohemian Rhapsody", SongRepository.cleanName(raw))
    }

    // =========================================================================
    // Category 3: Local Song Indexing & O(1) Deduplication Lookup
    // =========================================================================

    @Test
    fun testRepository_emptyDirectory_hasNoSongsOrVideoIds() = runBlocking {
        val musicDir = tempFolder.newFolder("music")
        val artDir = tempFolder.newFolder("artwork")
        val repo = SongRepository(musicDir = musicDir, artDir = artDir)

        repo.refresh()

        assertTrue(repo.songs.value.isEmpty())
        assertFalse(repo.hasVideoId("5NV6Rdv1a3I"))
        assertNull(repo.getSongByVideoId("5NV6Rdv1a3I"))
    }

    @Test
    fun testRepository_indexesFilesWithExtractedVideoIds() = runBlocking {
        val musicDir = tempFolder.newFolder("music2")
        val artDir = tempFolder.newFolder("artwork2")
        val repo = SongRepository(musicDir = musicDir, artDir = artDir)

        // Create mock audio files in musicDir
        File(musicDir, "Daft Punk - Get Lucky [5NV6Rdv1a3I].m4a").createNewFile()
        File(musicDir, "Radiohead - Creep [UCr12345678].mp3").createNewFile()
        File(musicDir, "Offline Local Track without id.flac").createNewFile()
        File(musicDir, "IgnoredNonAudio.txt").createNewFile()

        repo.refresh()

        val songs = repo.songs.value
        assertEquals(3, songs.size)

        // Verify O(1) lookup by videoId
        assertTrue(repo.hasVideoId("5NV6Rdv1a3I"))
        assertTrue(repo.hasVideoId("UCr12345678"))
        assertFalse(repo.hasVideoId("nonExistentVideoId"))

        val song1 = repo.getSongByVideoId("5NV6Rdv1a3I")
        assertNotNull(song1)
        assertEquals("Daft Punk - Get Lucky", song1?.title)
        assertEquals("5NV6Rdv1a3I", song1?.videoId)

        val song2 = repo.getSongByVideoId("UCr12345678")
        assertNotNull(song2)
        assertEquals("Radiohead - Creep", song2?.title)
        assertEquals("UCr12345678", song2?.videoId)

        assertNull(repo.getSongByVideoId("nonExistentVideoId"))
    }

    @Test
    fun testRepository_deleteSong_updatesVideoIdIndex() = runBlocking {
        val musicDir = tempFolder.newFolder("music3")
        val artDir = tempFolder.newFolder("artwork3")
        val repo = SongRepository(musicDir = musicDir, artDir = artDir)

        val file1 = File(musicDir, "Track One [11111111111].mp3").apply { createNewFile() }
        val file2 = File(musicDir, "Track Two [22222222222].mp3").apply { createNewFile() }

        repo.refresh()
        assertEquals(2, repo.songs.value.size)
        assertTrue(repo.hasVideoId("11111111111"))
        assertTrue(repo.hasVideoId("22222222222"))

        val songToDelete = repo.getSongByVideoId("11111111111")
        assertNotNull(songToDelete)

        repo.delete(songToDelete!!)

        // Verify file deleted and index updated
        assertFalse(file1.exists())
        assertEquals(1, repo.songs.value.size)
        assertFalse(repo.hasVideoId("11111111111"))
        assertNull(repo.getSongByVideoId("11111111111"))
        assertTrue(repo.hasVideoId("22222222222"))
    }

    // =========================================================================
    // Category 4: Song Model Album & VideoId Fields
    // =========================================================================

    @Test
    fun testSongModel_albumAndVideoIdDefaultValues() {
        val dummyFile = File("test.mp3")
        val song = Song(
            id = "test.mp3",
            title = "Test Title",
            artist = "Test Artist",
            durationMs = 120000L,
            file = dummyFile,
            artworkFile = null,
            lastModified = 1000L,
        )

        assertNull(song.album)
        assertNull(song.videoId)
    }

    @Test
    fun testSongModel_withAlbumAndVideoId() {
        val dummyFile = File("test.mp3")
        val song = Song(
            id = "test.mp3",
            title = "Get Lucky",
            artist = "Daft Punk",
            durationMs = 248000L,
            file = dummyFile,
            artworkFile = null,
            lastModified = 2000L,
            album = "Random Access Memories",
            videoId = "5NV6Rdv1a3I",
        )

        assertEquals("Random Access Memories", song.album)
        assertEquals("5NV6Rdv1a3I", song.videoId)
    }
}
