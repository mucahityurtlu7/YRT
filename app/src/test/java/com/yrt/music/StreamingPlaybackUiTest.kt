package com.yrt.music

import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.ui.components.isCurrentRemoteTrack
import com.yrt.music.ui.search.playDiscoveryTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StreamingPlaybackUiTest {
    private val remote = RemoteTrack("video-123", "Online song", "Artist")

    private fun localSong(videoId: String = remote.videoId) = Song(
        id = "local-$videoId",
        title = "Downloaded song",
        artist = "Artist",
        durationMs = 200_000L,
        file = File("music/$videoId.m4a"),
        artworkFile = null,
        lastModified = 0L,
        videoId = videoId,
    )

    @Test
    fun undiscoveredLocalFileIsNotRequiredToPlayDiscoveryTrack() {
        var played: RemoteTrack? = null

        playDiscoveryTrack(remote, emptyList(), null) { played = it }

        assertEquals(remote, played)
    }

    @Test
    fun downloadedDiscoveryTrackStillUsesRemotePlaybackCallback() {
        var playedRemote: RemoteTrack? = null
        var playedLocal: Song? = null

        playDiscoveryTrack(
            track = remote,
            localSongs = listOf(localSong()),
            onPlayTrack = { playedLocal = it },
            onPlayRemoteTrack = { playedRemote = it },
        )

        assertEquals(remote, playedRemote)
        assertNull(playedLocal)
    }

    @Test
    fun legacyDiscoveryPlaybackUsesOnlyMatchingLocalSong() {
        val matchingSong = localSong()
        var playedLocal: Song? = null

        playDiscoveryTrack(
            track = remote,
            localSongs = listOf(localSong("other-video"), matchingSong),
            onPlayTrack = { playedLocal = it },
            onPlayRemoteTrack = null,
        )

        assertEquals(matchingSong, playedLocal)
    }

    @Test
    fun legacyDiscoveryPlaybackDoesNotPlayAnUnrelatedDownload() {
        var playedLocal: Song? = null

        playDiscoveryTrack(
            track = remote,
            localSongs = listOf(localSong("other-video")),
            onPlayTrack = { playedLocal = it },
            onPlayRemoteTrack = null,
        )

        assertNull(playedLocal)
    }

    @Test
    fun missingDiscoveryPlaybackCallbacksAreHarmless() {
        playDiscoveryTrack(remote, emptyList(), null, null)
        playDiscoveryTrack(remote, listOf(localSong()), null, null)
    }

    @Test
    fun streamedTrackHighlightsWithoutLocalCopy() {
        assertTrue(isCurrentRemoteTrack(remote, null, "youtube:${remote.videoId}"))
    }

    @Test
    fun streamedTrackKeepsHighlightWhenLocalCopyBecomesAvailable() {
        assertTrue(isCurrentRemoteTrack(remote, localSong().id, "youtube:${remote.videoId}"))
    }

    @Test
    fun locallyPlayedTrackUsesItsLocalMediaId() {
        assertTrue(isCurrentRemoteTrack(remote, localSong().id, localSong().id))
    }

    @Test
    fun unrelatedOrMissingMediaIsNotHighlighted() {
        assertFalse(isCurrentRemoteTrack(remote, null, null))
        assertFalse(isCurrentRemoteTrack(remote, localSong().id, "youtube:other-video"))
        assertFalse(isCurrentRemoteTrack(remote, localSong().id, "other-local-id"))
    }
}
