package com.yrt.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.ui.components.ArtistAvatarSize
import com.yrt.music.ui.components.HeroDownloadState
import com.yrt.music.ui.components.TrackDownloadState
import com.yrt.music.ui.components.createHeroScrim
import com.yrt.music.ui.components.formatAlbumSubtitle
import com.yrt.music.ui.components.resolveTrackDownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedStreamingComponentsTest {

    private class FakeSongLookup(
        private val downloadedIds: Set<String> = emptySet(),
    ) : LocalSongLookup {
        override fun hasVideoId(videoId: String): Boolean = downloadedIds.contains(videoId)
        override fun getSongByVideoId(videoId: String): Song? = null
        override val songs: StateFlow<List<Song>> = MutableStateFlow(emptyList())
        override suspend fun refresh() {}
    }

    private fun createTask(
        videoId: String,
        status: DownloadStatus,
        progress: Float = 0f,
        message: String? = null,
    ) = DownloadTask(
        id = "task-$videoId",
        url = "https://youtube.com/watch?v=$videoId",
        title = "Track $videoId",
        format = AudioFormat.M4A,
        status = status,
        progress = progress,
        message = message,
        videoId = videoId,
    )

    @Test
    fun testResolveTrackDownloadState_NullOrBlankVideoId() {
        val lookup = FakeSongLookup(downloadedIds = setOf("vid1"))
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState(null, lookup, emptyList()))
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState("", lookup, emptyList()))
        assertEquals(TrackDownloadState.NotDownloaded, resolveTrackDownloadState("   ", lookup, emptyList()))
    }

    @Test
    fun testResolveTrackDownloadState_AlreadyInLocalRepository() {
        val lookup = FakeSongLookup(downloadedIds = setOf("vid1", "vid2"))
        val tasks = listOf(createTask("vid1", DownloadStatus.Running, progress = 50f))

        // Local repository takes precedence as already downloaded
        val state = resolveTrackDownloadState("vid1", lookup, tasks)
        assertEquals(TrackDownloadState.Downloaded, state)
    }

    @Test
    fun testResolveTrackDownloadState_ActiveTasksQueued() {
        val lookup = FakeSongLookup()
        val tasks = listOf(createTask("vid3", DownloadStatus.Queued))

        val state = resolveTrackDownloadState("vid3", lookup, tasks)
        assertEquals(TrackDownloadState.Queued, state)
    }

    @Test
    fun testResolveTrackDownloadState_ActiveTasksRunning() {
        val lookup = FakeSongLookup()
        val tasks = listOf(createTask("vid4", DownloadStatus.Running, progress = 73.5f))

        val state = resolveTrackDownloadState("vid4", lookup, tasks)
        assertTrue(state is TrackDownloadState.Downloading)
        assertEquals(73.5f, (state as TrackDownloadState.Downloading).progress, 0.001f)
    }

    @Test
    fun testResolveTrackDownloadState_ActiveTasksDone() {
        val lookup = FakeSongLookup()
        val tasks = listOf(createTask("vid5", DownloadStatus.Done, progress = 100f))

        val state = resolveTrackDownloadState("vid5", lookup, tasks)
        assertEquals(TrackDownloadState.Downloaded, state)
    }

    @Test
    fun testResolveTrackDownloadState_ActiveTasksFailed() {
        val lookup = FakeSongLookup()
        val tasks = listOf(createTask("vid6", DownloadStatus.Failed, message = "Network Timeout"))

        val state = resolveTrackDownloadState("vid6", lookup, tasks)
        assertTrue(state is TrackDownloadState.Failed)
        assertEquals("Network Timeout", (state as TrackDownloadState.Failed).message)
    }

    @Test
    fun testResolveTrackDownloadState_ActiveTasksCancelled() {
        val lookup = FakeSongLookup()
        val tasks = listOf(createTask("vid7", DownloadStatus.Cancelled))

        val state = resolveTrackDownloadState("vid7", lookup, tasks)
        assertEquals(TrackDownloadState.NotDownloaded, state)
    }

    @Test
    fun testResolveTrackDownloadState_NoMatchingTask() {
        val lookup = FakeSongLookup()
        val tasks = listOf(createTask("other", DownloadStatus.Running, 10f))

        val state = resolveTrackDownloadState("vid8", lookup, tasks)
        assertEquals(TrackDownloadState.NotDownloaded, state)
    }

    @Test
    fun testFormatAlbumSubtitle() {
        assertEquals("Daft Punk • 2013 • Album", formatAlbumSubtitle("Daft Punk", "2013", "Album"))
        assertEquals("2013 • Single", formatAlbumSubtitle(null, "2013", "Single"))
        assertEquals("Daft Punk • 2013", formatAlbumSubtitle("Daft Punk", "2013", null))
        assertEquals("Daft Punk", formatAlbumSubtitle("Daft Punk", "", ""))
        assertEquals("", formatAlbumSubtitle(null, "", null))
    }

    @Test
    fun testArtistAvatarSizes() {
        assertEquals(40.dp, ArtistAvatarSize.Small.dp)
        assertEquals(56.dp, ArtistAvatarSize.Medium.dp)
        assertEquals(96.dp, ArtistAvatarSize.Large.dp)
        assertEquals(140.dp, ArtistAvatarSize.XLarge.dp)
    }

    @Test
    fun testHeroDownloadStateHierarchy() {
        val idle: HeroDownloadState = HeroDownloadState.Idle
        val inProg: HeroDownloadState = HeroDownloadState.InProgress(42.5f)
        val completed: HeroDownloadState = HeroDownloadState.Completed

        assertTrue(idle is HeroDownloadState.Idle)
        assertTrue(inProg is HeroDownloadState.InProgress)
        assertEquals(42.5f, (inProg as HeroDownloadState.InProgress).progress, 0.001f)
        assertTrue(completed is HeroDownloadState.Completed)
    }

    @Test
    fun testCreateHeroScrim() {
        val scrim = createHeroScrim(Color(0xFF0D0D12))
        assertNotNull(scrim)
    }
}
