package com.yrt.music.service

import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yrt.music.YrtApp
import com.yrt.music.data.playback.YtDlpStreamResolver
import com.yrt.music.data.playback.streamingDataSourceFactory
import java.io.File

/**
 * Foreground playback service. Media3 automatically provides the media notification,
 * lock screen controls and headset / Bluetooth button handling.
 */
@UnstableApi
class MusicPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var streamResolver: YtDlpStreamResolver? = null

    override fun onCreate() {
        super.onCreate()
        val resolver = YtDlpStreamResolver(this) {
            (application as YrtApp).container.downloadManager.awaitReady()
        }
        streamResolver = resolver
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(streamingDataSourceFactory(this, resolver)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        streamResolver?.close()
        streamResolver = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** Resolve stable catalog IDs to lazy streaming URIs; local IDs remain absolute paths. */
    private class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration != null) item
                else if (item.mediaId.startsWith("youtube:")) {
                    val videoId = item.mediaId.removePrefix("youtube:")
                    item.buildUpon().setUri(
                        Uri.Builder().scheme("yrt").authority("youtube").appendPath(videoId).build(),
                    ).build()
                } else item.buildUpon().setUri(Uri.fromFile(File(item.mediaId))).build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
