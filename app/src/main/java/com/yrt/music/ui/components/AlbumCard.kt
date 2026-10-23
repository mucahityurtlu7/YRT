package com.yrt.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yrt.music.data.model.AlbumSearchResult

fun formatAlbumSubtitle(artist: String?, year: String?, type: String?): String {
    return listOfNotNull(artist, year, type).filter { it.isNotBlank() }.joinToString(" • ")
}

@Composable
fun AlbumCard(
    title: String,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    year: String? = null,
    type: String? = null,
    width: Dp = 160.dp,
    corner: Dp = 16.dp,
    onClick: () -> Unit = {},
    onPlayClick: (() -> Unit)? = null,
) {
    val widthModifier = if (width != Dp.Unspecified) Modifier.width(width) else Modifier

    Column(
        modifier = modifier
            .then(widthModifier)
            .clip(RoundedCornerShape(corner + 8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(48.dp),
            )
            if (!artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Year / Type pill badge
            val badgeText = listOfNotNull(type, year).filter { it.isNotBlank() }.joinToString(" • ")
            if (badgeText.isNotBlank()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Floating quick-play action button
            if (onPlayClick != null) {
                IconButton(
                    onClick = onPlayClick,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(36.dp)
                        .shadow(4.dp, CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Albümü Çal",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        val meta = subtitle?.takeIf { it.isNotBlank() } ?: formatAlbumSubtitle(null, year, type)
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun AlbumCard(
    album: AlbumSearchResult,
    modifier: Modifier = Modifier,
    width: Dp = 160.dp,
    corner: Dp = 16.dp,
    onClick: () -> Unit = {},
    onPlayClick: (() -> Unit)? = null,
) = AlbumCard(
    title = album.title,
    artworkUrl = album.artworkUrl,
    subtitle = album.artist,
    year = album.year,
    type = album.type,
    modifier = modifier,
    width = width,
    corner = corner,
    onClick = onClick,
    onPlayClick = onPlayClick,
)
