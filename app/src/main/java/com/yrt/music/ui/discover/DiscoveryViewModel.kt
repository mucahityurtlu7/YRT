package com.yrt.music.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yrt.music.data.downloader.YtDlpDownloadManager
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.DiscoveryPage
import com.yrt.music.data.model.DiscoverySection
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.toBulkDownloadRequest
import com.yrt.music.data.repository.DiscoveryService
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.ui.components.resolveTrackDownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoveryUiState(
    val isLoading: Boolean = false,
    val page: DiscoveryPage? = null,
    val errorMessage: String? = null,
    val canGoBack: Boolean = false,
)

class DiscoveryViewModel(
    private val repository: DiscoveryService,
    private val downloadManager: YtDlpDownloadManager,
    val songRepository: LocalSongLookup,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private data class Target(val browseId: String, val params: String?, val title: String)
    private val scope get() = scopeOverride ?: viewModelScope
    private val _uiState = MutableStateFlow(DiscoveryUiState())
    val uiState = _uiState.asStateFlow()
    val tasks = downloadManager.tasks
    val localSongs = songRepository.songs
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private var target = Target("FEmusic_explore", null, "Keşfet")
    private val history = mutableListOf<Target>()
    private var loadJob: Job? = null
    private var generation = 0L

    fun ensureLoaded(): Job {
        if (_uiState.value.page != null) return CompletableDeferred(Unit)
        loadJob?.takeIf { it.isActive }?.let { return it }
        return loadPage()
    }

    fun openPage(browseId: String, params: String? = null, title: String = "Keşfet"): Job {
        val cleanId = browseId.trim()
        if (cleanId.isBlank()) return CompletableDeferred(Unit)
        if (target.browseId == cleanId && target.params == params) return ensureLoaded()
        history += target
        target = Target(cleanId, params, title)
        return loadPage()
    }

    fun openSection(section: DiscoverySection): Job = section.browseId?.let {
        openPage(it, section.params, section.title)
    } ?: CompletableDeferred(Unit)

    fun goBack(): Job {
        if (history.isEmpty()) return CompletableDeferred(Unit)
        target = history.removeAt(history.lastIndex)
        return loadPage()
    }

    fun refresh(): Job = loadPage(forceRefresh = true, retainPage = true)
    fun retry(): Job = loadPage(forceRefresh = true)

    private fun loadPage(forceRefresh: Boolean = false, retainPage: Boolean = false): Job {
        val request = target
        val requestGeneration = ++generation
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                isLoading = true,
                page = if (retainPage) it.page else null,
                errorMessage = null,
                canGoBack = history.isNotEmpty(),
            )
        }
        return scope.launch(ioDispatcher) {
            try {
                val result = repository.getPage(request.browseId, request.params, forceRefresh)
                if (requestGeneration != generation) return@launch
                result.fold(
                    onSuccess = { page ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                page = page.copy(title = page.title.takeUnless { title -> title.isBlank() || title == "YouTube Music" } ?: request.title),
                                errorMessage = null,
                            )
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = "YouTube Music şu anda yüklenemedi. Bağlantını kontrol edip tekrar dene.")
                        }
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (requestGeneration == generation) {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "YouTube Music şu anda yüklenemedi. Bağlantını kontrol edip tekrar dene.")
                    }
                }
            }
        }.also { loadJob = it }
    }

    fun downloadTrack(track: RemoteTrack) {
        if (track.videoId.isBlank()) return
        val result = downloadManager.enqueueBatch(
            batchId = "track_${track.videoId}",
            batchTitle = track.title,
            items = listOf(track.toBulkDownloadRequest()),
            format = AudioFormat.M4A,
        )
        _messages.trySend(
            if (result.enqueuedCount > 0) "\"${track.title}\" indirme kuyruğuna eklendi"
            else "Bu parça zaten indirilmiş veya indirme kuyruğunda",
        )
    }

    fun resolveDownloadState(videoId: String) = resolveTrackDownloadState(videoId, songRepository, tasks.value)

    companion object {
        fun provideFactory(
            repository: DiscoveryService,
            downloadManager: YtDlpDownloadManager,
            songRepository: LocalSongLookup,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DiscoveryViewModel(repository, downloadManager, songRepository) as T
        }
    }
}
