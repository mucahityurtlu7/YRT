package com.yrt.music.ui.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yrt.music.data.downloader.YtDlpDownloadManager
import com.yrt.music.data.model.ArtistProfile
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.DownloadBatch
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.data.model.toBulkDownloadRequest
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.data.repository.YouTubeMusicService
import com.yrt.music.ui.components.HeroDownloadState
import com.yrt.music.ui.components.TrackDownloadState
import com.yrt.music.ui.components.resolveTrackDownloadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArtistUiState(
    val isLoading: Boolean = false,
    val artist: ArtistProfile? = null,
    val errorMessage: String? = null,
    val heroDownloadState: HeroDownloadState = HeroDownloadState.Idle,
    val allSongs: List<RemoteTrack> = emptyList(),
    val isLoadingAllSongs: Boolean = false,
    val allSongsError: String? = null,
    val isDownloadingAllSongs: Boolean = false,
)

class ArtistViewModel(
    private val youTubeMusicRepository: YouTubeMusicService,
    val downloadManager: YtDlpDownloadManager,
    val songRepository: LocalSongLookup,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _uiState = MutableStateFlow(ArtistUiState())
    val uiState: StateFlow<ArtistUiState> = _uiState.asStateFlow()

    val tasks: StateFlow<List<DownloadTask>> = downloadManager.tasks
    val localSongs: StateFlow<List<Song>> = songRepository.songs

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var currentArtistId: String? = null
    private var downloadObserverJob: Job? = null
    private var artistLoadJob: Job? = null
    private var catalogLoadJob: Job? = null
    private var downloadAllJob: Job? = null
    private var loadGeneration = 0L
    private val catalogLock = Any()

    fun loadArtist(artistId: String, forceRefresh: Boolean = false): Job {
        val cleanId = artistId.trim()
        if (cleanId.isBlank()) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "Geçersiz sanatçı kimliği") }
            return CompletableDeferred(Unit)
        }

        if (!forceRefresh && currentArtistId == cleanId && _uiState.value.artist != null) {
            return CompletableDeferred(Unit)
        }

        val sameArtist = currentArtistId == cleanId
        currentArtistId = cleanId
        val generation = ++loadGeneration
        artistLoadJob?.cancel()
        catalogLoadJob?.cancel()
        downloadAllJob?.cancel()
        downloadObserverJob?.cancel()
        _uiState.value = ArtistUiState(
            isLoading = true,
            artist = if (sameArtist) _uiState.value.artist else null,
        )

        return scope.launch(ioDispatcher) {
            val result = youTubeMusicRepository.getArtistProfile(cleanId)
            if (generation != loadGeneration) return@launch
            result.fold(
                onSuccess = { profile ->
                    val initialHero = computeHeroDownloadState(
                        profile,
                        downloadManager.batches.value.find { it.id == getArtistBatchId(profile.browseId) },
                        downloadManager.tasks.value,
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            artist = profile,
                            errorMessage = null,
                            heroDownloadState = initialHero,
                            allSongs = if (profile.isCatalogComplete) profile.allSongs.distinctBy { song -> song.videoId } else emptyList(),
                        )
                    }
                    observeHeroDownloadState(profile)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Sanatçı profili yüklenemedi",
                        )
                    }
                }
            )
        }.also { artistLoadJob = it }
    }

    private fun observeHeroDownloadState(artist: ArtistProfile) {
        downloadObserverJob?.cancel()
        val batchId = getArtistBatchId(artist.browseId)

        downloadObserverJob = scope.launch(ioDispatcher) {
            combine(
                downloadManager.observeBatch(batchId),
                downloadManager.tasks,
                songRepository.songs,
            ) { batch, activeTasks, _ ->
                computeHeroDownloadState(_uiState.value.artist ?: artist, batch, activeTasks)
            }.collect { state ->
                _uiState.update {
                    if (it.artist?.browseId == artist.browseId) it.copy(heroDownloadState = state) else it
                }
            }
        }
    }

    private fun computeHeroDownloadState(
        artist: ArtistProfile,
        batch: DownloadBatch?,
        tasks: List<DownloadTask>,
    ): HeroDownloadState {
        // 1. If batch is actively running or queued
        if (batch != null && !batch.isCancelled && !batch.isCompleted) {
            if (batch.runningCount > 0 || batch.queuedCount > 0) {
                return HeroDownloadState.InProgress(batch.progress)
            }
        }

        // A downloaded preview must never mark the entire catalog as downloaded.
        val tracks = if (artist.isCatalogComplete) artist.allSongs else artist.topSongs
        if (artist.isCatalogComplete && tracks.isNotEmpty() && tracks.all { songRepository.hasVideoId(it.videoId) }) {
            return HeroDownloadState.Completed
        }

        val artistVideoIds = tracks.map { it.videoId }.toSet()
        val runningArtistTasks = tasks.filter {
            it.videoId in artistVideoIds && (it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued)
        }
        if (runningArtistTasks.isNotEmpty()) {
            val total = tracks.size.coerceAtLeast(1)
            val doneCount = tracks.count { songRepository.hasVideoId(it.videoId) }
            val runningProgress = runningArtistTasks.sumOf { it.progress.toDouble() }.toFloat()
            val approxProgress = ((doneCount * 100f + runningProgress) / total).coerceIn(0f, 99f)
            return HeroDownloadState.InProgress(approxProgress)
        }

        return HeroDownloadState.Idle
    }

    fun loadAllSongs(): Job = synchronized(catalogLock) {
        val artist = _uiState.value.artist ?: return@synchronized CompletableDeferred(Unit)
        if (artist.isCatalogComplete) return@synchronized CompletableDeferred(Unit)
        catalogLoadJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val generation = loadGeneration
        _uiState.update { it.copy(isLoadingAllSongs = true, allSongsError = null) }
        scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            try {
                val result = youTubeMusicRepository.getArtistCatalog(artist.browseId)
                if (generation != loadGeneration) return@launch
                result.fold(
                    onSuccess = { catalog ->
                        if (!catalog.isCatalogComplete) {
                            _uiState.update { it.copy(allSongsError = "Tüm şarkılar alınamadı. Lütfen tekrar deneyin.") }
                        } else {
                            val songs = catalog.allSongs.filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }
                            val completeArtist = catalog.copy(allSongs = songs)
                            _uiState.update {
                                it.copy(
                                    artist = completeArtist,
                                    allSongs = songs,
                                    allSongsError = null,
                                    heroDownloadState = computeHeroDownloadState(
                                        completeArtist,
                                        downloadManager.batches.value.find { batch -> batch.id == getArtistBatchId(artist.browseId) },
                                        downloadManager.tasks.value,
                                    ),
                                )
                            }
                            observeHeroDownloadState(completeArtist)
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        _uiState.update { it.copy(allSongsError = error.message ?: "Tüm şarkılar yüklenemedi") }
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == loadGeneration) {
                    _uiState.update { it.copy(allSongsError = error.message ?: "Tüm şarkılar yüklenemedi") }
                }
            } finally {
                if (generation == loadGeneration) _uiState.update { it.copy(isLoadingAllSongs = false) }
            }
        }.also { catalogLoadJob = it; it.start() }
    }

    fun downloadAllSongs() {
        val artist = _uiState.value.artist ?: return
        if (_uiState.value.isDownloadingAllSongs) return
        val existingBatch = downloadManager.batches.value.find { it.id == getArtistBatchId(artist.browseId) }
        if (existingBatch != null && !existingBatch.isCancelled &&
            (existingBatch.runningCount > 0 || existingBatch.queuedCount > 0)) return
        if (artist.isCatalogComplete) {
            enqueueArtistCatalog(artist)
            return
        }
        val generation = loadGeneration
        _uiState.update { it.copy(isDownloadingAllSongs = true) }
        downloadAllJob = scope.launch(ioDispatcher) {
            try {
                loadAllSongs().join()
                if (generation != loadGeneration) return@launch
                val catalog = _uiState.value.artist
                if (catalog?.isCatalogComplete == true) {
                    enqueueArtistCatalog(catalog)
                } else {
                    _messages.trySend(_uiState.value.allSongsError ?: "Tüm şarkılar alınamadı. Lütfen tekrar deneyin.")
                }
            } finally {
                if (generation == loadGeneration) _uiState.update { it.copy(isDownloadingAllSongs = false) }
            }
        }
    }

    private fun enqueueArtistCatalog(artist: ArtistProfile) {
        val tracks = artist.allSongs.filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }
        if (tracks.isEmpty()) {
            _messages.trySend("İndirilecek parça bulunamadı")
            return
        }

        val batchId = getArtistBatchId(artist.browseId)
        val requests = tracks.map { it.toBulkDownloadRequest(defaultArtist = artist.name) }
        val result = downloadManager.enqueueBatch(
            batchId = batchId,
            batchTitle = "Sanatçı: ${artist.name}",
            items = requests,
            format = AudioFormat.M4A,
        )

        when {
            result.alreadyDownloadedCount == result.totalRequested -> {
                _messages.trySend("Tüm parçalar zaten indirilmiş")
            }
            result.alreadyDownloadedCount > 0 -> {
                _messages.trySend("${result.enqueuedCount} parça indirme kuyruğuna eklendi (${result.alreadyDownloadedCount} parça zaten mevcut)")
            }
            else -> {
                _messages.trySend("${result.enqueuedCount} parça indirme kuyruğuna eklendi")
            }
        }
    }

    fun downloadTrack(track: RemoteTrack) {
        val artistName = _uiState.value.artist?.name
        val request = track.toBulkDownloadRequest(defaultArtist = artistName)
        downloadManager.enqueueBatch(
            batchId = "track_${track.videoId}",
            batchTitle = track.title,
            items = listOf(request),
            format = AudioFormat.M4A,
        )
        _messages.trySend("\"${track.title}\" indirme kuyruğuna eklendi")
    }

    fun resolveDownloadState(videoId: String): TrackDownloadState {
        return resolveTrackDownloadState(
            videoId = videoId,
            songRepository = songRepository,
            activeTasks = downloadManager.tasks.value,
        )
    }

    fun getDownloadedSongsForArtist(): List<Song> {
        val artist = _uiState.value.artist ?: return emptyList()
        val tracks = if (artist.isCatalogComplete) artist.allSongs else artist.topSongs
        return tracks.distinctBy { it.videoId }.mapNotNull { songRepository.getSongByVideoId(it.videoId) }
    }

    fun getSongForTrack(videoId: String): Song? = songRepository.getSongByVideoId(videoId)

    private fun getArtistBatchId(browseId: String): String = "artist_$browseId"

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
                return ArtistViewModel(
                    youTubeMusicRepository = repository,
                    downloadManager = downloadManager,
                    songRepository = songRepository,
                    ioDispatcher = ioDispatcher,
                    scopeOverride = scopeOverride,
                ) as T
            }
        }
    }
}
