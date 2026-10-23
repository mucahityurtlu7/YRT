package com.yrt.music.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yrt.music.data.model.DiscoveryItem
import com.yrt.music.data.model.DiscoveryItemKind
import com.yrt.music.data.model.DiscoverySection
import com.yrt.music.data.model.RemoteTrack
import com.yrt.music.ui.components.ArtistAvatar
import com.yrt.music.ui.components.Artwork
import com.yrt.music.ui.components.StreamingTrackRow
import com.yrt.music.ui.components.TrackDownloadState

/** The live, public YouTube Music feed shown when the search field is empty. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverContent(
    state: DiscoveryUiState,
    onItemClick: (DiscoveryItem) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onMoreClick: (DiscoverySection) -> Unit,
    modifier: Modifier = Modifier,
    onDownloadTrack: ((RemoteTrack) -> Unit)? = null,
    trackDownloadState: (String) -> TrackDownloadState = { TrackDownloadState.NotDownloaded },
) {
    BackHandler(enabled = state.canGoBack, onBack = onBack)

    Column(modifier.fillMaxSize()) {
        DiscoverHeader(
            title = if (state.canGoBack) state.page?.title ?: "Keşfet" else "Keşfet",
            canGoBack = state.canGoBack,
            onBack = onBack,
        )

        PullToRefreshBox(
            isRefreshing = state.isLoading && (state.page != null || state.errorMessage != null),
            onRefresh = onRefresh,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.page == null && state.isLoading -> DiscoveryLoading(Modifier.fillMaxSize())
                state.page == null && state.errorMessage != null -> DiscoveryMessage(
                    title = "Keşfet yüklenemedi",
                    message = state.errorMessage,
                    onRetry = onRetry,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                )
                state.page?.sections.isNullOrEmpty() -> DiscoveryMessage(
                    title = "Burada henüz içerik yok",
                    message = "Yeni müzikler için sayfayı yeniden yükle.",
                    onRetry = onRetry,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                )
                else -> key(state.page.title, state.canGoBack) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.errorMessage != null) {
                            item(key = "discovery_refresh_error") {
                                DiscoveryRefreshError(state.errorMessage, onRetry)
                            }
                        }
                        state.page.sections.forEachIndexed { sectionIndex, section ->
                            val sectionKey = "${sectionIndex}_${section.id}"
                            val isRootMenu = !state.canGoBack && section.title == state.page.title &&
                                section.items.size <= 6 && section.items.all { it.kind == DiscoveryItemKind.CATEGORY }
                            if (!isRootMenu) {
                                item(key = "${sectionKey}_header") {
                                    DiscoverySectionHeader(section, onMoreClick)
                                }
                            }
                            when {
                                section.items.all { it.kind == DiscoveryItemKind.TRACK } -> {
                                    items(
                                        items = section.items,
                                        key = { "${sectionKey}_${it.id}" },
                                    ) { item ->
                                        StreamingTrackRow(
                                            title = item.title,
                                            artist = item.subtitle ?: item.track?.artist,
                                            thumbnailUrl = item.artworkUrl ?: item.track?.thumbnailUrl,
                                            durationMs = item.track?.durationMs ?: 0L,
                                            durationText = item.track?.durationText,
                                            downloadState = item.track?.videoId?.let(trackDownloadState)
                                                ?: TrackDownloadState.NotDownloaded,
                                            onClick = { onItemClick(item) },
                                            onDownloadClick = if (item.track != null && onDownloadTrack != null) {
                                                { onDownloadTrack(item.track) }
                                            } else null,
                                        )
                                    }
                                }
                                section.items.all { it.kind == DiscoveryItemKind.CATEGORY } -> {
                                    if (!state.canGoBack && section.items.size > 6) {
                                        item(key = "${sectionKey}_category_shelf") {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 20.dp),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                items(section.items.chunked(2)) { column ->
                                                    Column(
                                                        modifier = Modifier.width(166.dp),
                                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                                    ) {
                                                        column.forEach { item ->
                                                            DiscoveryCategoryCard(
                                                                item = item,
                                                                onClick = { onItemClick(item) },
                                                                modifier = Modifier.fillMaxWidth(),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        section.items.chunked(2).forEachIndexed { rowIndex, row ->
                                            item(key = "${sectionKey}_categories_$rowIndex") {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    row.forEach { item ->
                                                        DiscoveryCategoryCard(
                                                            item = item,
                                                            onClick = { onItemClick(item) },
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                    }
                                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                                else -> item(key = "${sectionKey}_cards") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        items(section.items, key = { it.id }) { item ->
                                            DiscoveryArtworkCard(item, onClick = { onItemClick(item) })
                                        }
                                    }
                                }
                            }
                            item(key = "${sectionKey}_spacer") { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverHeader(
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = if (canGoBack) 8.dp else 20.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Keşfete dön")
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PlayCircleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "YouTube Music",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                style = if (canGoBack) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!canGoBack) {
                Text(
                    "Yeni çıkanlar, listeler ve yeni favorilerin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DiscoverySectionHeader(section: DiscoverySection, onMoreClick: (DiscoverySection) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(3.dp).height(20.dp)
                .clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary),
        )
        Text(
            section.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (section.browseId != null) {
            TextButton(onClick = { onMoreClick(section) }) {
                Text("Daha fazla", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(3.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(16.dp))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun DiscoveryArtworkCard(item: DiscoveryItem, onClick: () -> Unit) {
    val isArtist = item.kind == DiscoveryItemKind.ARTIST
    Column(
        modifier = Modifier.width(154.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (isArtist) {
            ArtistAvatar(
                imageUrl = item.artworkUrl,
                name = item.title,
                size = 144.dp,
                modifier = Modifier.padding(5.dp),
            )
        } else {
            Box {
                Artwork(item.artworkUrl, modifier = Modifier.fillMaxWidth().aspectRatio(1f), corner = 14.dp)
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                    shape = CircleShape,
                ) {
                    Icon(
                        discoveryKindIcon(item.kind),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(6.dp).size(14.dp),
                    )
                }
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 2.dp, end = 2.dp),
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            maxLines = 2,
            minLines = if (isArtist) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.subtitle?.takeIf { it.isNotBlank() } ?: discoveryKindLabel(item.kind),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 8.dp, start = 2.dp, end = 2.dp),
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DiscoveryCategoryCard(item: DiscoveryItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.height(76.dp).clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ),
            ).clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.align(Alignment.CenterStart).width(3.dp).height(28.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp, end = 30.dp),
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(16.dp),
        )
    }
}

@Composable
private fun DiscoveryLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(18.dp))
        Text("Yeni müzikler getiriliyor…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiscoveryMessage(title: String, message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
            Icon(
                Icons.Default.Explore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(18.dp).size(32.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry, shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Tekrar dene")
        }
    }
}

@Composable
private fun DiscoveryRefreshError(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Text(
                message,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onRetry) { Text("Yenile") }
        }
    }
}

private fun discoveryKindIcon(kind: DiscoveryItemKind): ImageVector = when (kind) {
    DiscoveryItemKind.ARTIST -> Icons.Default.Person
    DiscoveryItemKind.ALBUM -> Icons.Default.Album
    DiscoveryItemKind.PLAYLIST -> Icons.AutoMirrored.Filled.QueueMusic
    DiscoveryItemKind.TRACK -> Icons.Default.MusicNote
    DiscoveryItemKind.CATEGORY -> Icons.Default.Explore
}

private fun discoveryKindLabel(kind: DiscoveryItemKind): String = when (kind) {
    DiscoveryItemKind.ARTIST -> "Sanatçı"
    DiscoveryItemKind.ALBUM -> "Albüm"
    DiscoveryItemKind.PLAYLIST -> "Çalma listesi"
    DiscoveryItemKind.TRACK -> "Şarkı"
    DiscoveryItemKind.CATEGORY -> "Keşfet"
}
