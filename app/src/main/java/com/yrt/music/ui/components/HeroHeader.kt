package com.yrt.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yrt.music.data.model.AlbumDetails
import com.yrt.music.data.model.ArtistProfile
import com.yrt.music.ui.theme.StreamingColors

fun createHeroScrim(targetColor: Color): Brush = Brush.verticalGradient(
    0.00f to Color.Black.copy(alpha = 0.45f),
    0.25f to Color.Transparent,
    0.55f to targetColor.copy(alpha = 0.72f),
    0.78f to targetColor.copy(alpha = 0.97f),
    1.00f to targetColor,
)

sealed interface HeroDownloadState {
    data object Idle : HeroDownloadState
    data class InProgress(val progress: Float) : HeroDownloadState
    data object Completed : HeroDownloadState
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeroHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backdropUrl: String? = null,
    coverArtworkUrl: String? = null,
    headerHeight: Dp = 320.dp,
    downloadState: HeroDownloadState = HeroDownloadState.Idle,
    downloadButtonText: String = "Tümünü İndir",
    onBackClick: (() -> Unit)? = null,
    onPlayClick: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    secondaryActions: (@Composable () -> Unit)? = null,
    isPreparingDownload: Boolean = false,
    label: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val hasCover = coverArtworkUrl != null && backdropUrl == null
    Box(modifier = modifier.fillMaxWidth().heightIn(min = headerHeight)) {
        Box(
            modifier = Modifier.matchParentSize().background(
                Brush.linearGradient(listOf(colors.primaryContainer, colors.surfaceContainerHigh, colors.background)),
            ),
        )
        val imageUrl = backdropUrl?.takeIf { it.isNotBlank() }
            ?: coverArtworkUrl?.takeIf { it.isNotBlank() }
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(Modifier.matchParentSize().background(createHeroScrim(colors.background)))

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart)
                .padding(start = 24.dp, end = 24.dp, top = if (hasCover) 72.dp else 148.dp, bottom = 24.dp),
        ) {
            if (hasCover) {
                Artwork(
                    model = coverArtworkUrl,
                    corner = 18.dp,
                    modifier = Modifier.size(136.dp).shadow(18.dp, RoundedCornerShape(18.dp)),
                )
                Spacer(Modifier.height(20.dp))
            }
            if (!label.isNullOrBlank()) {
                Surface(
                    color = colors.primaryContainer.copy(alpha = 0.85f),
                    contentColor = colors.onPrimaryContainer,
                    shape = CircleShape,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = colors.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(22.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onPlayClick != null) {
                    Button(
                        onClick = onPlayClick,
                        shape = CircleShape,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Oynat", fontWeight = FontWeight.Bold)
                    }
                }
                if (onDownloadClick != null) {
                    FilledTonalButton(
                        onClick = onDownloadClick,
                        enabled = !isPreparingDownload && downloadState == HeroDownloadState.Idle,
                        shape = CircleShape,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = colors.surfaceContainerHigh,
                            contentColor = colors.onSurface,
                            disabledContainerColor = colors.surfaceContainerHigh,
                            disabledContentColor = colors.onSurfaceVariant,
                        ),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        when {
                            isPreparingDownload -> {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Şarkılar hazırlanıyor…")
                            }
                            downloadState is HeroDownloadState.InProgress -> {
                                CircularProgressIndicator(
                                    progress = { (downloadState.progress / 100f).coerceIn(0f, 1f) },
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("%${downloadState.progress.toInt()} İndiriliyor")
                            }
                            downloadState == HeroDownloadState.Completed -> {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = StreamingColors.Success,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("İndirildi")
                            }
                            else -> {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(downloadButtonText, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                secondaryActions?.invoke()
            }
        }
        if (onBackClick != null) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 8.dp)
                    .background(Color.Black.copy(alpha = 0.40f), CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = Color.White)
            }
        }
    }
}

@Composable
fun ArtistHeroHeader(
    artist: ArtistProfile,
    modifier: Modifier = Modifier,
    downloadState: HeroDownloadState = HeroDownloadState.Idle,
    onBackClick: (() -> Unit)? = null,
    onPlayClick: (() -> Unit)? = null,
    onDownloadAllClick: (() -> Unit)? = null,
    isPreparingDownload: Boolean = false,
) = HeroHeader(
    title = artist.name,
    subtitle = artist.subscribers?.let { "$it abone" },
    backdropUrl = artist.heroArtworkUrl,
    headerHeight = 360.dp,
    downloadState = downloadState,
    downloadButtonText = "Tümünü İndir",
    onBackClick = onBackClick,
    onPlayClick = onPlayClick,
    onDownloadClick = onDownloadAllClick,
    isPreparingDownload = isPreparingDownload,
    label = "SANATÇI",
    modifier = modifier,
)

@Composable
fun AlbumHeroHeader(
    album: AlbumDetails,
    modifier: Modifier = Modifier,
    downloadState: HeroDownloadState = HeroDownloadState.Idle,
    onBackClick: (() -> Unit)? = null,
    onPlayClick: (() -> Unit)? = null,
    onDownloadAlbumClick: (() -> Unit)? = null,
) {
    val subtitleParts = listOfNotNull(
        album.artist,
        album.year,
        if (album.trackCount > 0) "${album.trackCount} şarkı" else null,
        album.durationText,
    ).filter { it.isNotBlank() }
    HeroHeader(
        title = album.title,
        subtitle = subtitleParts.joinToString(" • "),
        coverArtworkUrl = album.artworkUrl,
        headerHeight = 360.dp,
        downloadState = downloadState,
        downloadButtonText = "Albümü İndir",
        onBackClick = onBackClick,
        onPlayClick = onPlayClick,
        onDownloadClick = onDownloadAlbumClick,
        modifier = modifier,
    )
}
