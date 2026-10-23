@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.yrt.music.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yrt.music.data.model.Song
import com.yrt.music.ui.components.Artwork
import com.yrt.music.ui.components.formatDuration

private val CardShape = 20.dp
private val CardGap = 10.dp

private fun formatTotal(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} sa ${minutes % 60} dk" else "$minutes dk"
}

/**
 * The library is rendered as one flat lazy list: an artist card header row followed (when
 * expanded) by one row per song. Only the rows on screen are ever composed, so expanding an
 * artist with hundreds of songs costs the same as expanding one with three.
 */
@Immutable
private sealed interface LibraryRow {
    val key: String

    @Immutable
    data class Artist(val group: ArtistGroup, val isOpen: Boolean) : LibraryRow {
        override val key = "a:${group.name.lowercase()}"
    }

    @Immutable
    data class SongItem(
        val group: ArtistGroup,
        val song: Song,
        val index: Int,
        val isLast: Boolean,
    ) : LibraryRow {
        override val key = "s:${song.id}"
    }
}

private fun buildRows(groups: List<ArtistGroup>, isOpen: (ArtistGroup) -> Boolean): List<LibraryRow> {
    val rows = ArrayList<LibraryRow>(groups.size * 2)
    for (group in groups) {
        val open = isOpen(group)
        rows += LibraryRow.Artist(group, open)
        if (open) {
            group.songs.forEachIndexed { index, song ->
                rows += LibraryRow.SongItem(group, song, index, isLast = index == group.songs.lastIndex)
            }
        }
    }
    return rows
}

