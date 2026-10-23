package com.yrt.music.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yrt.music.data.downloader.YtDlpDownloadManager
import com.yrt.music.data.model.AlbumDetails
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.BatchEnqueueResult
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.data.model.toBulkDownloadRequest
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.data.repository.YouTubeMusicService
import com.yrt.music.ui.components.HeroDownloadState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AlbumUiState {
    data object Loading : AlbumUiState
    data class Success(
        val album: AlbumDetails,
        val currentBatchId: String? = null,
    ) : AlbumUiState
    data class Error(val message: String) : AlbumUiState
}

class AlbumViewModel(
    private val repository: YouTubeMusicService,
    private val downloadManager: YtDlpDownloadManager,
    val songRepository: LocalSongLookup,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _uiState = MutableStateFlow<AlbumUiState>(AlbumUiState.Loading)
    val uiState: StateFlow<AlbumUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _currentBatchId = MutableStateFlow<String?>(null)
    val currentBatchId: StateFlow<String?> = _currentBatchId.asStateFlow()

    val activeTasks: StateFlow<List<DownloadTask>> = downloadManager.tasks
    val localSongs: StateFlow<List<Song>> = songRepository.songs

    private var loadedBrowseId: String? = null

    /**
     * Derives real-time HeroDownloadState by combining album tracks, local repository,
     * downloadManager batches, and tasks.
     */
    val heroDownloadState: StateFlow<HeroDownloadState> = combine(
        _uiState,
        _currentBatchId,
        downloadManager.batches,
        songRepository.songs,
        downloadManager.tasks,
    ) { state, batchId, batches, _, tasks ->
        if (state !is AlbumUiState.Success) return@combine HeroDownloadState.Idle
        val album = state.album
        if (album.tracks.isEmpty()) return@combine HeroDownloadState.Idle

        // Check if all tracks are in local repository
        val allDownloaded = album.tracks.all { track -> songRepository.hasVideoId(track.videoId) }
        if (allDownloaded) return@combine HeroDownloadState.Completed

        // Check active batch if enqueued
        val activeBatch = if (batchId != null) batches.find { it.id == batchId } else null
        if (activeBatch != null) {
            when {
                activeBatch.isCompleted || activeBatch.progress >= 100f -> HeroDownloadState.Completed
                activeBatch.isCancelled -> HeroDownloadState.Idle
                activeBatch.runningCount > 0 || activeBatch.queuedCount > 0 -> HeroDownloadState.InProgress(activeBatch.progress)
                else -> HeroDownloadState.Idle
            }
        } else {
            // Check individual track tasks
            val trackIds = album.tracks.map { it.videoId }.toSet()
            val runningTasks = tasks.filter {
                it.videoId in trackIds && (it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued)
            }
            if (runningTasks.isNotEmpty()) {
                val doneCount = album.tracks.count { songRepository.hasVideoId(it.videoId) }
                val avgProg = runningTasks.map { it.progress }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
                val approxProgress = ((doneCount * 100f + avgProg) / album.tracks.size).coerceIn(0f, 99f)
                HeroDownloadState.InProgress(approxProgress)
            } else {
                HeroDownloadState.Idle
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, HeroDownloadState.Idle)

    fun loadAlbum(browseId: String, forceRefresh: Boolean = false): kotlinx.coroutines.Job {
        val cleanId = browseId.trim()
        if (cleanId.isBlank()) {
            _uiState.value = AlbumUiState.Error("Geçersiz albüm kimliği")
            return kotlinx.coroutines.CompletableDeferred(Unit)
        }

        if (!forceRefresh && loadedBrowseId == cleanId && _uiState.value is AlbumUiState.Success) {
            return kotlinx.coroutines.CompletableDeferred(Unit)
        }

        loadedBrowseId = cleanId
        _uiState.value = AlbumUiState.Loading
        _currentBatchId.value = "album_$cleanId"

        return scope.launch(ioDispatcher) {
            repository.getAlbumDetails(cleanId)
                .onSuccess { details ->
                    _uiState.value = AlbumUiState.Success(album = details, currentBatchId = "album_$cleanId")
                }
                .onFailure { error ->
                    _uiState.value = AlbumUiState.Error(
                        error.message ?: "Albüm bilgileri alınırken bir hata oluştu"
                    )
                }
        }
    }

    fun downloadAlbum(): BatchEnqueueResult? {
        val currentState = _uiState.value as? AlbumUiState.Success ?: return null
        val album = currentState.album
        if (album.tracks.isEmpty()) {
            _messages.tryEmit("İndirilecek parça bulunamadı")
            return null
        }

        val batchId = "album_${album.browseId}"
        _currentBatchId.value = batchId

        val requests = album.tracks.map { track ->
            track.toBulkDownloadRequest(
                defaultArtist = album.artist,
                defaultAlbum = album.title,
            )
        }

        val result = downloadManager.enqueueBatch(
            batchId = batchId,
            batchTitle = "Albüm: ${album.title}",
            items = requests,
            format = AudioFormat.M4A,
        )

        if (result.enqueuedCount == 0 && result.alreadyDownloadedCount > 0) {
            _messages.tryEmit("Tüm parçalar zaten kütüphanenizde mevcut")
        } else if (result.alreadyDownloadedCount > 0) {
            _messages.tryEmit("${result.enqueuedCount} parça kuyruğa eklendi (${result.alreadyDownloadedCount} mevcut parça atlandı)")
        } else {
            _messages.tryEmit("${result.enqueuedCount} parça kuyruğa eklendi")
        }

        return result
    }

    fun downloadTrack(track: RemoteTrack) {
        val currentState = _uiState.value as? AlbumUiState.Success
        val album = currentState?.album

        val req = track.toBulkDownloadRequest(
            defaultArtist = album?.artist,
            defaultAlbum = album?.title,
        )

        downloadManager.enqueueBatch(
            batchId = "track_${track.videoId}",
            batchTitle = track.title,
            items = listOf(req),
            format = AudioFormat.M4A,
        )

        _messages.tryEmit("\"${track.title}\" indirme kuyruğuna eklendi")
    }

    fun findLocalSong(videoId: String): Song? = songRepository.getSongByVideoId(videoId)

    fun getDownloadedAlbumSongs(): List<Song> {
        val currentState = _uiState.value as? AlbumUiState.Success ?: return emptyList()
        return currentState.album.tracks.mapNotNull { songRepository.getSongByVideoId(it.videoId) }
    }

    companion object {
        fun provideFactory(
            repository: YouTubeMusicService,
            downloadManager: YtDlpDownloadManager,
            songRepository: LocalSongLookup,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            scopeOverride: CoroutineScope? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AlbumViewModel(
                    repository = repository,
                    downloadManager = downloadManager,
                    songRepository = songRepository,
                    ioDispatcher = ioDispatcher,
                    scopeOverride = scopeOverride,
                ) as T
            }
        }
    }
}
