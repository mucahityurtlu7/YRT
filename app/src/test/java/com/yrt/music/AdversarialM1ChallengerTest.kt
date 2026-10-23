package com.yrt.music

import com.yrt.music.data.model.Song
import com.yrt.music.data.network.YouTubeMusicClient
import com.yrt.music.data.repository.SongRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
 * Adversarial stress and corner-case verification test suite for Milestone 1.
 * Tests extreme inputs for:
 * 1. InnerTube JSON parsing (YouTubeMusicClient)
 * 2. VideoId regex extraction, filename cleaning, and indexing (SongRepository)
 */
class AdversarialM1ChallengerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // Category 1: Duration String Parsing Adversarial Stress
    // =========================================================================

    @Test
    fun testParseDurationMs_validDurations() {
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("0:00"))
        assertEquals(5000L, YouTubeMusicClient.parseDurationMs("0:05"))
        assertEquals(30000L, YouTubeMusicClient.parseDurationMs("0:30"))
        assertEquals(248000L, YouTubeMusicClient.parseDurationMs("4:08"))
        assertEquals(3599000L, YouTubeMusicClient.parseDurationMs("59:59"))
        assertEquals(3600000L, YouTubeMusicClient.parseDurationMs("1:00:00"))
        assertEquals(3661000L, YouTubeMusicClient.parseDurationMs("1:01:01"))
        assertEquals(3735000L, YouTubeMusicClient.parseDurationMs("1:02:15"))
        assertEquals(86400000L, YouTubeMusicClient.parseDurationMs("24:00:00"))
    }

    @Test
    fun testParseDurationMs_whitespaceVariations() {
        assertEquals(248000L, YouTubeMusicClient.parseDurationMs("  4:08  "))
        assertEquals(248000L, YouTubeMusicClient.parseDurationMs(" 4 : 08 "))
        assertEquals(3735000L, YouTubeMusicClient.parseDurationMs(" 1 : 02 : 15 "))
    }

    @Test
    fun testParseDurationMs_corruptAndBoundaryInputs_doNotCrash() {
        assertEquals(0L, YouTubeMusicClient.parseDurationMs(null))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs(""))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("   "))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("not a duration"))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("4")) // single token
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("1:2:3:4")) // 4 tokens
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("1:2:3:4:5")) // 5 tokens
        assertEquals(0L, YouTubeMusicClient.parseDurationMs(":")) // empty parts
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("::"))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs(":::"))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs(":30"))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("4:"))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("a:b"))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("1:b"))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("a:20"))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("1:2:c"))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("4:08.500")) // decimal seconds
    }

    @Test
    fun testParseDurationMs_negativeDurations_behaviorDocumented() {
        // Empirical observation: parseDurationMs does not guard against negative duration tokens.
        // -1 * 60 + 30 = -30 seconds -> -30000 ms.
        val negRes = YouTubeMusicClient.parseDurationMs("-1:30")
        assertEquals(-30000L, negRes)
    }

    // =========================================================================
    // Category 2: Thumbnail URL Extraction Adversarial Stress
    // =========================================================================

    @Test
    fun testExtractThumbnailUrl_emptyOrNull() {
        assertNull(YouTubeMusicClient.extractThumbnailUrl(null))
        assertNull(YouTubeMusicClient.extractThumbnailUrl(JSONObject()))
        assertNull(YouTubeMusicClient.extractThumbnailUrl(JSONObject("""{"thumbnails": []}""")))
    }

    @Test
    fun testExtractThumbnailUrl_deeplyNestedRenderer() {
        val nestedJson = JSONObject(
            """
            {
                "thumbnailRenderer": {
                    "croppedMusicThumbnailRenderer": {
                        "thumbnail": {
                            "thumbnails": [
                                {"url": "https://example.com/small.jpg", "width": 100},
                                {"url": "https://example.com/large.jpg", "width": 800},
                                {"url": "https://example.com/medium.jpg", "width": 300}
                            ]
                        }
                    }
                }
            }
            """.trimIndent()
        )
        val extracted = YouTubeMusicClient.extractThumbnailUrl(nestedJson)
        assertEquals("https://example.com/large.jpg", extracted)
    }

    @Test
    fun testExtractThumbnailUrl_protocolRelativeUrl() {
        val json = JSONObject(
            """
            {
                "thumbnails": [
                    {"url": "//lh3.googleusercontent.com/pic.jpg", "width": 400}
                ]
            }
            """.trimIndent()
        )
        val extracted = YouTubeMusicClient.extractThumbnailUrl(json)
        assertEquals("https://lh3.googleusercontent.com/pic.jpg", extracted)
    }

    @Test
    fun testExtractThumbnailUrl_deepNestingExceeds5Levels_gracefulNull() {
        var current = JSONObject()
        val innermost = JSONObject("""{"thumbnails": [{"url": "https://deep.com/img.jpg", "width": 100}]}""")
        current.put("thumbnail", innermost)
        for (i in 0 until 6) {
            val wrapper = JSONObject()
            wrapper.put("thumbnail", current)
            current = wrapper
        }
        val result = YouTubeMusicClient.extractThumbnailUrl(current)
        assertNull(result)
    }

    @Test
    fun testExtractThumbnailUrl_thumbnailsWithMissingUrlKeys_returnsEmptyStringBug() {
        // Empirical observation: When thumbnails array items lack a "url" property,
        // line 548 falls back to `thumbnails.optJSONObject(...)?.optString("url")`,
        // which returns "" instead of null.
        val json = JSONObject("""{"thumbnails": [{"width": 200}]}""")
        val result = YouTubeMusicClient.extractThumbnailUrl(json)
        // Returns "" instead of null
        assertEquals("", result)
    }

    // =========================================================================
    // Category 3: Runs Text Extraction Adversarial Stress
    // =========================================================================

    @Test
    fun testExtractRunsText_variousStructures() {
        val obj1 = JSONObject("""{"text": "Simple Text"}""")
        assertEquals("Simple Text", YouTubeMusicClient.extractRunsText(obj1))

        val obj2 = JSONObject(
            """
            {
                "runs": [
                    {"text": "Daft "},
                    {"text": "Punk"},
                    {"text": " - "},
                    {"text": "Alive"}
                ]
            }
            """.trimIndent()
        )
        assertEquals("Daft Punk - Alive", YouTubeMusicClient.extractRunsText(obj2))

        val obj3 = JSONObject("""{"runs": []}""")
        assertEquals("", YouTubeMusicClient.extractRunsText(obj3))

        val obj4 = JSONObject("""{"runs": [null, {"text": "Valid"}, null]}""")
        assertEquals("Valid", YouTubeMusicClient.extractRunsText(obj4))

        val obj5 = JSONObject()
        assertEquals("", YouTubeMusicClient.extractRunsText(obj5))

        val obj6 = JSONObject("""{"runs": [{"text": 12345}, {"other": "value"}]}""")
        assertEquals("12345", YouTubeMusicClient.extractRunsText(obj6))
    }

    // =========================================================================
    // Category 4: InnerTube Response Defensive Parsing Adversarial Stress
    // =========================================================================

    @Test
    fun testParseArtistSearchResults_adversarialInputs() {
        assertTrue(YouTubeMusicClient.parseArtistSearchResults("").isEmpty())
        assertTrue(YouTubeMusicClient.parseArtistSearchResults("   ").isEmpty())
        assertTrue(YouTubeMusicClient.parseArtistSearchResults("{ broken json").isEmpty())
        assertTrue(YouTubeMusicClient.parseArtistSearchResults("[]").isEmpty())
        assertTrue(YouTubeMusicClient.parseArtistSearchResults("\"just a string\"").isEmpty())
        assertTrue(YouTubeMusicClient.parseArtistSearchResults("42").isEmpty())
        assertTrue(YouTubeMusicClient.parseArtistSearchResults("null").isEmpty())

        val weirdTypes = JSONObject(
            """
            {
                "contents": {
                    "tabbedSearchResultsRenderer": {
                        "tabs": [
                            {
                                "tabRenderer": {
                                    "content": {
                                        "sectionListRenderer": {
                                            "contents": [
                                                {
                                                    "musicShelfRenderer": {
                                                        "contents": [
                                                            { "musicResponsiveListItemRenderer": "not an object" },
                                                            { "musicResponsiveListItemRenderer": {} },
                                                            {
                                                                "musicResponsiveListItemRenderer": {
                                                                    "navigationEndpoint": { "browseEndpoint": { "browseId": "UCvalid123" } },
                                                                    "flexColumns": []
                                                                }
                                                            }
                                                        ]
                                                    }
                                                }
                                            ]
                                        }
                                    }
                                }
                            }
                        ]
                    }
                }
            }
            """.trimIndent()
        ).toString()

        val results = YouTubeMusicClient.parseArtistSearchResults(weirdTypes)
        assertTrue(results.isEmpty())
    }

    @Test
    fun testParseAlbumSearchResults_adversarialInputs() {
        assertTrue(YouTubeMusicClient.parseAlbumSearchResults("").isEmpty())
        assertTrue(YouTubeMusicClient.parseAlbumSearchResults("{").isEmpty())
        assertTrue(YouTubeMusicClient.parseAlbumSearchResults("""{"contents": null}""").isEmpty())
        assertTrue(YouTubeMusicClient.parseAlbumSearchResults("""{"contents": {}}""").isEmpty())
    }

    @Test
    fun testParseArtistProfile_missingFields_returnsSafeDefaults() {
        val p1 = YouTubeMusicClient.parseArtistProfile("UCtest", "")
        assertEquals("UCtest", p1.browseId)
        assertEquals("", p1.name)
        assertTrue(p1.topSongs.isEmpty())
        assertTrue(p1.albums.isEmpty())

        val p2 = YouTubeMusicClient.parseArtistProfile("UCtest", "{ invalid: true }")
        assertEquals("UCtest", p2.browseId)
        assertEquals("", p2.name)

        val p3 = YouTubeMusicClient.parseArtistProfile("UCtest", "{}")
        assertEquals("UCtest", p3.browseId)
        assertEquals("", p3.name)
        assertNull(p3.heroArtworkUrl)
        assertNull(p3.description)
        assertNull(p3.subscribers)
        assertTrue(p3.topSongs.isEmpty())
        assertTrue(p3.albums.isEmpty())
    }

    @Test
    fun testParseAlbumDetails_missingFields_returnsSafeDefaults() {
        val a1 = YouTubeMusicClient.parseAlbumDetails("MPREtest", "")
        assertEquals("MPREtest", a1.browseId)
        assertEquals("", a1.title)
        assertTrue(a1.tracks.isEmpty())

        val a2 = YouTubeMusicClient.parseAlbumDetails("MPREtest", "{}")
        assertEquals("MPREtest", a2.browseId)
        assertEquals("", a2.title)
        assertNull(a2.artist)
        assertNull(a2.year)
        assertNull(a2.artworkUrl)
        assertEquals(0, a2.trackCount)
        assertTrue(a2.tracks.isEmpty())
    }

    @Test
    fun testParseArtistProfile_with1000ShelfItems_handlesHighVolume() {
        val root = JSONObject()
        val contents = JSONObject()
        val singleCol = JSONObject()
        val tabs = JSONArray()
        val tab = JSONObject()
        val tabRenderer = JSONObject()
        val content = JSONObject()
        val sectionList = JSONObject()
        val sections = JSONArray()

        val songShelf = JSONObject()
        val musicShelf = JSONObject()
        val songContents = JSONArray()

        for (i in 0 until 1000) {
            val item = JSONObject()
            val renderer = JSONObject()
            val playData = JSONObject().apply { put("videoId", "video_$i") }
            renderer.put("playlistItemData", playData)

            val flexCols = JSONArray()
            val flex0 = JSONObject().apply {
                val r = JSONObject().apply {
                    val t = JSONObject().apply { put("text", "Track Number $i") }
                    put("text", t)
                }
                put("musicResponsiveListItemFlexColumnRenderer", r)
            }
            flexCols.put(flex0)
            renderer.put("flexColumns", flexCols)

            item.put("musicResponsiveListItemRenderer", renderer)
            songContents.put(item)
        }

        musicShelf.put("contents", songContents)
        songShelf.put("musicShelfRenderer", musicShelf)
        sections.put(songShelf)

        sectionList.put("contents", sections)
        content.put("sectionListRenderer", sectionList)
        tabRenderer.put("content", content)
        tab.put("tabRenderer", tabRenderer)
        tabs.put(tab)
        singleCol.put("tabs", tabs)
        contents.put("singleColumnBrowseResultsRenderer", singleCol)
        root.put("contents", contents)

        val profile = YouTubeMusicClient.parseArtistProfile("UCbulk", root.toString())
        assertEquals(1000, profile.topSongs.size)
        assertEquals("video_0", profile.topSongs[0].videoId)
        assertEquals("Track Number 999", profile.topSongs[999].title)
    }

    // =========================================================================
    // Category 5: Video ID Regex Extraction Corner Cases
    // =========================================================================

    @Test
    fun testExtractVideoId_multipleBracketBlocks() {
        // VideoId at the very end
        assertEquals("12345678901", SongRepository.extractVideoId("Song [Remix] [Official Video] [12345678901]"))
        assertEquals("5NV6Rdv1a3I", SongRepository.extractVideoId("[Official] Song [Audio] [Live 2007] [5NV6Rdv1a3I]"))
        assertEquals("a-B_1234567", SongRepository.extractVideoId("Artist - Title [Single] [Remastered] [a-B_1234567]"))

        // VideoId NOT at the very end: returns null because regex is anchored with $
        assertNull(SongRepository.extractVideoId("Song [12345678901] [Remix]"))
        assertNull(SongRepository.extractVideoId("[5NV6Rdv1a3I] Song - Title"))
        assertNull(SongRepository.extractVideoId("Song [12345678901] [Official]"))
    }

    @Test
    fun testExtractVideoId_whitespaceAndPaddingVariations() {
        // Trailing and leading whitespace on filename is trimmed
        assertEquals("5NV6Rdv1a3I", SongRepository.extractVideoId("Song [5NV6Rdv1a3I]   "))
        assertEquals("5NV6Rdv1a3I", SongRepository.extractVideoId("   Song [5NV6Rdv1a3I]"))
        assertEquals("5NV6Rdv1a3I", SongRepository.extractVideoId(" \t Song [5NV6Rdv1a3I] \t "))

        // Spaces inside the brackets are NOT allowed (YouTube IDs cannot contain spaces)
        assertNull(SongRepository.extractVideoId("Song [ 5NV6Rdv1a3I]"))
        assertNull(SongRepository.extractVideoId("Song [5NV6Rdv1a3I ]"))
        assertNull(SongRepository.extractVideoId("Song [ 5NV6Rdv1a3I ]"))
        assertNull(SongRepository.extractVideoId("Song [5NV6 Rdv1a3I]"))
    }

    @Test
    fun testExtractVideoId_characterSetAndSymbols() {
        // Valid 11-char base64url characters: letters, digits, hyphen, underscore
        assertEquals("12345678901", SongRepository.extractVideoId("Song [12345678901]"))
        assertEquals("abcdefghijk", SongRepository.extractVideoId("Song [abcdefghijk]"))
        assertEquals("ABCDEFGHIJK", SongRepository.extractVideoId("Song [ABCDEFGHIJK]"))
        assertEquals("-_________-", SongRepository.extractVideoId("Song [-_________-]")) // 11 chars
        assertEquals("a-B_12-34_X", SongRepository.extractVideoId("Song [a-B_12-34_X]"))
        assertEquals("___________", SongRepository.extractVideoId("Song [___________]"))

        // Invalid characters inside brackets
        assertNull(SongRepository.extractVideoId("Song [1234567890!]"))
        assertNull(SongRepository.extractVideoId("Song [1234567890@]"))
        assertNull(SongRepository.extractVideoId("Song [1234567890#]"))
        assertNull(SongRepository.extractVideoId("Song [12345678.01]"))
        assertNull(SongRepository.extractVideoId("Song [1234567890?]"))
        assertNull(SongRepository.extractVideoId("Song [1234567890/]"))
    }

    @Test
    fun testExtractVideoId_lengthBoundaries() {
        assertNull(SongRepository.extractVideoId("Song []")) // 0 chars
        assertNull(SongRepository.extractVideoId("Song [a]")) // 1 char
        assertNull(SongRepository.extractVideoId("Song [12345]")) // 5 chars
        assertNull(SongRepository.extractVideoId("Song [1234567890]")) // 10 chars (1 too short)
        assertEquals("12345678901", SongRepository.extractVideoId("Song [12345678901]")) // 11 chars (exact)
        assertNull(SongRepository.extractVideoId("Song [123456789012]")) // 12 chars (1 too long)
        assertNull(SongRepository.extractVideoId("Song [-__________-]")) // 12 chars (1 too long)
        assertNull(SongRepository.extractVideoId("Song [12345678901234567890]")) // 20 chars
    }

    @Test
    fun testExtractVideoId_unicodeAndEmojis() {
        // Unicode and emojis in title with valid ASCII 11-char ID -> extracts correctly
        assertEquals("5NV6Rdv1a3I", SongRepository.extractVideoId("🎵 Müslüm Gürses - Affet [5NV6Rdv1a3I]"))
        assertEquals("5NV6Rdv1a3I", SongRepository.extractVideoId("🎧 Daft Punk - Get Lucky [5NV6Rdv1a3I]"))
        assertEquals("12345678901", SongRepository.extractVideoId("日本語タイトル - 曲名 [12345678901]"))
        assertEquals("12345678901", SongRepository.extractVideoId("موسيقى عربية [12345678901]"))
        assertEquals("12345678901", SongRepository.extractVideoId("Русская песня [12345678901]"))

        // Unicode or emojis inside the ID bracket -> fails (returns null)
        assertNull(SongRepository.extractVideoId("Song [öçşığü12345]"))
        assertNull(SongRepository.extractVideoId("Song [1234567890🔥]"))
        assertNull(SongRepository.extractVideoId("Song [1234567890ä]"))
    }

    @Test
    fun testExtractVideoId_nestedOrEscapedBrackets() {
        assertNull(SongRepository.extractVideoId("Song [[12345678901]]"))
        assertNull(SongRepository.extractVideoId("Song [12345678901]extra"))
        assertNull(SongRepository.extractVideoId("Song [12345678901]-remix"))
    }

    // =========================================================================
    // Category 6: cleanName Corner Cases & Discrepancies
    // =========================================================================

    @Test
    fun testCleanName_cornerCases() {
        // Standard videoId stripped
        assertEquals("Daft Punk - Get Lucky", SongRepository.cleanName("Daft Punk - Get Lucky [5NV6Rdv1a3I]"))

        // Multiple brackets: only trailing videoId stripped
        assertEquals("Song [Remix] [Official Video]", SongRepository.cleanName("Song [Remix] [Official Video] [12345678901]"))

        // Bracketed text < 6 chars preserved
        assertEquals("Song [EP]", SongRepository.cleanName("Song [EP]"))
        assertEquals("Song [Live]", SongRepository.cleanName("Song [Live]"))
        assertEquals("Song [Remix]", SongRepository.cleanName("Song [Remix]"))

        // Pure videoId title without surrounding spaces returns empty string
        assertEquals("", SongRepository.cleanName("[12345678901]"))

        // Plain titles untouched
        assertEquals("Bohemian Rhapsody", SongRepository.cleanName("Bohemian Rhapsody"))
        assertEquals("Track (2024 Remaster)", SongRepository.cleanName("Track (2024 Remaster)"))
    }

    @Test
    fun testCleanName_bug_trailingWhitespacePreventsRemoval() {
        // EMPIRICAL BUG DEMONSTRATION:
        // cleanName() applies regex `\s*\[[\w-]{6,}]$` without trimming first.
        // If the filename has trailing spaces (e.g. "Song [5NV6Rdv1a3I] "),
        // the regex fails to match because '$' is preceded by spaces, not ']'.
        // Consequently, the videoId is NOT removed!
        val withTrailingSpace = "Daft Punk - Get Lucky [5NV6Rdv1a3I] "
        val cleaned = SongRepository.cleanName(withTrailingSpace)
        // BUG: The videoId is preserved because regex fails on trailing whitespace
        assertEquals("Daft Punk - Get Lucky [5NV6Rdv1a3I]", cleaned)
    }

    @Test
    fun testCleanName_bug_stripsLegitimateTitleTagsLongerThan5Chars() {
        // EMPIRICAL BUG DEMONSTRATION:
        // cleanName() uses {6,} instead of {11}.
        // Legitimate song title tags with >= 6 chars like "[Remixes]", "[Acoustic]", "[Instrumental]"
        // are stripped by cleanName even though extractVideoId correctly returns null!
        assertEquals("Cool Song", SongRepository.cleanName("Cool Song [Remixes]"))
        assertEquals("Cool Song", SongRepository.cleanName("Cool Song [Acoustic]"))
        assertEquals("Cool Song", SongRepository.cleanName("Cool Song [Instrumental]"))

        // In contrast, extractVideoId does NOT treat them as video IDs:
        assertNull(SongRepository.extractVideoId("Cool Song [Remixes]"))
        assertNull(SongRepository.extractVideoId("Cool Song [Acoustic]"))
        assertNull(SongRepository.extractVideoId("Cool Song [Instrumental]"))
    }

    // =========================================================================
    // Category 7: SongRepository Local Directory Stress & Deduplication
    // =========================================================================

    @Test
    fun testSongRepository_deduplicationPreservesMostRecent() = runBlocking {
        val musicDir = tempFolder.newFolder("dedup_music")
        val artDir = tempFolder.newFolder("dedup_art")
        val repo = SongRepository(musicDir = musicDir, artDir = artDir)

        val fileOld = File(musicDir, "Track Old [DEDUP123456].mp3").apply {
            createNewFile()
            setLastModified(1000L)
        }
        val fileNew = File(musicDir, "Track New [DEDUP123456].mp3").apply {
            createNewFile()
            setLastModified(5000L)
        }

        repo.refresh()

        assertTrue(repo.hasVideoId("DEDUP123456"))
        val resolved = repo.getSongByVideoId("DEDUP123456")
        assertNotNull(resolved)
        // Deduplication must point to the newest file
        assertEquals(fileNew.absolutePath, resolved?.file?.absolutePath)
        assertEquals(5000L, resolved?.lastModified)
    }

    @Test
    fun testSongRepository_handlesSubdirectoriesAndUnsupportedExtensions() = runBlocking {
        val musicDir = tempFolder.newFolder("filter_music")
        val artDir = tempFolder.newFolder("filter_art")
        val repo = SongRepository(musicDir = musicDir, artDir = artDir)

        File(musicDir, "Subfolder.mp3").mkdir()
        File(musicDir, "video.mp4").createNewFile()
        File(musicDir, "document.pdf").createNewFile()
        File(musicDir, "notes.txt").createNewFile()
        File(musicDir, "cover.jpg").createNewFile()

        File(musicDir, "valid1 [VIDEO_ID_01].mp3").createNewFile()
        File(musicDir, "valid2 [VIDEO_ID_02].m4a").createNewFile()
        File(musicDir, "valid3 [VIDEO_ID_03].opus").createNewFile()
        File(musicDir, "valid4 [VIDEO_ID_04].flac").createNewFile()

        repo.refresh()

        assertEquals(4, repo.songs.value.size)
        assertTrue(repo.hasVideoId("VIDEO_ID_01"))
        assertTrue(repo.hasVideoId("VIDEO_ID_02"))
        assertTrue(repo.hasVideoId("VIDEO_ID_03"))
        assertTrue(repo.hasVideoId("VIDEO_ID_04"))
        assertFalse(repo.hasVideoId("Subfolder"))
    }

    @Test
    fun testSongRepository_fileWithMultipleDotsInName() = runBlocking {
        val musicDir = tempFolder.newFolder("dots_music")
        val artDir = tempFolder.newFolder("dots_art")
        val repo = SongRepository(musicDir = musicDir, artDir = artDir)

        File(musicDir, "Artist.Name.Song.Title.v1.0 [MULTIDOT123].mp3").createNewFile()

        repo.refresh()

        assertTrue(repo.hasVideoId("MULTIDOT123"))
        val song = repo.getSongByVideoId("MULTIDOT123")
        assertNotNull(song)
        assertEquals("Artist.Name.Song.Title.v1.0", song?.title)
    }

    @Test
    fun testSongRepository_zeroByteFileDoesNotCrash() = runBlocking {
        val musicDir = tempFolder.newFolder("zerobyte_music")
        val artDir = tempFolder.newFolder("zerobyte_art")
        val repo = SongRepository(musicDir = musicDir, artDir = artDir)

        val file = File(musicDir, "Empty [ZEROBYTE123].mp3").apply { createNewFile() }
        assertEquals(0L, file.length())

        repo.refresh()

        assertTrue(repo.hasVideoId("ZEROBYTE123"))
        val song = repo.getSongByVideoId("ZEROBYTE123")
        assertNotNull(song)
        assertEquals("Empty", song?.title)
    }
}
