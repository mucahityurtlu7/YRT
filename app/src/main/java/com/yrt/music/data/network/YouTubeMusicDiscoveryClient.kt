package com.yrt.music.data.network

import com.yrt.music.data.model.DiscoveryItem
import com.yrt.music.data.model.DiscoveryItemKind
import com.yrt.music.data.model.DiscoveryPage
import com.yrt.music.data.model.DiscoverySection
import com.yrt.music.data.model.RemoteTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Public WEB_REMIX discovery; it does not collect or send Google account credentials. */
object YouTubeMusicDiscoveryClient {
    private const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse"
    private const val MAX_DEPTH = 20
    private const val MAX_PAGES = 200

    private data class Link(val id: String, val params: String?, val pageType: String?)
    private data class Pending(val token: String, val section: Int?)
    private data class Parsed(val sections: List<DiscoverySection>, val continuations: List<Pending>, val recognized: Boolean)

    fun getPage(browseId: String = "FEmusic_explore", params: String? = null): DiscoveryPage =
        loadPage(browseId, params) { body ->
            val token = body.string("continuation")
            val url = if (token == null) BROWSE_URL else {
                val encoded = URLEncoder.encode(token, "UTF-8")
                "$BROWSE_URL?ctoken=$encoded&continuation=$encoded"
            }
            JSONObject(YouTubeMusicClient.postJson(url, body.toString()))
        }

