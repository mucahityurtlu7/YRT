package com.yrt.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.data.model.Song
import com.yrt.music.data.repository.LocalSongLookup
import com.yrt.music.ui.theme.StreamingColors

sealed interface TrackDownloadState {
    data object NotDownloaded : TrackDownloadState
    data object Queued : TrackDownloadState
    data class Downloading(val progress: Float) : TrackDownloadState
    data object Downloaded : TrackDownloadState
    data class Failed(val message: String? = null) : TrackDownloadState
}

internal fun isCurrentRemoteTrack(
    track: RemoteTrack,
    localSongId: String?,
    currentMediaId: String?,
): Boolean = currentMediaId != null && (
    currentMediaId == "youtube:${track.videoId}" || currentMediaId == localSongId
)

fun resolveTrackDownloadState(
    videoId: String?,
    songRepository: LocalSongLookup?,
    activeTasks: List<DownloadTask>?,
): TrackDownloadState {
    if (videoId.isNullOrBlank()) return TrackDownloadState.NotDownloaded
    if (songRepository?.hasVideoId(videoId) == true) {
        return TrackDownloadState.Downloaded
    }
    val task = activeTasks?.find { it.videoId == videoId } ?: return TrackDownloadState.NotDownloaded
    return when (task.status) {
        DownloadStatus.Queued -> TrackDownloadState.Queued
        DownloadStatus.Running -> TrackDownloadState.Downloading(task.progress)
        DownloadStatus.Done -> TrackDownloadState.Downloaded
        DownloadStatus.Failed -> TrackDownloadState.Failed(task.message)
        DownloadStatus.Cancelled -> TrackDownloadState.NotDownloaded
    }
}

@Composable
fun DownloadStatusBadge(
    state: TrackDownloadState,
    modifier: Modifier = Modifier,
    onDownloadClick: (() -> Unit)? = null,
) {
    when (state) {
        TrackDownloadState.NotDownloaded -> {
            if (onDownloadClick != null) {
                IconButton(
                    onClick = onDownloadClick,
                    modifier = modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "İndir",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        TrackDownloadState.Queued -> {
            Icon(
                Icons.Default.Schedule,
                contentDescription = "İndirme sırasında",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = modifier.size(18.dp),
            )
        }
        is TrackDownloadState.Downloading -> {
            CircularProgressIndicator(
                progress = { (state.progress / 100f).coerceIn(0.05f, 1f) },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = modifier.size(18.dp),
            )
        }
        TrackDownloadState.Downloaded -> {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "İndirildi",
                tint = StreamingColors.Success,
                modifier = modifier.size(18.dp),
            )
        }
        is TrackDownloadState.Failed -> {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = state.message ?: "İndirme başarısız",
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun StreamingTrackRow(
    title: String,
    artist: String?,
    modifier: Modifier = Modifier,
    album: String? = null,
    durationMs: Long = 0L,
    durationText: String? = null,
    index: Int? = null,
    thumbnailUrl: String? = null,
    downloadState: TrackDownloadState = TrackDownloadState.NotDownloaded,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    onClick: () -> Unit = {},
    onDownloadClick: (() -> Unit)? = null,
    onViewAlbumClick: (() -> Unit)? = null,
    onViewArtistClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (isCurrent && isPlaying) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Çalıyor",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (index <= 3) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        if (thumbnailUrl != null || index == null) {
            Box {
                Artwork(model = thumbnailUrl, corner = 12.dp, modifier = Modifier.size(48.dp))
                if (index == null && isCurrent && isPlaying) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.GraphicEq, "Çalıyor", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
        }

        // Title and Subtitle metadata
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val formattedDuration = durationText?.takeIf { it.isNotBlank() } ?: formatDuration(durationMs)
            val sub = listOfNotNull(artist, album, formattedDuration.takeIf { it != "0:00" })
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Download badge
        DownloadStatusBadge(
            state = downloadState,
            onDownloadClick = onDownloadClick,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // 3-dots overflow menu
        val hasMenu = onDownloadClick != null || onViewAlbumClick != null || onViewArtistClick != null || onDeleteClick != null
        if (hasMenu) {
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Seçenekler",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Oynat") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                        onClick = {
                            menuExpanded = false
                            onClick()
                        },
                    )
                    if (downloadState == TrackDownloadState.NotDownloaded && onDownloadClick != null) {
                        DropdownMenuItem(
                            text = { Text("İndir") },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = {
                                menuExpanded = false
                                onDownloadClick()
                            },
                        )
                    }
                    if (onViewAlbumClick != null) {
                        DropdownMenuItem(
                            text = { Text("Albüme Git") },
                            leadingIcon = { Icon(Icons.Default.Album, null) },
                            onClick = {
                                menuExpanded = false
                                onViewAlbumClick()
                            },
                        )
                    }
                    if (onViewArtistClick != null) {
                        DropdownMenuItem(
                            text = { Text("Sanatçıya Git") },
                            leadingIcon = { Icon(Icons.Default.Person, null) },
                            onClick = {
                                menuExpanded = false
                                onViewArtistClick()
                            },
                        )
                    }
                    if (onDeleteClick != null) {
                        DropdownMenuItem(
                            text = { Text("Sil") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = {
                                menuExpanded = false
                                onDeleteClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StreamingTrackRow(
    track: RemoteTrack,
    modifier: Modifier = Modifier,
    showThumbnail: Boolean = true,
    downloadState: TrackDownloadState = TrackDownloadState.NotDownloaded,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    onClick: () -> Unit = {},
    onDownloadClick: (() -> Unit)? = null,
    onViewAlbumClick: (() -> Unit)? = null,
    onViewArtistClick: (() -> Unit)? = null,
) = StreamingTrackRow(
    title = track.title,
    artist = track.artist,
    album = track.album,
    durationMs = track.durationMs,
    durationText = track.durationText,
    index = track.index,
    thumbnailUrl = if (showThumbnail) track.thumbnailUrl else null,
    downloadState = downloadState,
    isCurrent = isCurrent,
    isPlaying = isPlaying,
    onClick = onClick,
    onDownloadClick = onDownloadClick,
    onViewAlbumClick = onViewAlbumClick,
    onViewArtistClick = onViewArtistClick,
    modifier = modifier,
)

@Composable
fun StreamingTrackRow(
    song: Song,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    onClick: () -> Unit = {},
    onDeleteClick: (() -> Unit)? = null,
) = StreamingTrackRow(
    title = song.title,
    artist = song.artist,
    album = song.album,
    durationMs = song.durationMs,
    thumbnailUrl = song.artworkFile?.absolutePath,
    downloadState = TrackDownloadState.Downloaded,
    isCurrent = isCurrent,
    isPlaying = isPlaying,
    onClick = onClick,
    onDeleteClick = onDeleteClick,
    modifier = modifier,
)
