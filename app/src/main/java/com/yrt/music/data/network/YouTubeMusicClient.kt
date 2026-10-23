package com.yrt.music.data.network

import com.yrt.music.data.model.AlbumDetails
import com.yrt.music.data.model.AlbumSearchResult
import com.yrt.music.data.model.ArtistProfile
import com.yrt.music.data.model.ArtistSearchResult
import com.yrt.music.data.model.RemoteTrack
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Direct HTTP client and defensive JSON parser for YouTube Music InnerTube `WEB_REMIX` endpoints.
 * Operates without API keys or user authentication.
 */
object YouTubeMusicClient {

    private const val BASE_URL = "https://music.youtube.com/youtubei/v1"
    private const val SEARCH_URL = "$BASE_URL/search"
    private const val BROWSE_URL = "$BASE_URL/browse"

    // InnerTube filter parameter tokens
    private const val PARAMS_ARTIST = "EgWKAQIgAWoSEAUQAxAKEAkQEBAEEBUQERAO"
    private const val PARAMS_ALBUM = "EgWKAQIYAWoSEAUQAxAKEAkQEBAEEBUQERAO"

    private const val CLIENT_NAME = "WEB_REMIX"
    private const val CLIENT_VERSION = "1.20240101.01.00"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    private val YEAR_REGEX = Pattern.compile("""\b(19\d\d|20\d\d)\b""")

    private data class ArtistPage(val profile: ArtistProfile, val pages: List<JSONObject>)
    private data class BrowseLink(val browseId: String, val params: String? = null)
    private val artistPages = ConcurrentHashMap<String, ArtistPage>()

    // =========================================================================
    // HTTP Networking
    // =========================================================================

