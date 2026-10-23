package com.yrt.music.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yrt.music.data.model.AlbumSearchResult
import com.yrt.music.data.model.ArtistSearchResult
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.data.repository.YouTubeMusicService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

enum class SearchFilter(val label: String) {
    ALL("Tümü"),
    ARTISTS("Sanatçılar"),
    ALBUMS("Albümler"),
}

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val isLoading: Boolean = false,
    val artists: List<ArtistSearchResult> = emptyList(),
    val albums: List<AlbumSearchResult> = emptyList(),
    val localTracks: List<Song> = emptyList(),
    val errorMessage: String? = null,
    val hasSearched: Boolean = false,
) {
    val isEmptyResult: Boolean
        get() = hasSearched && !isLoading && artists.isEmpty() && albums.isEmpty() && localTracks.isEmpty() && errorMessage == null
}

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val repository: YouTubeMusicService,
    private val localSongLookup: LocalSongLookup? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _queryFlow = MutableStateFlow("")
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            _queryFlow
                .debounce(300L)
                .distinctUntilChanged()
                .collectLatest { query ->
                    executeSearch(query, _uiState.value.filter)
                }
        }
    }

    fun onQueryChange(newQuery: String) {
        _queryFlow.value = newQuery
        _uiState.update { it.copy(query = newQuery) }
        if (newQuery.isBlank()) {
            clearQuery()
        }
    }

    fun onFilterSelected(filter: SearchFilter): Job? {
        if (_uiState.value.filter == filter) return null
        _uiState.update { it.copy(filter = filter) }
        val currentQuery = _queryFlow.value.trim()
        return if (currentQuery.isNotBlank()) {
            scope.launch {
                executeSearch(currentQuery, filter)
            }
        } else null
    }

    fun clearQuery() {
        _queryFlow.value = ""
        _uiState.update {
            it.copy(
                query = "",
                isLoading = false,
                artists = emptyList(),
                albums = emptyList(),
                localTracks = emptyList(),
                errorMessage = null,
                hasSearched = false,
            )
        }
    }

    fun retry(): Job? {
        val q = _queryFlow.value.trim()
        return if (q.isNotBlank()) {
            scope.launch {
                executeSearch(q, _uiState.value.filter)
            }
        } else null
    }

    fun executeSearchNow(query: String = _queryFlow.value, filter: SearchFilter = _uiState.value.filter): Job {
        _queryFlow.value = query
        return scope.launch {
            executeSearch(query, filter)
        }
    }

    private suspend fun executeSearch(query: String, filter: SearchFilter) {
        val clean = query.trim()
        if (clean.isBlank()) {
            _uiState.update {
                it.copy(
                    query = query,
                    filter = filter,
                    isLoading = false,
                    hasSearched = false,
                    artists = emptyList(),
                    albums = emptyList(),
                    localTracks = emptyList(),
                    errorMessage = null,
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                query = query,
                filter = filter,
                isLoading = true,
                hasSearched = true,
                errorMessage = null,
            )
        }

        try {
            withContext(ioDispatcher) {
                val artistsDeferred = if (filter == SearchFilter.ALL || filter == SearchFilter.ARTISTS) {
                    async { repository.searchArtists(clean) }
                } else null

                val albumsDeferred = if (filter == SearchFilter.ALL || filter == SearchFilter.ALBUMS) {
                    async { repository.searchAlbums(clean) }
                } else null

                val artistResult = artistsDeferred?.await()
                val albumResult = albumsDeferred?.await()

                val localMatches = if (filter == SearchFilter.ALL) {
                    localSongLookup?.songs?.value?.filter { s ->
                        s.title.contains(clean, ignoreCase = true) ||
                            s.artist.contains(clean, ignoreCase = true) ||
                            (s.album?.contains(clean, ignoreCase = true) == true)
                    } ?: emptyList()
                } else emptyList()

                val artists = artistResult?.getOrNull() ?: emptyList()
                val albums = albumResult?.getOrNull() ?: emptyList()

                val isFailed = (artistResult?.isFailure == true && filter == SearchFilter.ARTISTS) ||
                    (albumResult?.isFailure == true && filter == SearchFilter.ALBUMS) ||
                    (artistResult?.isFailure == true && albumResult?.isFailure == true && filter == SearchFilter.ALL)

                val errorMsg = if (isFailed) {
                    val ex = artistResult?.exceptionOrNull() ?: albumResult?.exceptionOrNull()
                    ex?.message ?: "Arama yapılamadı"
                } else null

                _uiState.update {
                    it.copy(
                        query = query,
                        filter = filter,
                        isLoading = false,
                        artists = artists,
                        albums = albums,
                        localTracks = localMatches,
                        errorMessage = errorMsg,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Arama sırasında bir hata oluştu",
                )
            }
        }
    }

    companion object {
        fun provideFactory(
            repository: YouTubeMusicService,
            localSongLookup: LocalSongLookup? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            scopeOverride: CoroutineScope? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SearchViewModel(
                    repository = repository,
                    localSongLookup = localSongLookup,
                    ioDispatcher = ioDispatcher,
                    scopeOverride = scopeOverride,
                ) as T
            }
        }
    }
}
