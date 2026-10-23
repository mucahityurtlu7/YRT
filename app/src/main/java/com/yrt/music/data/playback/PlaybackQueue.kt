package com.yrt.music.data.playback

import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.LocalSongLookup

/** Queue entries retain catalog order even when only some tracks are downloaded. */
sealed interface PlaybackEntry {
    val mediaId: String

    data class Local(val song: Song) : PlaybackEntry {
        override val mediaId: String get() = song.file.absolutePath
    }

    data class Remote(val track: RemoteTrack) : PlaybackEntry {
        override val mediaId: String get() = "youtube:${track.videoId}"
    }
}

/** Listening never enqueues a download. Missing/deleted local files fall back to streaming. */
fun buildPlaybackQueue(
    tracks: List<RemoteTrack>,
    localSongs: LocalSongLookup?,
): List<PlaybackEntry> = tracks.map { track ->
    val local = localSongs?.getSongByVideoId(track.videoId)?.takeIf { it.file.isFile }
    if (local != null) PlaybackEntry.Local(local) else PlaybackEntry.Remote(track)
}
