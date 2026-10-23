package com.yrt.music.data.playback

/** A temporary network location. It must never be persisted as a library/download entry. */
data class ResolvedAudioStream(
    val url: String,
    val httpHeaders: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
)

fun interface RemoteStreamResolver : AutoCloseable {
    suspend fun resolve(videoId: String): ResolvedAudioStream

    override fun close() = Unit
}
