package com.yrt.music

import android.app.Application
import com.yrt.music.data.downloader.YtDlpDownloadManager
import com.yrt.music.data.repository.SongRepository
import com.yrt.music.data.repository.DiscoveryRepository
import com.yrt.music.data.repository.DiscoveryService
import com.yrt.music.data.repository.YouTubeMusicRepository
import com.yrt.music.data.repository.YouTubeMusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container – keeps app-wide singletons. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val songRepository = SongRepository(app)
    val downloadManager = YtDlpDownloadManager(app, songRepository, appScope)
    val youTubeMusicRepository: YouTubeMusicService = YouTubeMusicRepository()
    val discoveryRepository: DiscoveryService = DiscoveryRepository()
}

class YrtApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Extracts embedded Python / yt-dlp / FFmpeg on first launch (IO thread)
        container.downloadManager.initAsync()
    }
}
