package com.yrt.music

import com.yrt.music.data.network.YouTubeMusicClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused regressions based on the live WEB_REMIX artist/discography response shapes. */
class ArtistCatalogAuditTest {

    @Test
    fun releaseMoreEndpointsWithSameBrowseIdRetainTheirDistinctParams() {
        val artist = artistPage(
            releaseCarousel("Albums", "album-filter"),
            releaseCarousel("Singles & EPs", "single-filter"),
            releaseCarousel("Featured on", "unrelated-filter"),
        )
        val requests = mutableListOf<String>()

        val profile = YouTubeMusicClient.loadArtistProfile("UC_artist", artist) { request ->
            assertEquals("MPADUC_artist", request.getString("browseId"))
            assertTrue(request.has("context"))
            val params = request.getString("params")
            requests += params
            when (params) {
                "album-filter" -> gridPage(release("MPRE_album", "Full album", "Album"))
                "single-filter" -> gridPage(release("MPRE_single", "New single", "Single"))
                else -> error("A recommendation carousel must not enter the artist catalog: $params")
            }
        }

        assertEquals(setOf("album-filter", "single-filter"), requests.toSet())
        assertEquals(2, requests.size)
        assertEquals(setOf("MPRE_album", "MPRE_single"), profile.albums.map { it.browseId }.toSet())
        assertEquals("Single", profile.albums.single { it.browseId == "MPRE_single" }.type)
        assertFalse(profile.isCatalogComplete)
    }

    @Test
    fun songContinuationCommandsAppendAllTracksAndSortByNumericPlays() {
        val preview = track("preview", "Preview", "10M plays")
        val songsEndpoint = endpoint("VL_artist_songs", "ranked-songs")
        val artist = artistPage(JSONObject().put("musicShelfRenderer", JSONObject()
            .put("title", text("Top songs"))
            .put("contents", JSONArray().put(preview))
            .put("bottomEndpoint", songsEndpoint)))
        val profile = YouTubeMusicClient.parseArtistProfile("UC_artist", artist.toString())
        val requests = mutableListOf<String>()

        val catalog = YouTubeMusicClient.loadArtistCatalog(profile, listOf(artist)) { request ->
            assertTrue(request.has("context"))
            when {
                request.has("browseId") -> {
                    assertEquals("VL_artist_songs", request.getString("browseId"))
                    assertEquals("ranked-songs", request.getString("params"))
                    requests += "songs"
                    artistPage(
                        JSONObject().put("musicPlaylistShelfRenderer", JSONObject()
                            .put("contents", JSONArray()
                                .put(track("low", "Lower", "850K plays"))
                                .put(track("high", "Higher", "1.9B plays"))
                                .put(continuationItem("next-songs")))),
                        JSONObject().put("musicShelfRenderer", JSONObject()
                            .put("title", text("Recommended songs"))
                            .put("contents", JSONArray()
                                .put(track("other-artist", "Unrelated recommendation", "9B plays")))),
                    )
                }
                request.optString("continuation") == "next-songs" -> {
                    requests += "next-songs"
                    JSONObject().put("onResponseReceivedActions", JSONArray().put(JSONObject()
                        .put("appendContinuationItemsAction", JSONObject()
                            .put("targetId", "artist_songs")
                            .put("continuationItems", JSONArray()
                                .put(track("highest", "Highest", "2.1B plays"))
                                .put(track("high", "Higher", "1.9B plays"))
                                .put(track("unknown", "No count", null))))))
                }
                else -> error("Unexpected catalog request: $request")
            }
        }

        assertEquals(listOf("songs", "next-songs"), requests)
        assertEquals(listOf("highest", "high", "preview", "low", "unknown"), catalog.allSongs.map { it.videoId })
        assertEquals(2_100_000_000L, catalog.allSongs.first().viewCount)
        assertEquals(5, catalog.allSongs.map { it.videoId }.distinct().size)
        assertTrue(catalog.isCatalogComplete)
    }

    @Test
    fun previewTracksReadPlaysIndependentlyFromArtistAndAlbumColumns() {
        val artist = artistPage(JSONObject().put("musicShelfRenderer", JSONObject()
            .put("title", text("Top songs"))
            .put("contents", JSONArray().put(track("one", "One", "1.2B plays")))))

        val profile = YouTubeMusicClient.parseArtistProfile("UC_artist", artist.toString())

        assertEquals(1_200_000_000L, profile.topSongs.single().viewCount)
        assertEquals("Artist", profile.topSongs.single().artist)
        assertEquals("The album", profile.topSongs.single().album)
    }

