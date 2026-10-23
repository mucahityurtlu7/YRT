package com.yrt.music.data.model

/**
 * Represents an artist match returned from YouTube Music InnerTube search.
 */
data class ArtistSearchResult(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val subtitle: String? = null,
)

/**
 * Represents an album match returned from YouTube Music InnerTube search or artist discography.
 */
data class AlbumSearchResult(
    val browseId: String,
    val title: String,
    val artist: String? = null,
    val year: String? = null,
    val artworkUrl: String? = null,
    val type: String? = null, // "Album", "Single", "EP"
    val browseParams: String? = null,
)

/**
 * Represents an individual track discovered via InnerTube (top songs or album tracklist).
 */
data class RemoteTrack(
    val videoId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val durationText: String? = null,
    val thumbnailUrl: String? = null,
    val index: Int? = null,
    /** Actual plays/views supplied by YouTube Music, when available. */
    val viewCount: Long? = null,
)

/**
 * Represents complete artist profile details fetched from YouTube Music InnerTube.
 */
data class ArtistProfile(
    val browseId: String,
    val name: String,
    val heroArtworkUrl: String? = null,
    val description: String? = null,
    val subscribers: String? = null,
    val topSongs: List<RemoteTrack> = emptyList(),
    val albums: List<AlbumSearchResult> = emptyList(),
    val allSongs: List<RemoteTrack> = topSongs,
    /** False while [allSongs] still contains only the artist page preview. */
    val isCatalogComplete: Boolean = true,
)

/**
 * Represents complete album metadata and tracklist fetched from YouTube Music InnerTube.
 */
data class AlbumDetails(
    val browseId: String,
    val title: String,
    val artist: String? = null,
    val artistBrowseId: String? = null,
    val year: String? = null,
    val artworkUrl: String? = null,
    val trackCount: Int = 0,
    val durationText: String? = null,
    val tracks: List<RemoteTrack> = emptyList(),
)

/**
 * Convenience mapping from RemoteTrack to BulkDownloadRequest for one-click bulk enqueueing.
 */
fun RemoteTrack.toBulkDownloadRequest(
    defaultArtist: String? = null,
    defaultAlbum: String? = null,
): BulkDownloadRequest = BulkDownloadRequest(
    videoId = videoId,
    title = title,
    artist = artist ?: defaultArtist,
    album = album ?: defaultAlbum,
    thumbnailUrl = thumbnailUrl,
    durationMs = if (durationMs > 0) durationMs else null,
)
