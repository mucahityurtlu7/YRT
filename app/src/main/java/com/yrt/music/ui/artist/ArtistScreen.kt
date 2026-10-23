package com.yrt.music.ui.artist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.ui.components.AlbumCard
import com.yrt.music.ui.components.ArtistHeroHeader
import com.yrt.music.ui.components.StreamingTrackRow
import com.yrt.music.ui.components.isCurrentRemoteTrack

@Composable
fun ArtistScreen(
    artistId: String,
    vm: ArtistViewModel,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    currentMediaId: String? = null,
    isPlaying: Boolean = false,
    onBackClick: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onPlaySongs: (List<Song>, Int) -> Unit = { _, _ -> },
    onPlayTracks: (List<RemoteTrack>, Int) -> Unit = { _, _ -> },
) {
    val state by vm.uiState.collectAsState()
    val activeTasks by vm.tasks.collectAsState()
    val localSongs by vm.localSongs.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val artistListState = rememberLazyListState()
    val allSongsListState = rememberLazyListState()
    var showAllSongs by rememberSaveable(artistId) { mutableStateOf(false) }

    LaunchedEffect(artistId) { vm.loadArtist(artistId) }
    LaunchedEffect(artistId, showAllSongs, state.artist?.browseId) {
        if (showAllSongs && state.artist?.browseId == artistId) vm.loadAllSongs()
    }
    LaunchedEffect(vm) {
        vm.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    BackHandler(enabled = showAllSongs) { showAllSongs = false }

    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.isLoading -> ArtistContentMessage(
                message = "Sanatçı profili yükleniyor…",
                isLoading = true,
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            state.errorMessage != null && state.artist == null -> ArtistContentMessage(
                message = state.errorMessage ?: "Sanatçı bilgileri alınamadı",
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                onRetry = { vm.loadArtist(artistId, forceRefresh = true) },
            )
            state.artist != null -> {
                val artist = state.artist!!
                if (showAllSongs) {
                    Column(Modifier.fillMaxSize()) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.statusBarsPadding()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(onClick = { showAllSongs = false }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Sanatçıya dön")
                                    }
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(
                                            "Tüm Şarkılar",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            artist.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Text(
                                    text = if (state.isLoadingAllSongs) {
                                        "Şarkılar yükleniyor…"
                                    } else {
                                        "${state.allSongs.size} parça · En popülerden en az popülere"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 18.dp),
                                )
                                if (state.allSongs.any { it.viewCount == null }) {
                                    Text(
                                        text = "Dinlenme bilgisi olmayan parçalar sonda",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                                    )
                                }
                                if (state.isLoadingAllSongs) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                        when {
                            state.allSongs.isEmpty() && state.isLoadingAllSongs -> ArtistContentMessage(
                                message = "Sanatçının tüm şarkıları getiriliyor…",
                                isLoading = true,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                            state.allSongs.isEmpty() && state.allSongsError != null -> ArtistContentMessage(
                                message = state.allSongsError ?: "Şarkılar alınamadı",
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                onRetry = { vm.loadAllSongs() },
                            )
                            state.allSongs.isEmpty() -> ArtistContentMessage(
                                message = "Bu sanatçı için henüz şarkı bulunamadı.",
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                onRetry = { vm.loadAllSongs() },
                            )
                            else -> LazyColumn(
                                modifier = Modifier.weight(1f),
                                state = allSongsListState,
                                contentPadding = PaddingValues(
                                    top = 12.dp,
                                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                                ),
                            ) {
                                itemsIndexed(state.allSongs, key = { _, track -> track.videoId }) { index, track ->
                                    ArtistTrackRow(
                                        track = track,
                                        rank = index + 1,
                                        vm = vm,
                                        localSongs = localSongs,
                                        activeTasks = activeTasks,
                                        currentMediaId = currentMediaId,
                                        isPlaying = isPlaying,
                                        onPlayClick = { onPlayTracks(state.allSongs, index) },
                                    )
                                }
                                if (state.allSongsError != null) {
                                    item {
                                        ArtistContentMessage(
                                            message = state.allSongsError ?: "Bazı şarkılar yüklenemedi",
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            onRetry = { vm.loadAllSongs() },
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = artistListState,
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding() + 24.dp,
                        ),
                    ) {
                        item {
                            ArtistHeroHeader(
                                artist = artist,
                                downloadState = state.heroDownloadState,
                                isPreparingDownload = state.isDownloadingAllSongs,
                                onBackClick = onBackClick,
                                onPlayClick = {
                                    onPlayTracks(state.allSongs.ifEmpty { artist.topSongs }, 0)
                                },
                                onDownloadAllClick = { vm.downloadAllSongs() },
                            )
                        }
                        item {
                            SectionHeader(title = "Popüler Parçalar", onMoreClick = { showAllSongs = true })
                        }
                        itemsIndexed(artist.topSongs.take(5), key = { _, track -> track.videoId }) { index, track ->
                            ArtistTrackRow(
                                track = track,
                                rank = index + 1,
                                vm = vm,
                                localSongs = localSongs,
                                activeTasks = activeTasks,
                                currentMediaId = currentMediaId,
                                isPlaying = isPlaying,
                                onPlayClick = { onPlayTracks(artist.topSongs, index) },
                            )
                        }
                        if (artist.albums.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(22.dp))
                                SectionHeader(title = "Diskografi", count = artist.albums.size)
                                Text(
                                    "Albümler, EP'ler ve single'lar",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, bottom = 14.dp),
                                )
                            }
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(artist.albums, key = { it.browseId }) { album ->
                                        AlbumCard(album = album, onClick = { onAlbumClick(album.browseId) })
                                    }
                                }
                            }
                        }
                        if (!artist.description.isNullOrBlank()) {
                            item {
                                Spacer(Modifier.height(26.dp))
                                SectionHeader(title = "Sanatçı Hakkında")
                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = artist.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding() + 8.dp),
        )
    }
}

@Composable
private fun ArtistTrackRow(
    track: RemoteTrack,
    rank: Int,
    vm: ArtistViewModel,
    localSongs: List<Song>,
    activeTasks: List<DownloadTask>,
    currentMediaId: String?,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
) {
    val localSong = remember(track.videoId, localSongs) { vm.getSongForTrack(track.videoId) }
    val downloadState = remember(track.videoId, localSongs, activeTasks) { vm.resolveDownloadState(track.videoId) }
    val isCurrent = isCurrentRemoteTrack(track, localSong?.id, currentMediaId)
    StreamingTrackRow(
        track = track.copy(index = rank),
        downloadState = downloadState,
        isCurrent = isCurrent,
        isPlaying = isCurrent && isPlaying,
        onClick = onPlayClick,
        onDownloadClick = { vm.downloadTrack(track) },
    )
}

@Composable
private fun ArtistContentMessage(
    message: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            } else {
                Icon(
                    imageVector = if (onRetry != null) Icons.Default.ErrorOutline else Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onRetry != null) Button(onClick = onRetry) { Text("Tekrar Dene") }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int? = null, onMoreClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (count != null && count > 0) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (onMoreClick != null) {
            TextButton(onClick = onMoreClick) {
                Text("Daha fazla", style = MaterialTheme.typography.labelLarge)
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp).size(16.dp),
                )
            }
        }
    }
}