    @Test
    fun legacyUntitledSongShelfExcludesAdjacentRecommendedSongs() {
        val artist = artistPage(JSONObject().put("musicShelfRenderer", JSONObject()
            .put("title", text("Top songs"))
            .put("contents", JSONArray().put(track("preview", "Preview", "10M plays")))
            .put("bottomEndpoint", endpoint("VL_artist_songs", "songs"))))
        val profile = YouTubeMusicClient.parseArtistProfile("UC_artist", artist.toString())

        val catalog = YouTubeMusicClient.loadArtistCatalog(profile, listOf(artist)) {
            artistPage(
                JSONObject().put("musicShelfRenderer", JSONObject()
                    .put("contents", JSONArray().put(track("owned", "Artist's song", "20M plays")))),
                JSONObject().put("musicShelfRenderer", JSONObject()
                    .put("title", text("Recommended songs"))
                    .put("contents", JSONArray().put(track("other", "Other artist", "9B plays")))),
            )
        }

        assertEquals(listOf("owned", "preview"), catalog.allSongs.map { it.videoId })
        assertTrue(catalog.isCatalogComplete)
    }

    private fun artistPage(vararg sections: JSONObject): JSONObject {
        val sectionList = JSONObject().put("contents", JSONArray(sections.toList()))
        val content = JSONObject().put("sectionListRenderer", sectionList)
        val tab = JSONObject().put("tabRenderer", JSONObject().put("content", content))
        val results = JSONObject().put("tabs", JSONArray().put(tab))
        val header = JSONObject().put("musicImmersiveHeaderRenderer", JSONObject().put("title", text("Artist")))
        return JSONObject().put("header", header)
            .put("contents", JSONObject().put("singleColumnBrowseResultsRenderer", results))
    }

    private fun gridPage(vararg releases: JSONObject): JSONObject = artistPage(
        JSONObject().put("gridRenderer", JSONObject().put("items", JSONArray(releases.toList()))),
    )

    private fun releaseCarousel(title: String, params: String): JSONObject {
        val button = JSONObject().put("navigationEndpoint", endpoint("MPADUC_artist", params, "MUSIC_PAGE_TYPE_ARTIST_DISCOGRAPHY"))
        val header = JSONObject().put("title", text(title))
            .put("moreContentButton", JSONObject().put("buttonRenderer", button))
        val carousel = JSONObject().put("header", JSONObject().put("musicCarouselShelfBasicHeaderRenderer", header))
            .put("contents", JSONArray())
        return JSONObject().put("musicCarouselShelfRenderer", carousel)
    }

    private fun release(id: String, title: String, type: String): JSONObject = JSONObject()
        .put("musicTwoRowItemRenderer", JSONObject()
            .put("title", text(title))
            .put("subtitle", text("$type • 2026"))
            .put("navigationEndpoint", endpoint(id, "release-params", "MUSIC_PAGE_TYPE_ALBUM")))

    private fun endpoint(id: String, params: String, pageType: String = "MUSIC_PAGE_TYPE_PLAYLIST"): JSONObject = JSONObject()
        .put("browseEndpoint", JSONObject().put("browseId", id).put("params", params)
            .put("browseEndpointContextSupportedConfigs", JSONObject()
                .put("browseEndpointContextMusicConfig", JSONObject().put("pageType", pageType))))

    private fun track(id: String, title: String, plays: String?): JSONObject {
        val columns = JSONArray()
            .put(column(text(title)))
            .put(column(text("Artist")))
        columns.put(column(text(plays ?: "")))
        columns.put(column(JSONObject().put("runs", JSONArray().put(JSONObject()
            .put("text", "The album")
            .put("navigationEndpoint", endpoint("MPRE_album", "", "MUSIC_PAGE_TYPE_ALBUM"))))))
        return JSONObject().put("musicResponsiveListItemRenderer", JSONObject()
            .put("playlistItemData", JSONObject().put("videoId", id))
            .put("flexColumns", columns)
            .put("fixedColumns", JSONArray().put(JSONObject()
                .put("musicResponsiveListItemFixedColumnRenderer", JSONObject().put("text", text("3:20"))))))
    }

    private fun column(value: JSONObject): JSONObject = JSONObject()
        .put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", value))

    private fun continuationItem(token: String): JSONObject = JSONObject()
        .put("continuationItemRenderer", JSONObject().put("continuationEndpoint", JSONObject()
            .put("continuationCommand", JSONObject().put("token", token).put("request", "CONTINUATION_REQUEST_TYPE_BROWSE"))))

    private fun text(value: String): JSONObject = JSONObject().put("runs", JSONArray().put(JSONObject().put("text", value)))
}
