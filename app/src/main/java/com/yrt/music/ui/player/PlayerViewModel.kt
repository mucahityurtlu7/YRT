package com.yrt.music.ui.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.yrt.music.YrtApp
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.data.playback.PlaybackEntry
import com.yrt.music.data.playback.buildPlaybackQueue
import com.yrt.music.service.MusicPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val connected: Boolean = false,
    val currentMediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val playWhenReady: Boolean = isPlaying,
    val isBuffering: Boolean = false,
    val isDownloaded: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasMedia get() = currentMediaId != null
}

/** Talks to [MusicPlaybackService] through a Media3 [MediaController]. */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingPlayback: Pair<List<MediaItem>, Int>? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        viewModelScope.launch {
            try {
                val token = SessionToken(app, ComponentName(app, MusicPlaybackService::class.java))
                val future = MediaController.Builder(app, token).buildAsync()
                controllerFuture = future
                val c = future.await()
                controller = c
                c.addListener(listener)
                pendingPlayback?.let { (items, index) ->
                    pendingPlayback = null
                    setQueue(items, index)
                }
                refresh()
                while (isActive) {
                    if (c.isPlaying) refresh()
                    delay(500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = _state.value.copy(errorMessage = "Oynatıcıya bağlanılamadı")
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val md = c.mediaMetadata
        _state.value = PlayerUiState(
            connected = true,
            currentMediaId = item?.mediaId,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            artworkUri = md.artworkUri,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
            playWhenReady = c.playWhenReady,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            isDownloaded = item != null && !item.mediaId.startsWith("youtube:"),
            errorMessage = c.playerError?.let { "Şarkı oynatılamadı. Bağlantınızı kontrol edip tekrar deneyin." },
        )
    }

    fun playSongs(songs: List<Song>, startIndex: Int) {
        if (startIndex !in songs.indices) return
        setQueue(songs.map { it.toMediaItem() }, startIndex)
    }

    /** Play the complete catalog queue, using existing local files when available. */
    fun playTracks(tracks: List<RemoteTrack>, startIndex: Int) {
        if (startIndex !in tracks.indices) return
        val localSongs = (getApplication<Application>() as? YrtApp)?.container?.songRepository
        val items = buildPlaybackQueue(tracks, localSongs).map { entry ->
            when (entry) {
                is PlaybackEntry.Local -> entry.song.toMediaItem()
                is PlaybackEntry.Remote -> entry.track.toMediaItem()
            }
        }
        setQueue(items, startIndex)
    }

    private fun setQueue(items: List<MediaItem>, startIndex: Int) {
        val c = controller
        if (c == null) {
            pendingPlayback = items to startIndex
            return
        }
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.playWhenReady && c.playerError == null) c.pause() else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            if (c.playbackState == Player.STATE_IDLE || c.playerError != null) c.prepare()
            c.play()
        }
    }

    fun next() { controller?.seekToNext() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms); refresh() }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    /** Remove a deleted song from the queue if it is there. */
    fun removeFromQueue(mediaId: String) {
        val c = controller ?: return
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (c.getMediaItemAt(i).mediaId == mediaId) c.removeMediaItem(i)
        }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        pendingPlayback = null
        super.onCleared()
    }
}

private fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(file.absolutePath)
        .setUri(Uri.fromFile(file))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkFile?.let { Uri.fromFile(it) })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()

private fun RemoteTrack.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId("youtube:$videoId")
        .setUri(Uri.Builder().scheme("yrt").authority("youtube").appendPath(videoId).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(thumbnailUrl?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()
