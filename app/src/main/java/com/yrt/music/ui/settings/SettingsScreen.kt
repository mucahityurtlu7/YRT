package com.yrt.music.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yrt.music.data.downloader.EngineState

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    contentPadding: PaddingValues,
    onBackClick: () -> Unit = {},
) {
    val engine by vm.engineState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp, end = 16.dp,
                top = contentPadding.calculateTopPadding() + 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
            }
            Spacer(Modifier.width(8.dp))
            Text("Ayarlar", style = MaterialTheme.typography.headlineMedium)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Güncellemeler", style = MaterialTheme.typography.titleMedium)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val text = when (val e = engine) {
                        EngineState.Initializing -> "yt-dlp hazırlanıyor…"
                        EngineState.Updating -> "yt-dlp güncelleniyor…"
                        is EngineState.Ready -> "yt-dlp ${e.version ?: "?"}"
                        is EngineState.Error -> "Hata: ${e.message}"
                    }
                    if (engine is EngineState.Initializing || engine is EngineState.Updating) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (engine is EngineState.Error) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (vm.lastUpdateMillis == 0L) "Son güncelleme: hiç"
                            else "Son güncelleme: " + DateUtils.getRelativeTimeSpanString(vm.lastUpdateMillis),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = vm::updateYtDlp, enabled = engine is EngineState.Ready) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Güncelle")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Otomatik güncelle", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "yt-dlp günde en fazla bir kez kendiliğinden güncellenir",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = vm.autoUpdate, onCheckedChange = vm::onAutoUpdateChange)
                }
            }
        }
    }
}
