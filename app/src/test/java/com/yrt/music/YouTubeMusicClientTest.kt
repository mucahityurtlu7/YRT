package com.yrt.music

import com.yrt.music.data.network.YouTubeMusicClient
import com.yrt.music.fixtures.MockInnerTubeResponses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying JSON parsing for YouTube Music InnerTube responses:
 * artist search, album search, artist profile, and album browse.
 */
class YouTubeMusicClientTest {

    // =========================================================================
    // Category 1: Artist Search Results Parsing
    // =========================================================================

    @Test
    fun testParseArtistSearchResults_validPayload() {
        val results = YouTubeMusicClient.parseArtistSearchResults(MockInnerTubeResponses.ARTIST_SEARCH_JSON)

        assertEquals(2, results.size)

        val first = results[0]
        assertEquals("UCRr1xG_2WIDs18a6cIiCxeA", first.browseId)
        assertEquals("Daft Punk", first.name)
        assertEquals("https://lh3.googleusercontent.com/daftpunk_thumb.jpg", first.thumbnailUrl)
        assertTrue(first.subtitle?.contains("8.1M subscribers") == true)

        val second = results[1]
        assertEquals("UCr_iyUANcn9OX_yy9piYoLw", second.browseId)
        assertEquals("Radiohead", second.name)
        assertEquals("https://lh3.googleusercontent.com/radiohead_thumb.jpg", second.thumbnailUrl)
    }

    @Test
    fun testParseArtistSearchResults_emptyPayload_returnsEmptyList() {
        val results = YouTubeMusicClient.parseArtistSearchResults(MockInnerTubeResponses.EMPTY_RESPONSE_JSON)
        assertTrue(results.isEmpty())

        val resultsFromMalformed = YouTubeMusicClient.parseArtistSearchResults("{ \"invalid\": true }")
        assertTrue(resultsFromMalformed.isEmpty())
    }

    // =========================================================================
    // Category 2: Album Search Results Parsing
    // =========================================================================

    @Test
    fun testParseAlbumSearchResults_validPayload() {
        val results = YouTubeMusicClient.parseAlbumSearchResults(MockInnerTubeResponses.ALBUM_SEARCH_JSON)

        assertEquals(1, results.size)
        val album = results[0]

        assertEquals("MPREb_yXhSI4FCUo6", album.browseId)
        assertEquals("Random Access Memories", album.title)
        assertEquals("Daft Punk", album.artist)
        assertEquals("2013", album.year)
        assertEquals("https://lh3.googleusercontent.com/ram_cover.jpg", album.artworkUrl)
    }

    @Test
    fun testParseAlbumSearchResults_emptyPayload_returnsEmptyList() {
        val results = YouTubeMusicClient.parseAlbumSearchResults(MockInnerTubeResponses.EMPTY_RESPONSE_JSON)
        assertTrue(results.isEmpty())
    }

    // =========================================================================
    // Category 3: Artist Profile Parsing
    // =========================================================================

    @Test
    fun testParseArtistProfile_validPayload() {
        val profile = YouTubeMusicClient.parseArtistProfile("UCRr1xG_2WIDs18a6cIiCxeA", MockInnerTubeResponses.ARTIST_PROFILE_JSON)

        assertNotNull(profile)
        assertEquals("UCRr1xG_2WIDs18a6cIiCxeA", profile.browseId)
        assertEquals("Daft Punk", profile.name)
        assertEquals("https://lh3.googleusercontent.com/daftpunk_hero.jpg", profile.heroArtworkUrl)
        assertEquals("Legendary French electronic music duo.", profile.description)
        assertEquals("8.1M subscribers", profile.subscribers)

        // Verify Top Songs
        assertEquals(2, profile.topSongs.size)
        val song1 = profile.topSongs[0]
        assertEquals("5NV6Rdv1a3I", song1.videoId)
        assertEquals("Get Lucky", song1.title)
        assertEquals("Daft Punk", song1.artist)
        assertEquals("4:08", song1.durationText)
        assertEquals(248000L, song1.durationMs)
        assertEquals("https://lh3.googleusercontent.com/getlucky.jpg", song1.thumbnailUrl)

        val song2 = profile.topSongs[1]
        assertEquals("FGBhQbmMxH8", song2.videoId)
        assertEquals("One More Time", song2.title)
        assertEquals(320000L, song2.durationMs)

        // Verify Discography Albums
        assertEquals(2, profile.albums.size)
        val album1 = profile.albums[0]
        assertEquals("MPREb_yXhSI4FCUo6", album1.browseId)
        assertEquals("Random Access Memories", album1.title)
        assertEquals("2013", album1.year)

        val album2 = profile.albums[1]
        assertEquals("MPREb_Discovery123", album2.browseId)
        assertEquals("Discovery", album2.title)
        assertEquals("2001", album2.year)
    }

    // =========================================================================
    // Category 4: Album Details Parsing
    // =========================================================================

    @Test
    fun testParseAlbumDetails_validPayload() {
        val details = YouTubeMusicClient.parseAlbumDetails("MPREb_yXhSI4FCUo6", MockInnerTubeResponses.ALBUM_DETAILS_JSON)

        assertNotNull(details)
        assertEquals("MPREb_yXhSI4FCUo6", details.browseId)
        assertEquals("Random Access Memories", details.title)
        assertEquals("Daft Punk", details.artist)
        assertEquals("2013", details.year)
        assertEquals("https://lh3.googleusercontent.com/ram_large.jpg", details.artworkUrl)
        assertEquals(2, details.tracks.size)

        val track1 = details.tracks[0]
        assertEquals("zhl-Cs1-sG4", track1.videoId)
        assertEquals("Give Life Back to Music", track1.title)
        assertEquals("Daft Punk", track1.artist)
        assertEquals("Random Access Memories", track1.album)
        assertEquals("4:34", track1.durationText)
        assertEquals(274000L, track1.durationMs)
        assertEquals(1, track1.index)

        val track8 = details.tracks[1]
        assertEquals("5NV6Rdv1a3I", track8.videoId)
        assertEquals("Get Lucky", track8.title)
        assertEquals("4:08", track8.durationText)
        assertEquals(248000L, track8.durationMs)
        assertEquals(8, track8.index)
    }

    // =========================================================================
    // Category 5: Duration Parsing Helper
    // =========================================================================

    @Test
    fun testParseDurationText_variousFormats() {
        assertEquals(225000L, YouTubeMusicClient.parseDurationMs("3:45"))
        assertEquals(30000L, YouTubeMusicClient.parseDurationMs("0:30"))
        assertEquals(3735000L, YouTubeMusicClient.parseDurationMs("1:02:15"))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs(null))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs(""))
        assertEquals(0L, YouTubeMusicClient.parseDurationMs("invalid"))
    }
}
