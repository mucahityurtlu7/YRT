package com.yrt.music

import com.yrt.music.data.model.DiscoveryItemKind
import com.yrt.music.data.network.YouTubeMusicDiscoveryClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DiscoveryClientTest {
    @Test
    fun publicExploreUsesTurkishRegionAndKeepsCategoryFiltersAndMoreDestinations() {
        val grid = JSONObject().put("gridRenderer", JSONObject().put("items", JSONArray()
            .put(category("Egzersiz", "workout-filter")).put(category("Uyku", "sleep-filter"))))
        val shelf = JSONObject().put("musicCarouselShelfRenderer", JSONObject()
            .put("header", JSONObject().put("musicCarouselShelfBasicHeaderRenderer", JSONObject()
                .put("title", text("Yeni çıkanlar"))
                .put("moreContentButton", JSONObject().put("buttonRenderer", JSONObject()
                    .put("navigationEndpoint", endpoint("FEmusic_new_releases_albums", "all-releases"))))))
            .put("contents", JSONArray().put(twoRow("MPRE_album", "Album", "MUSIC_PAGE_TYPE_ALBUM"))))
        var requests = 0
        val page = YouTubeMusicDiscoveryClient.loadPage("FEmusic_explore") { request ->
            requests++
            assertEquals("FEmusic_explore", request.getString("browseId"))
            val client = request.getJSONObject("context").getJSONObject("client")
            assertEquals("tr", client.getString("hl"))
            assertEquals("TR", client.getString("gl"))
            assertFalse(client.has("visitorData"))
            page(grid, shelf)
        }
        assertEquals(1, requests)
        assertEquals("Keşfet", page.title)
        assertEquals(listOf("workout-filter", "sleep-filter"), page.sections[0].items.map { it.params })
        assertEquals(2, page.sections[0].items.map { it.id }.toSet().size)
        assertTrue(page.sections[0].items.all { it.kind == DiscoveryItemKind.CATEGORY })
        assertEquals("FEmusic_new_releases_albums", page.sections[1].browseId)
        assertEquals("all-releases", page.sections[1].params)
        assertEquals(DiscoveryItemKind.ALBUM, page.sections[1].items.single().kind)
    }

    @Test
    fun actualTwoRowAndResponsiveShapesProducePlayableTracksAndPrimaryDestinations() {
        val album = twoRow("MPRE_album", "Album", "MUSIC_PAGE_TYPE_ALBUM")
        album.getJSONObject("musicTwoRowItemRenderer").put("subtitle", JSONObject().put("runs", JSONArray()
            .put(JSONObject().put("text", "Single • "))
            .put(JSONObject().put("text", "Singer").put("navigationEndpoint", endpoint("UC_artist")))))
        val video = JSONObject().put("musicTwoRowItemRenderer", JSONObject().put("title", text("Video"))
            .put("subtitle", text("Video Singer • 10 Mn görüntüleme"))
            .put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", "video-2"))))
        val track = JSONObject().put("musicResponsiveListItemRenderer", JSONObject()
            .put("playlistItemData", JSONObject().put("videoId", "video-1"))
            .put("flexColumns", JSONArray().put(flex(text("Song"))).put(flex(JSONObject().put("runs", JSONArray()
                .put(JSONObject().put("text", "Singer").put("navigationEndpoint", endpoint("UC_artist")))
                .put(JSONObject().put("text", " • 20 Mn görüntüleme"))))))
            .put("fixedColumns", JSONArray().put(JSONObject().put("musicResponsiveListItemFixedColumnRenderer", JSONObject()
                .put("text", text("3:25"))))))
        val page = YouTubeMusicDiscoveryClient.loadPage("FEmusic_charts") {
            page(JSONObject().put("musicCarouselShelfRenderer", JSONObject().put("contents", JSONArray()
                .put(album).put(twoRow("UC_artist", "Singer", "MUSIC_PAGE_TYPE_ARTIST"))
                .put(twoRow("VL_playlist", "Playlist", "MUSIC_PAGE_TYPE_PLAYLIST")).put(video).put(track))))
        }
        val items = page.sections.single().items
        assertEquals(listOf(DiscoveryItemKind.ALBUM, DiscoveryItemKind.ARTIST, DiscoveryItemKind.PLAYLIST,
            DiscoveryItemKind.TRACK, DiscoveryItemKind.TRACK), items.map { it.kind })
        assertEquals("MPRE_album", items[0].browseId)
        assertEquals("video-2", items[3].track?.videoId)
        assertEquals("video-1", items[4].track?.videoId)
        assertEquals("Singer", items[4].track?.artist)
        assertEquals(205_000L, items[4].track?.durationMs)
    }

    @Test
    fun legacyGridContinuationCompletesTheListAndForwardsInitialBrowseParams() {
        val calls = mutableListOf<String>()
        val result = YouTubeMusicDiscoveryClient.loadPage("FEmusic_new_releases_albums", "exact-filter") { request ->
            if (!request.has("continuation")) {
                assertEquals("exact-filter", request.getString("params"))
                calls += "initial"
                page(JSONObject().put("gridRenderer", JSONObject().put("items", JSONArray()
                    .put(twoRow("MPRE_first", "First", "MUSIC_PAGE_TYPE_ALBUM")))
                    .put("continuations", JSONArray().put(JSONObject().put("nextContinuationData",
                        JSONObject().put("continuation", "next"))))))
            } else {
                calls += request.getString("continuation")
                assertFalse(request.has("browseId"))
                assertFalse(request.has("params"))
                JSONObject().put("continuationContents", JSONObject().put("gridContinuation", JSONObject()
                    .put("items", JSONArray().put(twoRow("MPRE_second", "Second", "MUSIC_PAGE_TYPE_ALBUM")))))
            }
        }
        assertEquals(listOf("initial", "next"), calls)
        assertEquals(listOf("First", "Second"), result.sections.single().items.map { it.title })
    }

    @Test
    fun selectedContentExcludesOtherTabsAndMenuItems() {
        val selected = page(JSONObject().put("musicShelfRenderer", JSONObject().put("title", text("Selected"))
            .put("contents", JSONArray().put(twoRow("MPRE_selected", "Selected", "MUSIC_PAGE_TYPE_ALBUM")))))
        val tabs = selected.getJSONObject("contents").getJSONObject("singleColumnBrowseResultsRenderer").getJSONArray("tabs")
        tabs.put(JSONObject().put("tabRenderer", JSONObject().put("selected", false).put("content", JSONObject()
            .put("sectionListRenderer", JSONObject().put("contents", JSONArray().put(JSONObject().put("gridRenderer", JSONObject()
                .put("items", JSONArray().put(twoRow("MPRE_other", "Other", "MUSIC_PAGE_TYPE_ALBUM"))))))))))
        selected.put("menu", JSONObject().put("gridRenderer", JSONObject().put("items", JSONArray()
            .put(twoRow("MPRE_menu", "Menu", "MUSIC_PAGE_TYPE_ALBUM")))))
        val result = YouTubeMusicDiscoveryClient.loadPage("FEmusic_explore") { selected }
        assertEquals(listOf("Selected"), result.sections.flatMap { it.items }.map { it.title })
    }

    @Test
    fun invalidOrServerErrorResponseFailsInsteadOfInventingContent() {
        for (response in listOf(JSONObject().put("responseContext", JSONObject()),
            JSONObject().put("error", JSONObject().put("code", 403)))) {
            try {
                YouTubeMusicDiscoveryClient.loadPage("FEmusic_explore") { response }
                fail("Unsupported and server-error responses must fail")
            } catch (_: IOException) { }
        }
    }

    @Test
    fun pageLevelContinuationActionsKeepFollowingNewShelves() {
        fun shelf(id: String) = JSONObject().put("gridRenderer", JSONObject()
            .put("items", JSONArray().put(twoRow(id, id, "MUSIC_PAGE_TYPE_ALBUM"))))
        fun next(token: String) = JSONObject().put("continuationItemRenderer", JSONObject()
            .put("continuationEndpoint", JSONObject().put("continuationCommand", JSONObject().put("token", token))))
        fun append(vararg entries: JSONObject) = JSONObject().put("onResponseReceivedActions", JSONArray().put(JSONObject()
            .put("appendContinuationItemsAction", JSONObject().put("continuationItems", JSONArray(entries.toList())))))
        val initial = page(shelf("MPRE_first"), next("page-two"))
        val requests = mutableListOf<String>()
        val result = YouTubeMusicDiscoveryClient.loadPage("FEmusic_explore") { request ->
            val token = request.optString("continuation", "initial")
            requests += token
            when (token) {
                "initial" -> initial
                "page-two" -> append(shelf("MPRE_second"), next("page-three"))
                "page-three" -> append(shelf("MPRE_third"))
                else -> error("Unexpected continuation")
            }
        }
        assertEquals(listOf("initial", "page-two", "page-three"), requests)
        assertEquals(listOf("MPRE_first:", "MPRE_second:", "MPRE_third:"),
            result.sections.flatMap { it.items }.map { it.id })
    }

    private fun page(vararg sections: JSONObject) = JSONObject().put("contents", JSONObject()
        .put("singleColumnBrowseResultsRenderer", JSONObject().put("tabs", JSONArray().put(JSONObject()
            .put("tabRenderer", JSONObject().put("selected", true).put("content", JSONObject()
                .put("sectionListRenderer", JSONObject().put("contents", JSONArray(sections.toList())))))))))
    private fun text(value: String) = JSONObject().put("runs", JSONArray().put(JSONObject().put("text", value)))
    private fun endpoint(id: String, params: String? = null, type: String? = null) = JSONObject().put("browseEndpoint", JSONObject()
        .put("browseId", id).apply {
            params?.let { put("params", it) }
            type?.let { put("browseEndpointContextSupportedConfigs", JSONObject()
                .put("browseEndpointContextMusicConfig", JSONObject().put("pageType", it))) }
        })
    private fun category(title: String, params: String) = JSONObject().put("musicNavigationButtonRenderer", JSONObject()
        .put("buttonText", text(title)).put("clickCommand", endpoint("FEmusic_moods_and_genres_category", params)))
    private fun twoRow(id: String, title: String, type: String) = JSONObject().put("musicTwoRowItemRenderer", JSONObject()
        .put("title", text(title)).put("navigationEndpoint", endpoint(id, type = type)))
    private fun flex(text: JSONObject) = JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", text))
}
