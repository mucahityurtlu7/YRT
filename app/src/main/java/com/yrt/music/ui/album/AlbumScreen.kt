package com.yrt.music.ui.album

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yrt.music.data.model.AlbumDetails
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.ui.components.Artwork
import com.yrt.music.ui.components.HeroDownloadState
import com.yrt.music.ui.components.StreamingTrackRow
import com.yrt.music.ui.components.createHeroScrim
import com.yrt.music.ui.components.isCurrentRemoteTrack
import com.yrt.music.ui.components.resolveTrackDownloadState

@Composable
fun AlbumScreen(
    albumId: String,
    vm: AlbumViewModel,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    currentMediaId: String? = null,
    isPlaying: Boolean = false,
    onBackClick: () -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaySongs: (List<Song>, Int) -> Unit = { _, _ -> },
    onPlayTracks: (List<RemoteTrack>, Int) -> Unit = { _, _ -> },
) {
    val uiState by vm.uiState.collectAsState()
    val heroDownloadState by vm.heroDownloadState.collectAsState()
    val activeTasks by vm.activeTasks.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(albumId) {
        vm.loadAlbum(albumId)
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val state = uiState) {
            is AlbumUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Albüm yükleniyor…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is AlbumUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { vm.loadAlbum(albumId, forceRefresh = true) }) {
                            Text("Tekrar Dene")
                        }
                    }
                }
            }

            is AlbumUiState.Success -> {
                val album = state.album

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = contentPadding.calculateBottomPadding() + 24.dp
                    ),
                ) {
                    // 1. Album Hero Header
                    item {
                        AlbumHeroHeader(
                            album = album,
                            downloadState = heroDownloadState,
                            onBackClick = onBackClick,
                            onArtistClick = {
                                album.artistBrowseId?.let { onArtistClick(it) }
                            },
                            onPlayClick = { onPlayTracks(album.tracks, 0) },
                            onDownloadClick = { vm.downloadAlbum() },
                        )
                    }

                    // 2. Tracklist Section Header
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Şarkılar",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (album.tracks.isNotEmpty()) {
                                Text(
                                    text = "${album.tracks.size} parça",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // 3. Tracklist Rows
                    itemsIndexed(album.tracks, key = { _, track -> track.videoId }) { index, track ->
                        val localSong = vm.findLocalSong(track.videoId)
                        val isTrackCurrent = isCurrentRemoteTrack(track, localSong?.id, currentMediaId)
                        val trackDownloadState = resolveTrackDownloadState(
                            videoId = track.videoId,
                            songRepository = vm.songRepository,
                            activeTasks = activeTasks,
                        )

                        StreamingTrackRow(
                            track = track,
                            showThumbnail = false,
                            downloadState = trackDownloadState,
                            isCurrent = isTrackCurrent,
                            isPlaying = isTrackCurrent && isPlaying,
                            onClick = { onPlayTracks(album.tracks, index) },
                            onDownloadClick = { vm.downloadTrack(track) },
                            onViewArtistClick = album.artistBrowseId?.let {
                                { onArtistClick(it) }
                            },
                        )
                    }

                    // 4. Album Duration Summary Footer
                    if (!album.durationText.isNullOrBlank()) {
                        item {
                            Text(
                                text = album.durationText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding() + 8.dp),
        )
    }
}

@Composable
private fun AlbumHeroHeader(
    album: AlbumDetails,
    downloadState: HeroDownloadState,
    onBackClick: () -> Unit,
    onArtistClick: () -> Unit,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetBg = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(340.dp),
    ) {
        // Ambient cover background
        if (!album.artworkUrl.isNullOrBlank()) {
            Artwork(
                model = album.artworkUrl,
                corner = 0.dp,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // AMOLED gradient scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(createHeroScrim(targetBg)),
        )

        // Top Navigation Bar with Back Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Geri",
                    tint = Color.White,
                )
            }
        }

        // Hero Info and Action Buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Artwork square
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .shadow(8.dp, RoundedCornerShape(12.dp)),
            ) {
                Artwork(model = album.artworkUrl, corner = 12.dp, modifier = Modifier.fillMaxSize())
            }

            Spacer(Modifier.height(10.dp))

            // Album Title
            Text(
                text = album.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            // Clickable Artist Link & Metadata Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!album.artist.isNullOrBlank()) {
                    Text(
                        text = album.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (album.artistBrowseId != null) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable(enabled = album.artistBrowseId != null, onClick = onArtistClick)
                            .padding(vertical = 2.dp),
                    )
                }

                if (!album.year.isNullOrBlank()) {
                    Text(
                        text = if (!album.artist.isNullOrBlank()) " • ${album.year}" else album.year,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }

                if (album.trackCount > 0) {
                    Text(
                        text = " • ${album.trackCount} şarkı",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action Buttons Row: Play & Download Album
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onPlayClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Oynat", fontWeight = FontWeight.Bold)
                }

                FilledTonalButton(
                    onClick = onDownloadClick,
                    enabled = downloadState !is HeroDownloadState.Completed,
                    shape = CircleShape,
                ) {
                    when (downloadState) {
                        HeroDownloadState.Idle -> {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Albümü İndir")
                        }

                        is HeroDownloadState.InProgress -> {
                            CircularProgressIndicator(
                                progress = { (downloadState.progress / 100f).coerceIn(0f, 1f) },
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("%${downloadState.progress.toInt()} İndiriliyor")
                        }

                        HeroDownloadState.Completed -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("İndirildi")
                        }
                    }
                }
            }
        }
    }
}
