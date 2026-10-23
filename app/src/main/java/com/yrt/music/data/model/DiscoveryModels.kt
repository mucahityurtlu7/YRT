package com.yrt.music.data.model

/** Public, regional YouTube Music browse content. All destinations come from the server. */
data class DiscoveryPage(
    val title: String,
    val sections: List<DiscoverySection>,
)

data class DiscoverySection(
    val id: String,
    val title: String,
    val items: List<DiscoveryItem>,
    /** Optional destination for the section's "more" action. */
    val browseId: String? = null,
    val params: String? = null,
)

enum class DiscoveryItemKind { ARTIST, ALBUM, PLAYLIST, TRACK, CATEGORY }

data class DiscoveryItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val kind: DiscoveryItemKind,
    val browseId: String? = null,
    val params: String? = null,
    val track: RemoteTrack? = null,
)
