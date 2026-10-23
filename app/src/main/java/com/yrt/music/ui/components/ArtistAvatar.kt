package com.yrt.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yrt.music.data.model.ArtistSearchResult

enum class ArtistAvatarSize(val dp: Dp) {
    Small(40.dp),
    Medium(56.dp),
    Large(96.dp),
    XLarge(140.dp),
}

@Composable
fun ArtistAvatar(
    imageUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    borderColor: Color? = null,
    borderWidth: Dp = 2.dp,
    onClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    val borderModifier = if (borderColor != null) Modifier.border(borderWidth, borderColor, CircleShape) else Modifier

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(borderModifier)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(clickModifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(size * 0.5f),
        )
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun ArtistAvatar(
    imageUrl: String?,
    name: String,
    sizeVariant: ArtistAvatarSize,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    borderWidth: Dp = 2.dp,
    onClick: (() -> Unit)? = null,
) = ArtistAvatar(
    imageUrl = imageUrl,
    name = name,
    modifier = modifier,
    size = sizeVariant.dp,
    borderColor = borderColor,
    borderWidth = borderWidth,
    onClick = onClick,
)

@Composable
fun ArtistAvatar(
    artist: ArtistSearchResult,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    onClick: (() -> Unit)? = null,
) = ArtistAvatar(
    imageUrl = artist.thumbnailUrl,
    name = artist.name,
    modifier = modifier,
    size = size,
    onClick = onClick,
)

/**
 * Vertical artist card for search shelves, carousels, or grid views.
 */
@Composable
fun ArtistCard(
    artist: ArtistSearchResult,
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
    onClick: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtistAvatar(
            imageUrl = artist.thumbnailUrl,
            name = artist.name,
            size = 96.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = artist.subtitle?.takeIf { it.isNotBlank() } ?: "Sanatçı",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
