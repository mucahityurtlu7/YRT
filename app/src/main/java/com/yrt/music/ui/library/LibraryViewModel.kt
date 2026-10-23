package com.yrt.music.ui.library

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yrt.music.YrtApp
import com.yrt.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the library screen renders, pre-computed off the main thread. */
@Immutable
data class LibraryUiState(
    val all: List<Song> = emptyList(),
    val visible: List<Song> = emptyList(),
    val groups: List<ArtistGroup> = emptyList(),
    val artistCount: Int = 0,
    val totalMs: Long = 0L,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as YrtApp).container.songRepository

    val songs = repository.songs
    var query by mutableStateOf("")

    /**
     * Search filtering and artist grouping are CPU work (regex parsing, sorting), so they run on
     * [Dispatchers.Default] and the UI only ever receives the finished, immutable result.
     */
    val uiState: StateFlow<LibraryUiState> = combine(
        repository.songs,
        snapshotFlow { query.trim() }.distinctUntilChanged(),
    ) { all, q ->
        val visible = filterSongs(all, q)
        val groups = groupSongsByArtist(visible, library = all)
        LibraryUiState(
            all = all,
            visible = visible,
            groups = groups,
            // Without a search the visible groups are the whole library; avoid grouping twice.
            artistCount = if (q.isEmpty()) groups.size else groupSongsByArtist(all).size,
            totalMs = all.sumOf { it.durationMs },
        )
    }
        .conflate()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    var isRefreshing by mutableStateOf(false)
        private set
    private var refreshJob: Job? = null

    fun refresh(): Job {
        refreshJob?.takeIf { it.isActive }?.let { return it }
        return viewModelScope.launch {
            isRefreshing = true
            try {
                repository.refresh()
            } finally {
                isRefreshing = false
            }
        }.also { refreshJob = it }
    }

    fun delete(song: Song) {
        viewModelScope.launch { repository.delete(song) }
    }

    fun deleteAll(songs: Collection<Song>) {
        viewModelScope.launch { repository.deleteAll(songs) }
    }

    fun filter(all: List<Song>): List<Song> = filterSongs(all, query.trim())

    private fun filterSongs(all: List<Song>, q: String): List<Song> {
        if (q.isEmpty()) return all
        return all.filter { it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true) }
    }
}
