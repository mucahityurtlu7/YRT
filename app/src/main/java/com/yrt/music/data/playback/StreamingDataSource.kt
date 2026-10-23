package com.yrt.music.data.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException

/** Resolves only the track ExoPlayer is opening, on its loader thread, with no disk cache. */
@UnstableApi
fun streamingDataSourceFactory(context: Context, resolver: RemoteStreamResolver): DataSource.Factory {
    val upstream = DefaultDataSource.Factory(
        context,
        DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000),
    )
    return ResolvingDataSource.Factory(upstream, object : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            if (dataSpec.uri.scheme != "yrt" || dataSpec.uri.host != "youtube") return dataSpec
            val videoId = dataSpec.uri.lastPathSegment?.takeIf { it.isNotBlank() }
                ?: throw IOException("Şarkı kimliği bulunamadı")
            val stream = try {
                // Media3 calls this on a loader thread; UI and playlist construction stay immediate.
                runBlocking { resolver.resolve(videoId) }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Oynatma iptal edildi").apply { initCause(e) }
            } catch (e: CancellationException) {
                throw InterruptedIOException("Oynatma iptal edildi").apply { initCause(e) }
            } catch (e: Exception) {
                throw IOException("Şarkı için yayın bağlantısı alınamadı", e)
            }
            return dataSpec.withUri(Uri.parse(stream.url)).withAdditionalHeaders(stream.httpHeaders)
        }
    })
}