    /**
     * Executes an HTTP POST with JSON body using standard Java HttpURLConnection.
     */
    fun postJson(endpointUrl: String, jsonPayload: String): String {
        val url = URL(endpointUrl)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.doInput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            conn.setRequestProperty("Origin", "https://music.youtube.com")
            conn.setRequestProperty("Referer", "https://music.youtube.com/")

            conn.outputStream.use { os ->
                os.write(jsonPayload.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val statusCode = conn.responseCode
            val isSuccess = statusCode in 200..299
            val stream = if (isSuccess) conn.inputStream else conn.errorStream
                ?: throw java.io.IOException("HTTP $statusCode with no response stream")

            val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
            if (!isSuccess) throw IOException("YouTube Music returned HTTP $statusCode")
            return response
        } finally {
            conn.disconnect()
        }
    }

    private fun buildClientContext(): JSONObject {
        val client = JSONObject().apply {
            put("clientName", CLIENT_NAME)
            put("clientVersion", CLIENT_VERSION)
            put("hl", "en")
            put("gl", "US")
        }
        return JSONObject().apply {
            put("client", client)
        }
    }

    // =========================================================================
    // Network Operations (Callable from Repository)
    // =========================================================================

    fun searchArtists(query: String): List<ArtistSearchResult> {
        val payload = JSONObject().apply {
            put("context", buildClientContext())
            put("query", query)
            put("params", PARAMS_ARTIST)
        }.toString()

        val jsonResponse = postJson(SEARCH_URL, payload)
        return parseArtistSearchResults(jsonResponse)
    }

    fun searchAlbums(query: String): List<AlbumSearchResult> {
        val payload = JSONObject().apply {
            put("context", buildClientContext())
            put("query", query)
            put("params", PARAMS_ALBUM)
        }.toString()

        val jsonResponse = postJson(SEARCH_URL, payload)
        return parseAlbumSearchResults(jsonResponse)
    }

    fun getArtistProfile(browseId: String): ArtistProfile {
        val root = requestBrowse(browsePayload(BrowseLink(browseId)))
        val page = expandArtistPage(browseId, root, ::requestBrowse)
        artistPages[browseId] = page
        return page.profile
    }

    fun getArtistCatalog(browseId: String): ArtistProfile {
        val source = artistPages[browseId] ?: run {
            val root = requestBrowse(browsePayload(BrowseLink(browseId)))
            expandArtistPage(browseId, root, ::requestBrowse).also { artistPages[browseId] = it }
        }
        if (source.profile.isCatalogComplete) return source.profile
        val catalog = loadArtistCatalog(source.profile, source.pages, ::requestBrowse)
        artistPages[browseId] = source.copy(profile = catalog)
        return catalog
    }

    fun clearArtistCache() {
        artistPages.clear()
    }

    fun getAlbumDetails(browseId: String): AlbumDetails {
        return loadAlbumDetails(BrowseLink(browseId), ::requestBrowse)
    }

    private fun browsePayload(link: BrowseLink? = null, continuation: String? = null) = JSONObject().apply {
        put("context", buildClientContext())
        link?.takeIf { continuation == null }?.let {
            put("browseId", it.browseId)
            it.params?.let { params -> put("params", params) }
        }
        continuation?.let { put("continuation", it) }
    }

    private fun requestBrowse(payload: JSONObject): JSONObject {
        val token = payload.optString("continuation").takeIf { it.isNotBlank() }
        // Legacy shelves use query continuations; current playlists use the same token in the body.
        val url = if (token == null) BROWSE_URL else {
            val encoded = URLEncoder.encode(token, "UTF-8")
            "$BROWSE_URL?ctoken=$encoded&continuation=$encoded"
        }
        return checkedResponse(JSONObject(postJson(url, payload.toString())))
    }

    private fun checkedResponse(response: JSONObject): JSONObject {
        if (response.has("error")) throw IOException("YouTube Music could not load the complete catalog")
        return response
    }

    /** Test seam: requests receive the exact browse parameters or continuation from the response. */
    internal fun loadArtistProfile(
        browseId: String,
        initialResponse: JSONObject,
        request: (JSONObject) -> JSONObject,
    ): ArtistProfile = expandArtistPage(browseId, initialResponse, request).profile

    private fun expandArtistPage(
        browseId: String,
        initialResponse: JSONObject,
        request: (JSONObject) -> JSONObject,
    ): ArtistPage {
        checkedResponse(initialResponse)
        val initialProfile = parseArtistProfile(browseId, initialResponse.toString())
        if (initialProfile.name.isBlank()) throw IOException("Artist profile could not be read")
        val initialSections = renderers(initialResponse, setOf("sectionListRenderer"))
        if (initialSections.isEmpty()) throw IOException("Artist sections could not be read")
        val sections = drainCollections(initialSections, SECTION_COLLECTIONS, BrowseLink(browseId), request)
        val pages = listOf(initialResponse) + sections.drop(initialSections.size)
        val releases = linkedMapOf<String, AlbumSearchResult>()
        val pending = ArrayDeque<BrowseLink>()
        pages.flatMap(::releaseShelves).forEach { shelf ->
            parseReleaseItems(shelf, initialProfile.name, releaseType(shelfTitle(shelf))).forEach {
                releases[it.browseId] = it
            }
            pending.addAll(moreLinks(shelf))
            // A carousel can paginate even when it has no separate More endpoint.
            drainCollections(listOf(shelf), RELEASE_COLLECTIONS, null, request).drop(1).forEach { page ->
                parseReleaseItems(page, initialProfile.name, releaseType(shelfTitle(shelf))).forEach {
                    releases[it.browseId] = it
                }
            }
        }
        val visited = mutableSetOf<BrowseLink>()
        while (pending.isNotEmpty()) {
            val link = pending.removeFirst()
            if (!visited.add(link)) continue // Albums and singles can share an ID but have different params.
            val response = checkedResponse(request(browsePayload(link)))
            val collections = renderers(response, RELEASE_RENDERERS)
            if (collections.isEmpty()) throw IOException("Artist releases could not be read")
            drainCollections(collections, RELEASE_COLLECTIONS, link, request).forEach { collection ->
                parseReleaseItems(collection, initialProfile.name, null).forEach { releases[it.browseId] = it }
            }
        }
        val topSongs = mergeTracks(pages.flatMap(::songShelves).flatMap { parseTracks(it, initialProfile.name) })
        return ArtistPage(
            initialProfile.copy(topSongs = topSongs, allSongs = topSongs, albums = releases.values.toList()),
            pages,
        )
    }

    internal fun loadArtistCatalog(
        profile: ArtistProfile,
        artistPages: List<JSONObject>,
        request: (JSONObject) -> JSONObject,
    ): ArtistProfile {
        val tracks = profile.topSongs.toMutableList()
        val visited = mutableSetOf<BrowseLink>()
        artistPages.flatMap(::songShelves).forEach { shelf ->
            val links = moreLinks(shelf)
            if (links.isEmpty()) {
                drainCollections(listOf(shelf), TRACK_COLLECTIONS, null, request).forEach {
                    tracks += parseTracks(it, profile.name)
                }
            } else links.forEach { link ->
                if (visited.add(link)) {
                    val response = checkedResponse(request(browsePayload(link)))
                    val playlistShelves = renderers(response, setOf("musicPlaylistShelfRenderer"))
                    val collections = playlistShelves.ifEmpty { songShelves(response) }
                    if (collections.isEmpty()) throw IOException("Artist songs could not be read")
                    drainCollections(collections, TRACK_COLLECTIONS, link, request).forEach {
                        tracks += parseTracks(it, profile.name)
                    }
                }
            }
        }
        // The songs playlist can omit releases/versions. Walk every album, single and EP as well.
        profile.albums.distinctBy { it.browseId }.forEach { album ->
            tracks += loadAlbumDetails(BrowseLink(album.browseId, album.browseParams), request).tracks
        }
        val songs = mergeTracks(tracks).sortedByDescending { it.viewCount ?: -1L }
            .mapIndexed { index, track -> track.copy(index = index + 1) }
        return profile.copy(allSongs = songs, isCatalogComplete = true)
    }

    private fun loadAlbumDetails(link: BrowseLink, request: (JSONObject) -> JSONObject): AlbumDetails {
        val root = checkedResponse(request(browsePayload(link)))
        val details = parseAlbumDetails(link.browseId, root.toString())
        val shelf = findTrackShelf(root)?.takeIf(::isSongShelf)
            ?: songShelves(root).firstOrNull()
            ?: throw IOException("Album track list could not be read")
        val tracks = drainCollections(listOf(shelf), TRACK_COLLECTIONS, link, request).flatMap {
            parseTracks(it, details.artist, details.title, details.artworkUrl)
        }.distinctBy { it.videoId }
        return details.copy(tracks = tracks, trackCount = tracks.size)
    }

    private val TRACK_RENDERERS = setOf("musicShelfRenderer", "musicPlaylistShelfRenderer")
    private val TRACK_COLLECTIONS = TRACK_RENDERERS + setOf("musicShelfContinuation", "musicPlaylistShelfContinuation")
    private val RELEASE_RENDERERS = setOf("gridRenderer", "musicCarouselShelfRenderer", "musicShelfRenderer")
    private val RELEASE_COLLECTIONS = RELEASE_RENDERERS + setOf("gridContinuation", "musicCarouselShelfContinuation", "musicShelfContinuation")
    private val SECTION_COLLECTIONS = setOf("sectionListRenderer", "sectionListContinuation")

    /** Drain until the server has no continuation; repeated tokens and malformed pages are failures. */
    private fun drainCollections(
        initial: List<JSONObject>,
        collectionNames: Set<String>,
        link: BrowseLink?,
        request: (JSONObject) -> JSONObject,
    ): List<JSONObject> {
        val pages = initial.toMutableList()
        val targetIds = initial.mapNotNull { it.optString("targetId").takeIf(String::isNotBlank) }.toSet()
        val pending = ArrayDeque<String>()
        initial.forEach { pending.addAll(continuationTokens(it)) }
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val token = pending.removeFirst()
            if (!seen.add(token)) throw IOException("YouTube Music repeated a catalog continuation")
            val response = checkedResponse(request(browsePayload(link, token)))
            val collections = renderers(response, collectionNames).toMutableList()
            listOf("onResponseReceivedActions", "onResponseReceivedEndpoints").forEach { key ->
                val actions = response.optJSONArray(key) ?: return@forEach
                for (index in 0 until actions.length()) {
                    val action = actions.optJSONObject(index) ?: continue
                    val append = action.optJSONObject("appendContinuationItemsAction")
                        ?: action.optJSONObject("reloadContinuationItemsCommand") ?: continue
                    val target = append.optString("targetId")
                    if (targetIds.isNotEmpty() && target.isNotBlank() && target !in targetIds) continue
                    val items = append.optJSONArray("continuationItems")
                    if (items != null) collections += JSONObject().put("contents", items)
                }
            }
            if (collections.isEmpty()) throw IOException("Catalog continuation could not be read")
            collections.forEach {
                pages += it
                pending.addAll(continuationTokens(it))
            }
        }
        return pages
    }