    /** Test seam preserves exact browse parameters and continuation tokens received from Music. */
    internal fun loadPage(
        browseId: String,
        params: String? = null,
        request: (JSONObject) -> JSONObject,
    ): DiscoveryPage {
        require(browseId.isNotBlank()) { "browseId cannot be blank" }
        val root = checked(request(payload(browseId, params)))
        var visitorData = root.optJSONObject("responseContext")?.string("visitorData")
        val title = pageTitle(root) ?: defaultTitle(browseId)
        val first = parse(root, title)
        if (!first.recognized) throw IOException("YouTube Music keşfet yanıtı okunamadı")
        val sections = first.sections.toMutableList()
        val pending = ArrayDeque<Pending>().apply { addAll(first.continuations) }
        val seen = HashSet<String>()
        var pages = 1
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (!seen.add(next.token)) throw IOException("YouTube Music devam sayfasını tekrarladı")
            if (++pages > MAX_PAGES) throw IOException("YouTube Music listesi tamamlanamadı")
            val response = checked(request(payload(continuation = next.token, visitorData = visitorData)))
            response.optJSONObject("responseContext")?.string("visitorData")?.let { visitorData = it }
            val parsed = parse(response, title, continuation = true)
            if (!parsed.recognized) throw IOException("YouTube Music devam sayfası okunamadı")
            if (next.section != null && next.section in sections.indices) {
                // Shelf continuations append to their own shelf, never a recommendation shelf.
                val target = next.section
                val old = sections[target]
                sections[target] = old.copy(items = (old.items + parsed.sections.flatMap { it.items }).distinctBy { it.id })
                pending.addAll(parsed.continuations.map { it.copy(section = target) })
            } else {
                val offset = sections.size
                sections += parsed.sections
                pending.addAll(parsed.continuations.map { it.copy(section = it.section?.plus(offset)) })
            }
        }
        return DiscoveryPage(title, sections.filter { it.items.isNotEmpty() })
    }

    private fun payload(browseId: String? = null, params: String? = null, continuation: String? = null, visitorData: String? = null) = JSONObject().apply {
        put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB_REMIX").put("clientVersion", "1.20240101.01.00")
            .put("hl", "tr").put("gl", "TR").apply { visitorData?.let { put("visitorData", it) } }))
        if (continuation == null) {
            put("browseId", browseId)
            params?.let { put("params", it) }
        } else put("continuation", continuation)
    }

    private fun checked(response: JSONObject): JSONObject {
        if (response.has("error")) throw IOException("YouTube Music içeriği yüklenemedi")
        return response
    }

    private fun parse(root: JSONObject, pageTitle: String, continuation: Boolean = false): Parsed {
        val sections = mutableListOf<DiscoverySection>()
        val pending = mutableListOf<Pending>()
        var recognized = false

        fun addSection(renderer: JSONObject, type: String) {
            recognized = true
            val index = sections.size
            val header = renderer.optJSONObject("header")
                ?.let { it.optJSONObject("musicCarouselShelfBasicHeaderRenderer") ?: it.optJSONObject("gridHeaderRenderer") }
            val title = text(header?.optJSONObject("title")) ?: text(renderer.optJSONObject("title"))
                ?: if (type.startsWith("musicPlaylist") || type.endsWith("Continuation")) pageTitle else "Keşfet"
            val more = header?.optJSONObject("moreContentButton")?.optJSONObject("buttonRenderer")
                ?.optJSONObject("navigationEndpoint")?.let(::browseLink)
                ?: header?.optJSONObject("title")?.let(::textLink)
                ?: renderer.optJSONObject("bottomEndpoint")?.let(::browseLink)
            val items = (renderer.optJSONArray("contents") ?: renderer.optJSONArray("items"))
                .objects().mapNotNull(::parseItem).distinctBy { it.id }
            sections += DiscoverySection("section-$index-${more?.id ?: title}", title, items, more?.id, more?.params)
            continuationTokens(renderer).forEach { pending += Pending(it, index) }
        }

        fun visit(node: Any?, depth: Int) {
            if (depth > MAX_DEPTH) throw IOException("YouTube Music yanıtı çok derin")
            when (node) {
                is JSONArray -> node.objects().forEach { visit(it, depth + 1) }
                is JSONObject -> {
                    val rendererKey = SECTION_KEYS.firstOrNull { node.has(it) }
                    if (rendererKey != null) {
                        node.optJSONObject(rendererKey)?.let { addSection(it, rendererKey) }
                        return
                    }
                    node.optJSONObject("tabRenderer")?.let { tab ->
                        // Only the current tab is page content; other tabs are navigation links.
                        if (tab.optBoolean("selected", true)) visit(tab.optJSONObject("content"), depth + 1)
                        return
                    }
                    node.optJSONObject("sectionListRenderer")?.let { list ->
                        recognized = true
                        visit(list.optJSONArray("contents"), depth + 1)
                        continuationTokens(list).forEach { pending += Pending(it, null) }
                        return
                    }
                    node.optJSONObject("sectionListContinuation")?.let { list ->
                        recognized = true
                        visit(list.optJSONArray("contents"), depth + 1)
                        continuationTokens(list).forEach { pending += Pending(it, null) }
                        return
                    }
                    // These wrappers contain only page content, avoiding menu/overlay duplicates.
                    for (key in WRAPPER_KEYS) {
                        val child = node.opt(key)
                        if (child is JSONObject || child is JSONArray) visit(child, depth + 1)
                    }
                    val action = node.optJSONObject("appendContinuationItemsAction")
                        ?: node.optJSONObject("reloadContinuationItemsCommand")
                    action?.let {
                        val rows = it.optJSONArray("continuationItems") ?: return@let
                        recognized = true
                        if (rows.objects().any { row -> SECTION_KEYS.any(row::has) }) {
                            visit(rows, depth + 1)
                            continuationTokens(JSONObject().put("contents", rows)).forEach { token ->
                                pending += Pending(token, null)
                            }
                        } else addSection(JSONObject().put("contents", rows), "musicShelfContinuation")
                    }
                }
            }
        }
        visit(root, 0)
        // Continuation requests must contain a supported response body, not just responseContext.
        if (continuation && !root.has("continuationContents") && !root.has("onResponseReceivedActions") &&
            !root.has("onResponseReceivedCommands")) recognized = false
        return Parsed(sections, pending, recognized)
    }

    private fun parseItem(wrapper: JSONObject): DiscoveryItem? {
        wrapper.optJSONObject("musicNavigationButtonRenderer")?.let { item ->
            val title = text(item.optJSONObject("buttonText")) ?: return null
            val link = item.optJSONObject("clickCommand")?.let(::browseLink) ?: return null
            return DiscoveryItem("${link.id}:${link.params.orEmpty()}", title, kind = DiscoveryItemKind.CATEGORY,
                browseId = link.id, params = link.params)
        }
        val item = wrapper.optJSONObject("musicTwoRowItemRenderer")
            ?: wrapper.optJSONObject("musicResponsiveListItemRenderer") ?: return null
        val titleContainer = item.optJSONObject("title") ?: column(item, "flexColumns", 0)
        val title = text(titleContainer) ?: return null
        val subtitleContainer = item.optJSONObject("subtitle") ?: column(item, "flexColumns", 1)
        val subtitle = text(subtitleContainer)
        val artwork = YouTubeMusicClient.extractThumbnailUrl(item)
        val navigation = item.optJSONObject("navigationEndpoint")
        val link = navigation?.let(::browseLink) ?: titleContainer?.let(::textLink)
        val videoId = item.optJSONObject("playlistItemData")?.string("videoId")
            ?: navigation?.optJSONObject("watchEndpoint")?.string("videoId")
            ?: titleContainer?.optJSONArray("runs").objects().firstNotNullOfOrNull {
                it.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.string("videoId")
            }
        if (videoId != null) {
            val duration = text(column(item, "fixedColumns", 0))
            val artist = subtitleContainer?.optJSONArray("runs").objects().filter {
                it.optJSONObject("navigationEndpoint")?.let(::browseLink)?.let { link ->
                    link.pageType == "MUSIC_PAGE_TYPE_ARTIST" || link.id.startsWith("UC")
                } == true
            }.mapNotNull { it.string("text") }.joinToString(", ").ifBlank { null }
                ?: subtitle?.substringBefore(" • ")
            val track = RemoteTrack(videoId, title, artist = artist, durationMs = YouTubeMusicClient.parseDurationMs(duration),
                durationText = duration, thumbnailUrl = artwork,
                index = text(item.optJSONObject("index"))?.toIntOrNull()
                    ?: text(item.optJSONObject("customIndexColumn")?.optJSONObject("musicCustomIndexColumnRenderer")
                        ?.optJSONObject("text"))?.toIntOrNull())
            return DiscoveryItem(videoId, title, subtitle, artwork, DiscoveryItemKind.TRACK, track = track)
        }
        link ?: return null
        val kind = when {
            link.pageType == "MUSIC_PAGE_TYPE_ARTIST" || link.id.startsWith("UC") -> DiscoveryItemKind.ARTIST
            link.pageType == "MUSIC_PAGE_TYPE_ALBUM" || link.id.startsWith("MPRE") -> DiscoveryItemKind.ALBUM
            link.pageType == "MUSIC_PAGE_TYPE_PLAYLIST" || link.id.startsWith("VL") -> DiscoveryItemKind.PLAYLIST
            else -> DiscoveryItemKind.CATEGORY
        }
        return DiscoveryItem("${link.id}:${link.params.orEmpty()}", title, subtitle, artwork, kind, link.id, link.params)
    }

    private fun browseLink(endpoint: JSONObject): Link? {
        val browse = endpoint.optJSONObject("browseEndpoint") ?: return null
        val id = browse.string("browseId") ?: return null
        return Link(id, browse.string("params"), browse.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")?.string("pageType"))
    }

    private fun textLink(container: JSONObject): Link? = container.optJSONArray("runs").objects()
        .firstNotNullOfOrNull { it.optJSONObject("navigationEndpoint")?.let(::browseLink) }

    private fun column(item: JSONObject, key: String, index: Int): JSONObject? {
        val column = item.optJSONArray(key)?.optJSONObject(index) ?: return null
        return (column.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?: column.optJSONObject("musicResponsiveListItemFixedColumnRenderer"))?.optJSONObject("text")
    }

    private fun continuationTokens(renderer: JSONObject): List<String> = buildList {
        renderer.optJSONArray("continuations").objects().forEach { value ->
            (value.optJSONObject("nextContinuationData") ?: value.optJSONObject("reloadContinuationData"))
                ?.string("continuation")?.let(::add)
        }
        (renderer.optJSONArray("contents") ?: renderer.optJSONArray("items")).objects().forEach { value ->
            value.optJSONObject("continuationItemRenderer")?.optJSONObject("continuationEndpoint")
                ?.optJSONObject("continuationCommand")?.string("token")?.let(::add)
        }
    }.distinct()

    private fun pageTitle(root: JSONObject): String? {
        val header = root.optJSONObject("header")
        for (key in listOf("musicHeaderRenderer", "musicDetailHeaderRenderer", "musicResponsiveHeaderRenderer", "musicImmersiveHeaderRenderer")) {
            text(header?.optJSONObject(key)?.optJSONObject("title"))?.let { return it }
        }
        // Current playlists place their header in the left side of a two-column response.
        val tabs = root.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")?.optJSONArray("tabs")
        for (tab in tabs.objects()) {
            val contents = tab.optJSONObject("tabRenderer")?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            for (entry in contents.objects()) {
                text(entry.optJSONObject("musicResponsiveHeaderRenderer")?.optJSONObject("title"))?.let { return it }
            }
        }
        return null
    }

    private fun defaultTitle(id: String): String = when (id) {
        "FEmusic_explore" -> "Keşfet"
        "FEmusic_charts" -> "Listeler"
        "FEmusic_new_releases" -> "Yeni çıkanlar"
        "FEmusic_moods_and_genres" -> "Ruh halleri ve türler"
        else -> "YouTube Music"
    }

    private fun text(value: JSONObject?): String? = value?.let {
        (it.string("simpleText") ?: YouTubeMusicClient.extractRunsText(it)).takeIf(String::isNotBlank)
    }

    private fun JSONObject.string(key: String): String? = optString(key).takeIf { !isNull(key) && it.isNotBlank() }
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
        (0 until length()).mapNotNull(::optJSONObject)

    private val SECTION_KEYS = listOf("musicCarouselShelfRenderer", "musicShelfRenderer", "musicPlaylistShelfRenderer",
        "gridRenderer", "musicShelfContinuation", "musicPlaylistShelfContinuation", "gridContinuation")
    private val WRAPPER_KEYS = listOf("contents", "content", "tabs", "secondaryContents", "singleColumnBrowseResultsRenderer",
        "twoColumnBrowseResultsRenderer", "itemSectionRenderer", "continuationContents", "onResponseReceivedActions", "onResponseReceivedCommands")
}
