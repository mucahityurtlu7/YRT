package com.yrt.music.ui.downloader

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yrt.music.data.downloader.EngineState
import com.yrt.music.data.model.AudioFormat
import com.yrt.music.data.model.DownloadStatus
import com.yrt.music.data.model.DownloadTask

@Suppress("DEPRECATION") // LocalClipboardManager: simple synchronous text access is enough here
@Composable
fun DownloaderScreen(vm: DownloaderViewModel, contentPadding: PaddingValues) {
    val tasks by vm.tasks.collectAsState()
    val engine by vm.engineState.collectAsState()
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("İndir", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            OutlinedTextField(
                value = vm.url,
                onValueChange = { vm.url = it },
                label = { Text("YouTube / SoundCloud bağlantısı") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { vm.onSharedText(it) }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Yapıştır")
                    }
                },
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AudioFormat.entries.forEach { f ->
                    FilterChip(
                        selected = vm.format == f,
                        onClick = { vm.format = f },
                        label = { Text(f.label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = vm::startDownload,
                    enabled = vm.url.isNotBlank() && engine !is EngineState.Error,
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("İndir")
                }
            }
        }
        item { EngineRow(engine, onUpdate = vm::updateYtDlp) }

        if (tasks.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("İndirmeler", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::clearFinished) { Text("Bitenleri temizle") }
                }
            }
            items(tasks, key = { it.id }) { task ->
                DownloadCard(task, onCancel = { vm.cancel(task.id) })
            }
        }
    }
}

@Composable
private fun EngineRow(engine: EngineState, onUpdate: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val text = when (engine) {
            EngineState.Initializing -> "yt-dlp hazırlanıyor…"
            EngineState.Updating -> "yt-dlp güncelleniyor…"
            is EngineState.Ready -> "yt-dlp ${engine.version ?: "?"}"
            is EngineState.Error -> "Hata: ${engine.message}"
        }
        if (engine is EngineState.Initializing || engine is EngineState.Updating) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (engine is EngineState.Error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUpdate, enabled = engine is EngineState.Ready) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Güncelle")
        }
    }
}

@Composable
private fun DownloadCard(task: DownloadTask, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when (task.status) {
                    DownloadStatus.Queued, DownloadStatus.Running ->
                        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "İptal") }
                    DownloadStatus.Done ->
                        Icon(Icons.Default.CheckCircle, "Tamamlandı", tint = MaterialTheme.colorScheme.primary)
                    DownloadStatus.Failed ->
                        Icon(Icons.Default.Error, "Hata", tint = MaterialTheme.colorScheme.error)
                    DownloadStatus.Cancelled -> Unit
                }
            }
            Spacer(Modifier.height(6.dp))
            when (task.status) {
                DownloadStatus.Queued -> Text("Sırada…", style = MaterialTheme.typography.bodySmall)
                DownloadStatus.Running -> {
                    if (task.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { task.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(4.dp))
                    val eta = if (task.etaSec > 0) " • ${task.etaSec} sn kaldı" else ""
                    Text(
                        task.message ?: "%${"%.1f".format(task.progress)}$eta",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                DownloadStatus.Done -> Text("${task.format.ext.uppercase()} • Kitaplığa eklendi", style = MaterialTheme.typography.bodySmall)
                DownloadStatus.Failed -> Text(
                    task.message ?: "Hata",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                DownloadStatus.Cancelled -> Text("İptal edildi", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