    private fun continuationTokens(collection: JSONObject): List<String> {
        val tokens = linkedSetOf<String>()
        val continuations = collection.optJSONArray("continuations")
        if (continuations != null) for (i in 0 until continuations.length()) {
            continuations.optJSONObject(i)?.optJSONObject("nextContinuationData")
                ?.optString("continuation")?.takeIf { it.isNotBlank() }?.let(tokens::add)
        }
        listOf("contents", "items").forEach { key ->
            val items = collection.optJSONArray(key) ?: return@forEach
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i)?.optJSONObject("continuationItemRenderer") ?: continue
                val endpoint = item.optJSONObject("continuationEndpoint") ?: continue
                val commands = listOf(endpoint) + jsonObjects(endpoint.optJSONObject("commandExecutorCommand")?.optJSONArray("commands"))
                commands.forEach { command ->
                    command.optJSONObject("continuationCommand")?.optString("token")
                        ?.takeIf { it.isNotBlank() }?.let(tokens::add)
                }
            }
        }
        return tokens.toList()
    }

    private fun jsonObjects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)

    private fun renderers(node: Any?, names: Set<String>): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (key in names && child is JSONObject) results += child else visit(child)
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
            }
        }
        visit(node)
        return results
    }

    private fun shelfTitle(shelf: JSONObject): String =
        shelf.optJSONObject("title")?.let(::extractRunsText)
            ?: shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                ?.optJSONObject("title")?.let(::extractRunsText).orEmpty()

    private fun songShelves(root: JSONObject): List<JSONObject> = renderers(root, TRACK_RENDERERS).filter(::isSongShelf)

    private fun isSongShelf(shelf: JSONObject): Boolean {
        val title = shelfTitle(shelf).lowercase()
        if (listOf("recommend", "you might", "related", "similar", "featured", "fans", "video", "öner")
                .any(title::contains)) return false
        return title.isBlank() || title.contains("song") || title.contains("popular") ||
            title.contains("şarkı") || title.contains("popüler")
    }

    private fun releaseType(text: String): String? = when {
        Regex("(?i)\\bEP\\b").containsMatchIn(text) && !text.contains("single", true) -> "EP"
        text.contains("single", true) || text.contains("tekli", true) -> "Single"
        text.contains("album", true) || text.contains("albüm", true) -> "Album"
        else -> null
    }

    private fun releaseShelves(root: JSONObject): List<JSONObject> = renderers(root, RELEASE_RENDERERS).filter {
        val title = shelfTitle(it)
        releaseType(title) != null || (title.isBlank() && parseReleaseItems(it, null, null).isNotEmpty())
    }

    private fun moreLinks(shelf: JSONObject): List<BrowseLink> {
        val links = mutableListOf<BrowseLink>()
        listOf("title", "header", "bottomEndpoint", "moreContentButton", "bottomText").forEach { key ->
            renderers(shelf.opt(key), setOf("browseEndpoint")).forEach {
                val id = it.optString("browseId")
                if (id.isNotBlank()) links += BrowseLink(id, it.optString("params").takeIf(String::isNotBlank))
            }
        }
        return links.distinct()
    }

    private fun parseReleaseItems(collection: JSONObject, artist: String?, fallbackType: String?): List<AlbumSearchResult> =
        renderers(collection, setOf("musicTwoRowItemRenderer", "musicResponsiveListItemRenderer")).mapNotNull { item ->
            val endpoint = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?: renderers(item.optJSONObject("title"), setOf("browseEndpoint")).firstOrNull()
                ?: renderers(item.optJSONArray("flexColumns")?.optJSONObject(0), setOf("browseEndpoint")).firstOrNull()
                ?: return@mapNotNull null
            val id = endpoint.optString("browseId")
            val pageType = endpoint.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
            // Related artists and featured playlists are not this artist's releases.
            if (!id.startsWith("MPRE") && pageType != "MUSIC_PAGE_TYPE_ALBUM") return@mapNotNull null
            val title = item.optJSONObject("title")?.let(::extractRunsText) ?: extractFlexColumnText(item, 0)
            if (title.isBlank()) return@mapNotNull null
            val subtitle = item.optJSONObject("subtitle")?.let(::extractRunsText) ?: extractFlexColumnText(item, 1)
            val year = YEAR_REGEX.matcher(subtitle).let { if (it.find()) it.group(1) else null }
            AlbumSearchResult(id, title, artist, year, extractThumbnailUrl(item), releaseType(subtitle) ?: fallbackType ?: "Album",
                endpoint.optString("params").takeIf(String::isNotBlank))
        }

    private fun parseTracks(collection: JSONObject, artist: String?, album: String? = null, artwork: String? = null): List<RemoteTrack> =
        jsonObjects(collection.optJSONArray("contents")).mapIndexedNotNull { index, item ->
            val row = item.optJSONObject("musicResponsiveListItemRenderer") ?: return@mapIndexedNotNull null
            val videoId = extractVideoId(row) ?: return@mapIndexedNotNull null
            val title = extractFlexColumnText(row, 0).takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val columns = jsonObjects(row.optJSONArray("flexColumns"))
            val runs = columns.drop(1).flatMap { column ->
                jsonObjects(column.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs"))
            }
            val artistRuns = runs.filter { run ->
                run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")?.startsWith("UC") == true
            }
            val artistName = artistRuns.joinToString(" & ") { it.optString("text") }.takeIf { it.isNotBlank() }
                ?: extractFlexColumnText(row, 1).takeIf { it.isNotBlank() && parseViewCount(it) == null } ?: artist
            val albumName = runs.firstOrNull { run ->
                run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")?.startsWith("MPRE") == true
            }?.optString("text") ?: album
            val duration = extractFixedColumnText(row, 0)
            RemoteTrack(videoId, title, artistName, albumName, parseDurationMs(duration), duration.takeIf(String::isNotBlank),
                extractThumbnailUrl(row.optJSONObject("thumbnail")) ?: artwork,
                row.optJSONObject("index")?.let(::extractRunsText)?.toIntOrNull() ?: (index + 1),
                extractViewCount(row))
        }

    private fun extractViewCount(row: JSONObject): Long? =
        jsonObjects(row.optJSONArray("flexColumns")).asSequence().mapNotNull { column ->
            column.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
                ?.let(::extractRunsText)?.let(::parseViewCount)
        }.firstOrNull()

    internal fun parseViewCount(text: String): Long? {
        val match = Regex("(?i)([0-9][0-9,.\\s]*)\\s*([KMB])?\\s*(?:plays?|views?|dinlenme|görüntüleme)\\b")
            .find(text) ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) { "K" -> 1_000L; "M" -> 1_000_000L; "B" -> 1_000_000_000L; else -> 1L }
        val number = match.groupValues[1].replace(" ", "").let {
            if (multiplier > 1) it.replace(",", ".") else it.replace(",", "").replace(".", "")
        }.toDoubleOrNull() ?: return null
        return (number * multiplier).toLong()
    }

    private fun mergeTracks(tracks: List<RemoteTrack>): List<RemoteTrack> {
        val songs = linkedMapOf<String, RemoteTrack>()
        tracks.forEach { track ->
            val old = songs[track.videoId]
            songs[track.videoId] = if (old == null) track else old.copy(
                artist = old.artist ?: track.artist,
                album = old.album ?: track.album,
                durationMs = old.durationMs.takeIf { it > 0 } ?: track.durationMs,
                durationText = old.durationText ?: track.durationText,
                thumbnailUrl = old.thumbnailUrl ?: track.thumbnailUrl,
                viewCount = old.viewCount ?: track.viewCount,
            )
        }
        return songs.values.toList()
    }

    // =========================================================================
    // Defensive JSON Parsers
    // =========================================================================

    /**
     * Parses artist search results from InnerTube search response.
     */
    fun parseArtistSearchResults(jsonString: String): List<ArtistSearchResult> {
        val results = mutableListOf<ArtistSearchResult>()
        if (jsonString.isBlank()) return results

        try {
            val root = JSONObject(jsonString)
            val sectionListContents = findSectionListContents(root) ?: return results

            for (i in 0 until sectionListContents.length()) {
                val section = sectionListContents.optJSONObject(i) ?: continue
                val shelf = section.optJSONObject("musicShelfRenderer") ?: continue

                val items = shelf.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val renderer = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                    val browseId = extractBrowseId(renderer) ?: continue
                    val name = extractFlexColumnText(renderer, 0)
                    if (name.isBlank()) continue

                    val subtitle = extractFlexColumnText(renderer, 1).takeIf { it.isNotBlank() }
                    val thumbnail = extractThumbnailUrl(renderer.optJSONObject("thumbnail"))

                    results.add(
                        ArtistSearchResult(
                            browseId = browseId,
                            name = name,
                            thumbnailUrl = thumbnail,
                            subtitle = subtitle,
                        )
                    )
                }
            }
        } catch (_: JSONException) {
            // Return whatever was successfully parsed
        }

        return results
    }

    /**
     * Parses album search results from InnerTube search response.
     */
    fun parseAlbumSearchResults(jsonString: String): List<AlbumSearchResult> {
        val results = mutableListOf<AlbumSearchResult>()
        if (jsonString.isBlank()) return results

        try {
            val root = JSONObject(jsonString)
            val sectionListContents = findSectionListContents(root) ?: return results

            for (i in 0 until sectionListContents.length()) {
                val section = sectionListContents.optJSONObject(i) ?: continue
                val shelf = section.optJSONObject("musicShelfRenderer") ?: continue

                val items = shelf.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val renderer = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                    val browseId = extractBrowseId(renderer) ?: continue
                    val title = extractFlexColumnText(renderer, 0)
                    if (title.isBlank()) continue

                    // Flex column 1 contains subtitle runs: type, artist, year
                    val (type, artist, year) = extractAlbumSubtitleParts(renderer)
                    val artworkUrl = extractThumbnailUrl(renderer.optJSONObject("thumbnail"))

                    results.add(
                        AlbumSearchResult(
                            browseId = browseId,
                            title = title,
                            artist = artist,
                            year = year,
                            artworkUrl = artworkUrl,
                            type = type,
                        )
                    )
                }
            }
        } catch (_: JSONException) {
            // Return whatever was successfully parsed
        }

        return results
    }

    /**
     * Parses artist profile including hero banner, top songs, and discography carousel.
     */
    fun parseArtistProfile(browseId: String, jsonString: String): ArtistProfile {
        val empty = ArtistProfile(browseId = browseId, name = "", isCatalogComplete = false)
        if (jsonString.isBlank()) return empty
        return try {
            val root = JSONObject(jsonString)
            val header = root.optJSONObject("header")?.let {
                it.optJSONObject("musicImmersiveHeaderRenderer")
                    ?: it.optJSONObject("musicVisualHeaderRenderer")
                    ?: it.optJSONObject("musicResponsiveHeaderRenderer")
            }
            val name = header?.optJSONObject("title")?.let(::extractRunsText).orEmpty()
            val songs = songShelves(root).flatMap { parseTracks(it, name) }.distinctBy { it.videoId }
            val albums = releaseShelves(root).flatMap { parseReleaseItems(it, name, releaseType(shelfTitle(it))) }
                .distinctBy { it.browseId }
            ArtistProfile(
                browseId = browseId,
                name = name,
                heroArtworkUrl = header?.let(::extractThumbnailUrl),
                description = header?.optJSONObject("description")?.let(::extractRunsText)?.takeIf { it.isNotBlank() },
                subscribers = header?.optJSONObject("subscriptionButton")
                    ?.optJSONObject("subscribeButtonRenderer")?.optJSONObject("subscriberCountText")
                    ?.let(::extractRunsText)?.takeIf { it.isNotBlank() },
                topSongs = songs,
                albums = albums,
                allSongs = songs,
                isCatalogComplete = false,
            )
        } catch (_: JSONException) {
            empty
        }
    }
    /**
     * Parses complete album metadata and tracklist.
     */
    fun parseAlbumDetails(browseId: String, jsonString: String): AlbumDetails {
        if (jsonString.isBlank()) return AlbumDetails(browseId = browseId, title = "")

        try {
            val root = JSONObject(jsonString)

            // Multiple potential header locations for live vs mock InnerTube responses
            val detailHeader = root.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
            val respHeaderRoot = root.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
            val respHeaderTabs = root.optJSONObject("contents")
                ?.optJSONObject("twoColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                ?.optJSONObject(0)?.optJSONObject("musicResponsiveHeaderRenderer")

            val header = detailHeader ?: respHeaderRoot ?: respHeaderTabs

            val title = header?.optJSONObject("title")?.let { extractRunsText(it) } ?: ""
            val artworkUrl = header?.let { extractThumbnailUrl(it) }
            val secondSubtitle = header?.optJSONObject("secondSubtitle")?.let { extractRunsText(it) }

            var artist: String? = null
            var artistBrowseId: String? = null
            var year: String? = null

            // Extract artist and browseId from straplineTextOne
            val strapline = header?.optJSONObject("straplineTextOne")
            if (strapline != null) {
                artist = extractRunsText(strapline).takeIf { it.isNotBlank() }
                val runs = strapline.optJSONArray("runs")
                if (runs != null && runs.length() > 0) {
                    artistBrowseId = runs.optJSONObject(0)
                        ?.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId")?.takeIf { it.isNotBlank() }
                }
            }

            // Extract year and fallback artist from subtitle
            val subtitleObj = header?.optJSONObject("subtitle")
            if (subtitleObj != null) {
                val subtitleText = extractRunsText(subtitleObj)
                val matcher = YEAR_REGEX.matcher(subtitleText)
                if (matcher.find()) {
                    year = matcher.group(1)
                }

                if (artist == null) {
                    val runs = subtitleObj.optJSONArray("runs")
                    if (runs != null) {
                        for (k in 0 until runs.length()) {
                            val r = runs.optJSONObject(k) ?: continue
                            val text = r.optString("text", "").trim()
                            val bId = r.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                            if (!bId.isNullOrBlank() && bId.startsWith("UC")) {
                                artist = text
                                artistBrowseId = bId
                                break
                            } else if (text != "Album" && text != "•" && text != "Single" && text != "EP" && !YEAR_REGEX.matcher(text).matches() && text.isNotBlank()) {
                                artist = text
                            }
                        }
                    }
                }
            }

            // Secondary contents contains the tracklist shelf
            val tracks = mutableListOf<RemoteTrack>()
            val shelf = findTrackShelf(root)
            if (shelf != null) {
                val items = shelf.optJSONArray("contents")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val renderer = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                        val videoId = extractVideoId(renderer) ?: continue
                        val trackTitle = extractFlexColumnText(renderer, 0)
                        if (trackTitle.isBlank()) continue

                        val trackArtist = extractFlexColumnText(renderer, 1).takeIf { it.isNotBlank() } ?: artist
                        val durationText = extractFixedColumnText(renderer, 0)
                        val durationMs = parseDurationMs(durationText)

                        val indexText = renderer.optJSONObject("index")?.let { extractRunsText(it) }
                        val index = indexText?.toIntOrNull() ?: (i + 1)
                        val trackThumb = extractThumbnailUrl(renderer.optJSONObject("thumbnail")) ?: artworkUrl

                        tracks.add(
                            RemoteTrack(
                                videoId = videoId,
                                title = trackTitle,
                                artist = trackArtist,
                                album = title,
                                durationMs = durationMs,
                                durationText = durationText.takeIf { it.isNotBlank() },
                                thumbnailUrl = trackThumb,
                                index = index,
                            )
                        )
                    }
                }
            }

            return AlbumDetails(
                browseId = browseId,
                title = title,
                artist = artist,
                artistBrowseId = artistBrowseId,
                year = year,
                artworkUrl = artworkUrl,
                trackCount = tracks.size,
                durationText = secondSubtitle,
                tracks = tracks,
            )
        } catch (_: JSONException) {
            return AlbumDetails(browseId = browseId, title = "")
        }
    }

    // =========================================================================
    // Defensive Helper Utilities
    // =========================================================================

    /**
     * Converts a duration string like "4:08" or "1:02:15" into milliseconds.
     * Returns 0L on null, blank, or invalid format.
     */
    fun parseDurationMs(durationStr: String?): Long {
        if (durationStr.isNullOrBlank()) return 0L
        val clean = durationStr.trim()
        val parts = clean.split(":")
        if (parts.size !in 2..3) return 0L
        return try {
            when (parts.size) {
                2 -> {
                    val min = parts[0].trim().toLong()
                    val sec = parts[1].trim().toLong()
                    (min * 60 + sec) * 1000L
                }
                3 -> {
                    val hrs = parts[0].trim().toLong()
                    val min = parts[1].trim().toLong()
                    val sec = parts[2].trim().toLong()
                    ((hrs * 60 + min) * 60 + sec) * 1000L
                }
                else -> 0L
            }
        } catch (_: NumberFormatException) {
            0L
        }
    }

    fun extractRunsText(container: JSONObject): String {
        val runs = container.optJSONArray("runs") ?: return container.optString("text", "")
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            sb.append(run.optString("text", ""))
        }
        return sb.toString()
    }

    fun extractThumbnailUrl(container: JSONObject?): String? {
        if (container == null) return null
        val thumbnails = findThumbnailsArray(container) ?: return null

        if (thumbnails.length() == 0) return null
        var bestUrl: String? = null
        var maxWidth = -1
        for (i in 0 until thumbnails.length()) {
            val thumb = thumbnails.optJSONObject(i) ?: continue
            val url = thumb.optString("url").takeIf { it.isNotBlank() } ?: continue
            val width = thumb.optInt("width", 0)
            if (width >= maxWidth) {
                maxWidth = width
                bestUrl = url
            }
        }
        val selected = bestUrl ?: thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url")
        return selected?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    private fun findThumbnailsArray(container: JSONObject?, depth: Int = 0): JSONArray? {
        if (container == null || depth > 5) return null
        container.optJSONArray("thumbnails")?.let { return it }

        val candidateKeys = listOf("thumbnail", "thumbnailRenderer", "musicThumbnailRenderer", "croppedMusicThumbnailRenderer")
        for (key in candidateKeys) {
            container.optJSONObject(key)?.let { child ->
                findThumbnailsArray(child, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun extractBrowseId(renderer: JSONObject): String? {
        val direct = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")?.optString("browseId")
        if (!direct.isNullOrBlank()) return direct

        val flex0 = renderer.optJSONArray("flexColumns")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
        return flex0?.takeIf { it.isNotBlank() }
    }

    private fun extractVideoId(renderer: JSONObject): String? {
        // 1. playlistItemData
        val playlistVideoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")
        if (!playlistVideoId.isNullOrBlank()) return playlistVideoId

        // 2. Thumbnail overlay play button
        val overlayVideoId = renderer.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")?.optString("videoId")
        if (!overlayVideoId.isNullOrBlank()) return overlayVideoId

        // 3. FlexColumn 0 watchEndpoint
        val flexVideoId = renderer.optJSONArray("flexColumns")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")
        if (!flexVideoId.isNullOrBlank()) return flexVideoId

        // 4. Direct navigationEndpoint watchEndpoint
        val navVideoId = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")?.optString("videoId")
        return navVideoId?.takeIf { it.isNotBlank() }
    }

    private fun extractFlexColumnText(renderer: JSONObject, index: Int): String {
        val flexColumns = renderer.optJSONArray("flexColumns") ?: return ""
        val column = flexColumns.optJSONObject(index) ?: return ""
        val flexRenderer = column.optJSONObject("musicResponsiveListItemFlexColumnRenderer") ?: return ""
        val textObj = flexRenderer.optJSONObject("text") ?: return ""
        return extractRunsText(textObj)
    }

    private fun extractFixedColumnText(renderer: JSONObject, index: Int): String {
        val fixedColumns = renderer.optJSONArray("fixedColumns") ?: return ""
        val column = fixedColumns.optJSONObject(index) ?: return ""
        val fixedRenderer = column.optJSONObject("musicResponsiveListItemFixedColumnRenderer") ?: return ""
        val textObj = fixedRenderer.optJSONObject("text") ?: return ""
        return extractRunsText(textObj)
    }

    private fun extractAlbumSubtitleParts(renderer: JSONObject): Triple<String?, String?, String?> {
        val flexColumns = renderer.optJSONArray("flexColumns") ?: return Triple(null, null, null)
        if (flexColumns.length() < 2) return Triple(null, null, null)
        val textObj = flexColumns.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text") ?: return Triple(null, null, null)

        val runs = textObj.optJSONArray("runs") ?: return Triple(null, null, null)

        var type: String? = null
        var artist: String? = null
        var year: String? = null

        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val text = run.optString("text", "").trim()
            if (text.isBlank() || text == "•") continue

            val yearMatcher = YEAR_REGEX.matcher(text)
            if (yearMatcher.matches()) {
                year = text
            } else if (text.equals("Album", ignoreCase = true) ||
                text.equals("Single", ignoreCase = true) ||
                text.equals("EP", ignoreCase = true)
            ) {
                type = text
            } else if (artist == null) {
                artist = text
            }
        }

        return Triple(type, artist, year)
    }

    private fun findSectionListContents(root: JSONObject): JSONArray? {
        val tabbed = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
        if (tabbed != null) return tabbed

        return root.optJSONObject("contents")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
    }

    private fun findTrackShelf(root: JSONObject): JSONObject? {
        // Option 1: twoColumnBrowseResultsRenderer secondaryContents
        val secContents = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONObject("secondaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
        if (secContents != null) {
            for (i in 0 until secContents.length()) {
                val sec = secContents.optJSONObject(i) ?: continue
                val shelf = sec.optJSONObject("musicShelfRenderer")
                if (shelf != null) return shelf
            }
        }

        // Option 2: twoColumnBrowseResultsRenderer tabs
        val tabContents = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
        if (tabContents != null) {
            for (i in 0 until tabContents.length()) {
                val sec = tabContents.optJSONObject(i) ?: continue
                val shelf = sec.optJSONObject("musicShelfRenderer")
                if (shelf != null) return shelf
            }
        }

        // Option 3: singleColumnBrowseResultsRenderer
        val singleContents = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
        if (singleContents != null) {
            for (i in 0 until singleContents.length()) {
                val sec = singleContents.optJSONObject(i) ?: continue
                val shelf = sec.optJSONObject("musicShelfRenderer")
                if (shelf != null) return shelf
            }
        }

        return null
    }
}
