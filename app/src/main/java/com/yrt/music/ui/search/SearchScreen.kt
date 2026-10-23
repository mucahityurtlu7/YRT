package com.yrt.music.ui.search

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yrt.music.data.model.Song
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.DiscoveryItemKind
import com.yrt.music.ui.components.AlbumCard
import com.yrt.music.ui.components.ArtistAvatar
import com.yrt.music.ui.components.ArtistCard
import com.yrt.music.ui.components.Artwork
import com.yrt.music.ui.components.StreamingTrackRow
import com.yrt.music.ui.components.TrackDownloadState
import com.yrt.music.ui.components.formatAlbumSubtitle
import com.yrt.music.ui.components.resolveTrackDownloadState
import com.yrt.music.ui.discover.DiscoverContent
import com.yrt.music.ui.discover.DiscoveryViewModel

@Composable
fun SearchScreen(
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: SearchViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onPlayTrack: ((Song) -> Unit)? = null,
    discoveryVm: DiscoveryViewModel? = null,
    onPlayRemoteTrack: ((RemoteTrack) -> Unit)? = null,
) {
    val state by vm.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val discoveryState = discoveryVm?.uiState?.collectAsState()?.value
    val discoveryTasks = discoveryVm?.tasks?.collectAsState()?.value.orEmpty()
    val discoverySongs = discoveryVm?.localSongs?.collectAsState()?.value.orEmpty()
    val discoverySnackbar = remember { SnackbarHostState() }
    val showDiscovery = state.query.isBlank() && !state.hasSearched

    LaunchedEffect(discoveryVm, showDiscovery) {
        if (showDiscovery) discoveryVm?.ensureLoaded()
    }
    LaunchedEffect(discoveryVm) {
        discoveryVm?.messages?.collect { discoverySnackbar.showSnackbar(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 8.dp),
    ) {
        // Search Bar Input
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::onQueryChange,
            placeholder = {
                Text(
                    text = "Sanatçı, albüm veya şarkı ara...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Ara",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (state.query.isNotBlank()) {
                    IconButton(onClick = vm::clearQuery) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Temizle",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(8.dp))

        // Filter Chips Row (All, Artists, Albums)
        if (!showDiscovery) Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchFilter.entries.forEach { filter ->
                val selected = filter == state.filter
                FilterChip(
                    selected = selected,
                    onClick = { vm.onFilterSelected(filter) },
                    label = { Text(filter.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Body Content Switching based on state
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = contentPadding.calculateBottomPadding()),
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 48.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.errorMessage != null && state.artists.isEmpty() && state.albums.isEmpty() && state.localTracks.isEmpty() -> {
                    ErrorStateView(
                        message = state.errorMessage ?: "Arama hatası",
                        onRetry = vm::retry,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.isEmptyResult -> {
                    EmptyResultView(
                        query = state.query,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                !state.hasSearched && state.query.isBlank() -> {
                    if (discoveryVm != null && discoveryState != null) {
                        DiscoverContent(
                            state = discoveryState,
                            onItemClick = { item ->
                                focusManager.clearFocus()
                                when (item.kind) {
                                    DiscoveryItemKind.ARTIST -> item.browseId?.let(onArtistClick)
                                    DiscoveryItemKind.ALBUM -> item.browseId?.let(onAlbumClick)
                                    DiscoveryItemKind.TRACK -> item.track?.let { track ->
                                        playDiscoveryTrack(track, discoverySongs, onPlayTrack, onPlayRemoteTrack)
                                    }
                                    else -> item.browseId?.let { discoveryVm.openPage(it, item.params, item.title) }
                                }
                            },
                            onBack = { discoveryVm.goBack() },
                            onRefresh = { discoveryVm.refresh() },
                            onRetry = { discoveryVm.retry() },
                            onMoreClick = { discoveryVm.openSection(it) },
                            onDownloadTrack = discoveryVm::downloadTrack,
                            trackDownloadState = { videoId ->
                                if (discoverySongs.any { it.videoId == videoId }) TrackDownloadState.Downloaded
                                else resolveTrackDownloadState(videoId, discoveryVm.songRepository, discoveryTasks)
                            },
                        )
                    } else {
                        ErrorStateView(
                            message = "Keşfet bağlantısı başlatılamadı",
                            onRetry = { discoveryVm?.ensureLoaded() },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                else -> {
                    SearchResultsList(
                        state = state,
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        onPlayTrack = onPlayTrack,
                    )
                }
            }
            SnackbarHost(discoverySnackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

internal fun playDiscoveryTrack(
    track: RemoteTrack,
    localSongs: List<Song>,
    onPlayTrack: ((Song) -> Unit)?,
    onPlayRemoteTrack: ((RemoteTrack) -> Unit)?,
) {
    if (onPlayRemoteTrack != null) {
        onPlayRemoteTrack(track)
    } else {
        localSongs.find { it.videoId == track.videoId }?.let { onPlayTrack?.invoke(it) }
    }
}

@Composable
private fun SearchResultsList(
    state: SearchUiState,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlayTrack: ((Song) -> Unit)?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        when (state.filter) {
            SearchFilter.ALL -> {
                // 1. Artists shelf
                if (state.artists.isNotEmpty()) {
                    item {
                        SectionHeader(title = "Sanatçılar", count = state.artists.size)
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.artists, key = { it.browseId }) { artist ->
                                ArtistCard(
                                    artist = artist,
                                    onClick = { onArtistClick(artist.browseId) },
                                )
                            }
                        }
                    }
                }

                // 2. Albums shelf
                if (state.albums.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        SectionHeader(title = "Albümler", count = state.albums.size)
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.albums, key = { it.browseId }) { album ->
                                AlbumCard(
                                    album = album,
                                    onClick = { onAlbumClick(album.browseId) },
                                )
                            }
                        }
                    }
                }

                // 3. Local matching songs
                if (state.localTracks.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        SectionHeader(title = "İndirilen Şarkılar", count = state.localTracks.size)
                    }
                    items(state.localTracks, key = { it.id }) { song ->
                        StreamingTrackRow(
                            song = song,
                            onClick = { onPlayTrack?.invoke(song) },
                        )
                    }
                }
            }

            SearchFilter.ARTISTS -> {
                item {
                    SectionHeader(title = "Sanatçılar", count = state.artists.size)
                }
                items(state.artists, key = { it.browseId }) { artist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onArtistClick(artist.browseId) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtistAvatar(artist = artist, size = 56.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = artist.subtitle?.takeIf { it.isNotBlank() } ?: "Sanatçı",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            SearchFilter.ALBUMS -> {
                item {
                    SectionHeader(title = "Albümler", count = state.albums.size)
                }
                items(state.albums, key = { it.browseId }) { album ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAlbumClick(album.browseId) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(model = album.artworkUrl, corner = 8.dp, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = album.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatAlbumSubtitle(album.artist, album.year, album.type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorStateView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Tekrar Dene")
        }
    }
}

@Composable
private fun EmptyResultView(
    query: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "\"$query\" için sonuç bulunamadı",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Yazımı kontrol edin veya başka bir anahtar kelime deneyin.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
