package com.yrt.music

import com.yrt.music.data.network.YouTubeMusicDiscoveryClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Regressions for the public playlist pagination protocol verified against WEB_REMIX. */
class DiscoveryParserAuditTest {
    @Test
    fun modernPlaylistPagesCarryVisitorAndAppendOnlyToPlaylist() {
        val initial = initialPage(
            shelf("Selected playlist", track("one"), next("page-two")),
            shelf("Related music", track("related")),
        ).put("responseContext", JSONObject().put("visitorData", "visitor-one"))
        val second = appendPage(track("one"), track("two"), next("page-three"))
            .put("responseContext", JSONObject().put("visitorData", "visitor-two"))
        val third = appendPage(track("three"))
        val requests = mutableListOf<JSONObject>()

        val result = YouTubeMusicDiscoveryClient.loadPage("VLplaylist", "exact-params") { body ->
            requests += JSONObject(body.toString())
            when (requests.size) {
                1 -> initial
                2 -> second
                3 -> third
                else -> error("Unexpected extra request")
            }
        }

        assertEquals(3, requests.size)
        assertEquals("VLplaylist", requests[0].getString("browseId"))
        assertEquals("exact-params", requests[0].getString("params"))
        assertEquals("page-two", requests[1].getString("continuation"))
        // Without this response value, live Music playlists repeat their initial 100 tracks.
        assertEquals("visitor-one", requests[1].getJSONObject("context").getJSONObject("client").getString("visitorData"))
        assertEquals("page-three", requests[2].getString("continuation"))
        assertEquals("visitor-two", requests[2].getJSONObject("context").getJSONObject("client").getString("visitorData"))
        assertFalse(requests[1].has("browseId"))
        assertFalse(requests[1].has("params"))
        assertEquals(listOf("one", "two", "three"), result.sections[0].items.map { it.track?.videoId })
        assertEquals(listOf("related"), result.sections[1].items.map { it.track?.videoId })
    }

    @Test
    fun repeatedContinuationFailsInsteadOfReportingAnIncompletePlaylist() {
        var requests = 0
        try {
            YouTubeMusicDiscoveryClient.loadPage("VLplaylist") {
                requests++
                if (requests == 1) initialPage(shelf("Playlist", track("one"), next("repeated")))
                else appendPage(track("two"), next("repeated"))
            }
            throw AssertionError("A repeated continuation must fail")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
        assertEquals(2, requests)
    }

    @Test
    fun missingContinuationContentFailsInsteadOfSilentlyTruncatingPlaylist() {
        var requests = 0
        try {
            YouTubeMusicDiscoveryClient.loadPage("VLplaylist") {
                requests++
                if (requests == 1) initialPage(shelf("Playlist", track("one"), next("next")))
                else JSONObject().put("responseContext", JSONObject().put("visitorData", "visitor"))
            }
            throw AssertionError("Missing continuation content must fail")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
        assertEquals(2, requests)
    }

    @Test
    fun serverErrorOnLaterPageFailsInsteadOfReturningOnlyInitialTracks() {
        var requests = 0
        try {
            YouTubeMusicDiscoveryClient.loadPage("VLplaylist") {
                requests++
                if (requests == 1) initialPage(shelf("Playlist", track("one"), next("next")))
                else JSONObject().put("error", JSONObject().put("code", 503))
            }
            throw AssertionError("A server error must fail the complete request")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
        assertEquals(2, requests)
    }

    private fun initialPage(vararg shelves: JSONObject): JSONObject {
        val content = JSONObject().put("sectionListRenderer", JSONObject().put("contents", JSONArray(shelves.toList())))
        val tab = JSONObject().put("tabRenderer", JSONObject().put("selected", true).put("content", content))
        return JSONObject().put("contents", JSONObject().put("singleColumnBrowseResultsRenderer",
            JSONObject().put("tabs", JSONArray().put(tab))))
    }

    private fun shelf(title: String, vararg rows: JSONObject): JSONObject = JSONObject().put("musicPlaylistShelfRenderer",
        JSONObject().put("title", text(title)).put("contents", JSONArray(rows.toList())))

    private fun appendPage(vararg rows: JSONObject): JSONObject = JSONObject().put("onResponseReceivedActions", JSONArray().put(
        JSONObject().put("appendContinuationItemsAction", JSONObject().put("targetId", "playlist")
            .put("continuationItems", JSONArray(rows.toList())))))

    private fun track(id: String): JSONObject = JSONObject().put("musicResponsiveListItemRenderer", JSONObject()
        .put("flexColumns", JSONArray().put(JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", text(id)))))
        .put("playlistItemData", JSONObject().put("videoId", id)))

    private fun next(token: String): JSONObject = JSONObject().put("continuationItemRenderer", JSONObject()
        .put("continuationEndpoint", JSONObject().put("continuationCommand", JSONObject().put("token", token))))

    private fun text(value: String): JSONObject = JSONObject().put("runs", JSONArray().put(JSONObject().put("text", value)))
}
