package com.yrt.music

import com.yrt.music.data.model.AlbumSearchResult
import com.yrt.music.data.model.ArtistProfile
import com.yrt.music.data.network.YouTubeMusicClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ArtistCatalogPaginationTest {
    @Test
    fun releaseGridFollowsEveryLegacyContinuationAndTitleRunEndpoint() {
        val artist = page(JSONObject().put("musicCarouselShelfRenderer", JSONObject()
            .put("header", JSONObject().put("musicCarouselShelfBasicHeaderRenderer", JSONObject()
                .put("title", text("Albums"))
                .put("moreContentButton", JSONObject().put("buttonRenderer", JSONObject()
                    .put("navigationEndpoint", endpoint("MPAD_artist", "albums-filter"))))))
            .put("contents", JSONArray())))
        val requests = mutableListOf<String>()
        val profile = YouTubeMusicClient.loadArtistProfile("UC_artist", artist) { request ->
            if (request.has("continuation")) {
                val token = request.getString("continuation")
                requests += token
                JSONObject().put("continuationContents", JSONObject().put("gridContinuation",
                    grid("MPRE_$token", if (token == "next") "last" else null)))
            } else {
                assertEquals("MPAD_artist", request.getString("browseId"))
                assertEquals("albums-filter", request.getString("params"))
                requests += "albums"
                page(JSONObject().put("gridRenderer", grid("MPRE_first", "next")))
            }
        }
        assertEquals(listOf("albums", "next", "last"), requests)
        assertEquals(listOf("MPRE_first", "MPRE_next", "MPRE_last"), profile.albums.map { it.browseId })
        assertTrue(profile.albums.all { it.browseParams == "album-params" })
    }

    @Test
    fun albumPaginationAddsSongsBeyondPreviewWithTheirPlayCounts() {
        val root = page(JSONObject().put("musicShelfRenderer", JSONObject()
            .put("title", text("Top songs"))
            .put("contents", JSONArray().put(track("preview", "5M plays")))))
        val preview = YouTubeMusicClient.parseArtistProfile("UC_artist", root.toString())
        val profile = preview.copy(albums = listOf(AlbumSearchResult("MPRE_album", "Album", browseParams = "keep-me")))
        val catalog = YouTubeMusicClient.loadArtistCatalog(profile, listOf(root)) { request ->
            if (request.has("continuation")) {
                assertEquals("album-next", request.getString("continuation"))
                JSONObject().put("continuationContents", JSONObject().put("musicShelfContinuation", JSONObject()
                    .put("contents", JSONArray().put(track("extra", "20M plays")).put(track("preview", "5M plays")))))
            } else {
                assertEquals("MPRE_album", request.getString("browseId"))
                assertEquals("keep-me", request.getString("params"))
                page(JSONObject().put("musicShelfRenderer", JSONObject()
                    .put("contents", JSONArray().put(track("album-first", "12M plays")))
                    .put("continuations", continuation("album-next"))))
                    .put("header", JSONObject().put("musicDetailHeaderRenderer", JSONObject().put("title", text("Album"))))
            }
        }
        assertEquals(listOf("extra", "album-first", "preview"), catalog.allSongs.map { it.videoId })
        assertEquals(listOf("preview"), catalog.topSongs.map { it.videoId })
        assertEquals("Album", catalog.allSongs.first().album)
        assertTrue(catalog.isCatalogComplete)
    }

    @Test
    fun malformedContinuationCannotBecomeACompleteCatalog() {
        val profile = ArtistProfile("UC_artist", "Artist", albums = listOf(AlbumSearchResult("MPRE_album", "Album")), isCatalogComplete = false)
        try {
            YouTubeMusicClient.loadArtistCatalog(profile, emptyList()) { request ->
                if (request.has("continuation")) JSONObject().put("responseContext", JSONObject())
                else page(JSONObject().put("musicShelfRenderer", JSONObject()
                    .put("contents", JSONArray().put(track("first", null)))
                    .put("continuations", continuation("missing-page"))))
            }
            fail("A missing advertised page must fail instead of enqueueing a partial catalog")
        } catch (_: IOException) {
            assertFalse(profile.isCatalogComplete)
        }
    }

    @Test
    fun repeatedContinuationFailsInsteadOfLoopingOrTruncating() {
        val shelf = JSONObject().put("contents", JSONArray().put(track("first", null)))
            .put("continuations", continuation("same-token"))
        val root = page(JSONObject().put("musicShelfRenderer", shelf))
        val profile = YouTubeMusicClient.parseArtistProfile("UC_artist", root.toString())
        var requests = 0
        try {
            YouTubeMusicClient.loadArtistCatalog(profile, listOf(root)) {
                requests++
                JSONObject().put("continuationContents", JSONObject().put("musicShelfContinuation", shelf))
            }
            fail("Repeated continuation must fail")
        } catch (_: IOException) {
            assertEquals(1, requests)
        }
    }

    @Test
    fun twoColumnArtistIncludesSinglesAndExcludesFeaturedAlbums() {
        fun releaseShelf(title: String, id: String) = JSONObject().put("musicCarouselShelfRenderer", JSONObject()
            .put("header", JSONObject().put("musicCarouselShelfBasicHeaderRenderer", JSONObject().put("title", text(title))))
            .put("contents", JSONArray().put(release(id))))
        val artist = page(releaseShelf("Singles & EPs", "MPRE_single"), releaseShelf("Featured on", "MPRE_unrelated"))
        val contents = artist.getJSONObject("contents")
        contents.put("twoColumnBrowseResultsRenderer", contents.remove("singleColumnBrowseResultsRenderer"))
        val profile = YouTubeMusicClient.parseArtistProfile("UC_artist", artist.toString())
        assertEquals(listOf("MPRE_single"), profile.albums.map { it.browseId })
        assertFalse(profile.isCatalogComplete)
    }

    @Test
    fun fullSongsPlaylistExcludesRecommendationShelves() {
        val root = page(JSONObject().put("musicShelfRenderer", JSONObject().put("title", text("Top songs"))
            .put("contents", JSONArray()).put("bottomEndpoint", endpoint("VL_songs", "songs"))))
        val profile = YouTubeMusicClient.parseArtistProfile("UC_artist", root.toString())
        val catalog = YouTubeMusicClient.loadArtistCatalog(profile, listOf(root)) {
            page(
                JSONObject().put("musicPlaylistShelfRenderer", JSONObject().put("contents", JSONArray().put(track("own-song", "1M plays")))),
                JSONObject().put("musicShelfRenderer", JSONObject().put("title", text("Recommended"))
                    .put("contents", JSONArray().put(track("unrelated", "2B plays")))),
            )
        }
        assertEquals(listOf("own-song"), catalog.allSongs.map { it.videoId })
    }

    @Test
    fun unavailableAlbumCannotSubstituteRecommendedSongs() {
        val profile = ArtistProfile("UC_artist", "Artist", albums = listOf(AlbumSearchResult("MPRE_album", "Album")), isCatalogComplete = false)
        try {
            YouTubeMusicClient.loadArtistCatalog(profile, emptyList()) {
                page(JSONObject().put("musicShelfRenderer", JSONObject().put("title", text("Recommended songs"))
                    .put("contents", JSONArray().put(track("unrelated", "2B plays")))))
            }
            fail("Recommendations cannot stand in for an unavailable album")
        } catch (_: IOException) {
            assertFalse(profile.isCatalogComplete)
        }
    }

    private fun page(vararg sections: JSONObject): JSONObject = JSONObject()
        .put("header", JSONObject().put("musicImmersiveHeaderRenderer", JSONObject().put("title", text("Artist"))))
        .put("contents", JSONObject().put("singleColumnBrowseResultsRenderer", JSONObject().put("tabs", JSONArray()
            .put(JSONObject().put("tabRenderer", JSONObject().put("content", JSONObject().put("sectionListRenderer",
                JSONObject().put("contents", JSONArray(sections.toList())))))))))

    private fun text(value: String) = JSONObject().put("runs", JSONArray().put(JSONObject().put("text", value)))
    private fun endpoint(id: String, params: String) = JSONObject().put("browseEndpoint", JSONObject().put("browseId", id).put("params", params))
    private fun continuation(token: String) = JSONArray().put(JSONObject().put("nextContinuationData", JSONObject().put("continuation", token)))
    private fun grid(id: String, next: String?) = JSONObject().put("items", JSONArray().put(release(id))).apply {
        next?.let { put("continuations", continuation(it)) }
    }
    private fun release(id: String) = JSONObject().put("musicTwoRowItemRenderer", JSONObject()
        .put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Release")
            .put("navigationEndpoint", endpoint(id, "album-params")))))
        .put("subtitle", text("Single • 2026")))
    private fun track(id: String, plays: String?) = JSONObject().put("musicResponsiveListItemRenderer", JSONObject()
        .put("playlistItemData", JSONObject().put("videoId", id))
        .put("flexColumns", JSONArray().put(column(id)).put(column("Artist")).put(column(plays.orEmpty()))))
    private fun column(value: String) = JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", text(value)))
}