@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    currentMediaId: String?,
    contentPadding: PaddingValues,
    onPlay: (List<Song>, Int) -> Unit,
    onDeleted: (Song) -> Unit,
    onNavigateToSettings: () -> Unit = {},
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val all = state.all
    val songs = state.visible
    val groups = state.groups
    val query = vm.query
    val searching = query.isNotBlank()

    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<Song?>(null) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    val selecting = selectedIds.isNotEmpty()

    val rows = remember(groups, expanded, searching) {
        buildRows(groups) { searching || it.name in expanded }
    }

    // Drop selections that no longer exist (deleted / refreshed away).
    LaunchedEffect(all) {
        if (selectedIds.isEmpty()) return@LaunchedEffect
        val existing = all.mapTo(HashSet()) { it.id }
        if (!existing.containsAll(selectedIds)) selectedIds = selectedIds.filterTo(HashSet()) { it in existing }
    }
    BackHandler(enabled = selecting) { selectedIds = emptySet() }

    fun toggleSong(song: Song) {
        selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id
    }

    fun toggleGroup(group: ArtistGroup) {
        val ids = group.songs.map { it.id }
        selectedIds = if (selectedIds.containsAll(ids)) selectedIds - ids.toSet() else selectedIds + ids
    }

    fun toggleExpanded(group: ArtistGroup) {
        if (searching) return
        expanded = if (group.name in expanded) expanded - group.name else expanded + group.name
    }

    val pullToRefreshState = rememberPullToRefreshState()

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = vm.isRefreshing,
            onRefresh = { if (!selecting) vm.refresh() },
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = vm.isRefreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = contentPadding.calculateTopPadding()),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            ) {
                item(key = "header", contentType = "header") {
                    LibraryHeader(
                        topPadding = contentPadding.calculateTopPadding(),
                        songCount = all.size,
                        artistCount = state.artistCount,
                        totalMs = state.totalMs,
                        onSettingsClick = onNavigateToSettings,
                        onPlayAll = { if (songs.isNotEmpty()) onPlay(groups.flatMap { it.songs }, 0) },
                        onShuffle = { if (songs.isNotEmpty()) onPlay(songs.shuffled(), 0) },
                        canPlay = songs.isNotEmpty(),
                    )
                }
                item(key = "search", contentType = "search") {
                    SearchField(query) { vm.query = it }
                }
                if (groups.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        EmptyState(libraryEmpty = all.isEmpty())
                    }
                }
                items(rows, key = { it.key }, contentType = { if (it is LibraryRow.Artist) "artist" else "song" }) { row ->
                    when (row) {
                        is LibraryRow.Artist -> {
                            val group = row.group
                            ArtistHeaderRow(
                                group = group,
                                isOpen = row.isOpen,
                                hasCurrent = currentMediaId != null && group.songs.any { it.id == currentMediaId },
                                selecting = selecting,
                                selectedCount = if (selecting) group.songs.count { it.id in selectedIds } else 0,
                                onToggle = { toggleExpanded(group) },
                                onPlayArtist = { onPlay(group.songs, 0) },
                                onToggleGroup = { toggleGroup(group) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        is LibraryRow.SongItem -> {
                            val song = row.song
                            SongRow(
                                song = song,
                                featuring = row.group.featuring[song.id].orEmpty(),
                                isCurrent = song.id == currentMediaId,
                                isLast = row.isLast,
                                selecting = selecting,
                                isSelected = song.id in selectedIds,
                                onClick = {
                                    if (selecting) toggleSong(song) else onPlay(row.group.songs, row.index)
                                },
                                onLongClick = { toggleSong(song) },
                                onDelete = { pendingDelete = song },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = selecting,
            enter = slideInVertically(tween(180)) { -it } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(150)) { -it } + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            SelectionBar(
                topPadding = contentPadding.calculateTopPadding(),
                count = selectedIds.size,
                allSelected = songs.isNotEmpty() && songs.all { it.id in selectedIds },
                onClose = { selectedIds = emptySet() },
                onSelectAll = {
                    val visible = songs.map { it.id }
                    selectedIds = if (selectedIds.containsAll(visible)) emptySet() else selectedIds + visible
                },
                onDelete = { confirmBulkDelete = true },
            )
        }
    }

    if (confirmBulkDelete) {
        val toDelete = all.filter { it.id in selectedIds }
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("${toDelete.size} şarkı silinsin mi?") },
            text = { Text("Seçilen şarkılar cihazdan kalıcı olarak silinecek.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete.forEach(onDeleted)
                    vm.deleteAll(toDelete)
                    selectedIds = emptySet()
                    confirmBulkDelete = false
                }) { Text("Sil", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("Vazgeç") } },
        )
    }

    pendingDelete?.let { song ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Şarkı silinsin mi?") },
            text = { Text(song.title) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleted(song)
                    vm.delete(song)
                    pendingDelete = null
                }) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun SelectionBar(
    topPadding: Dp,
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), Color.Transparent),
                    ),
                )
                .padding(top = topPadding + 4.dp, bottom = 8.dp, start = 4.dp, end = 8.dp),
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Seçimi kapat") }
            Text(
                "$count seçildi",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            TextButton(onClick = onSelectAll) {
                Text(if (allSelected) "Seçimi kaldır" else "Tümünü seç")
            }
            Surface(
                onClick = onDelete,
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                shape = CircleShape,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sil", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    topPadding: Dp,
    songCount: Int,
    artistCount: Int,
    totalMs: Long,
    canPlay: Boolean,
    onSettingsClick: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.background
    val gradient = remember(primary, background) {
        Brush.verticalGradient(listOf(primary.copy(alpha = 0.22f), background))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(gradient)
            .padding(top = topPadding + 8.dp, start = 20.dp, end = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LibraryMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "Cihazında",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("Kitaplık", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (songCount == 0) "Henüz şarkı yok"
                    else "$artistCount sanatçı • $songCount şarkı • ${formatTotal(totalMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, "Ayarlar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(end = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            Surface(
                onClick = onPlayAll,
                enabled = canPlay,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tümünü çal", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            Surface(
                onClick = onShuffle,
                enabled = canPlay,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Shuffle, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Karıştır", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Şarkı veya sanatçı ara") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Temizle") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = CardGap + 2.dp),
    )
}

@Composable
private fun EmptyState(libraryEmpty: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
            Icon(
                Icons.Default.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(18.dp).size(32.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            if (libraryEmpty) "Kitaplığın boş" else "Sonuç bulunamadı",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (libraryEmpty) "Keşfet'ten şarkı ekle." else "Farklı bir arama dene.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Top row of an artist card. Rounded on top only while the card is open so song rows can continue it. */
@Composable
private fun ArtistHeaderRow(
    group: ArtistGroup,
    isOpen: Boolean,
    hasCurrent: Boolean,
    selecting: Boolean,
    selectedCount: Int,
    onToggle: () -> Unit,
    onPlayArtist: () -> Unit,
    onToggleGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation = animateFloatAsState(if (isOpen) 180f else 0f, tween(200), label = "chevron")
    val shape = if (isOpen) {
        RoundedCornerShape(topStart = CardShape, topEnd = CardShape)
    } else {
        RoundedCornerShape(CardShape)
    }
    val primary = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val brush = remember(primary, base, hasCurrent) {
        Brush.horizontalGradient(listOf(primary.copy(alpha = if (hasCurrent) 0.28f else 0.14f), base))
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = if (isOpen) 0.dp else CardGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(base)
                .background(brush)
                .combinedClickable(onClick = onToggle, onLongClick = onToggleGroup)
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        ) {
            Artwork(group.cover?.artworkFile, Modifier.size(60.dp), corner = 30.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (hasCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${group.songs.size} şarkı • ${formatTotal(group.totalMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (selecting) {
                TriStateCheckbox(
                    state = when (selectedCount) {
                        0 -> ToggleableState.Off
                        group.songs.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    },
                    onClick = onToggleGroup,
                )
            } else {
                IconButton(
                    onClick = onPlayArtist,
                    modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        "${group.name} şarkılarını çal",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            IconButton(onClick = onToggle) {
                Icon(
                    Icons.Default.ExpandMore,
                    if (isOpen) "Daralt" else "Genişlet",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation.value },
                )
            }
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    featuring: List<String>,
    isCurrent: Boolean,
    isLast: Boolean,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val shape = if (isLast) {
        RoundedCornerShape(bottomStart = CardShape, bottomEnd = CardShape)
    } else {
        RoundedCornerShape(0.dp)
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = if (isLast) CardGap else 0.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(base)
                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(
                    start = 14.dp,
                    top = 6.dp,
                    bottom = if (isLast) 12.dp else 6.dp,
                    end = if (selecting) 10.dp else 0.dp,
                ),
        ) {
            Artwork(song.artworkFile, Modifier.size(44.dp), corner = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (featuring.isEmpty()) formatDuration(song.durationMs)
                    else "feat. ${featuring.joinToString(", ")} • ${formatDuration(song.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isCurrent) {
                Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
            }
            if (selecting) {
                Checkbox(checked = isSelected, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
            } else {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, "Seçenekler", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Sil") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                        DropdownMenuItem(
                            text = { Text("Seç") },
                            leadingIcon = { Icon(Icons.Default.CheckCircle, null) },
                            onClick = { menu = false; onLongClick() },
                        )
                    }
                }
            }
        }
    }
}
