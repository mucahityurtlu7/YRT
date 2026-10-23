package com.yrt.music.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yrt.music.YrtApp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = (app as YrtApp).container.downloadManager

    val engineState = manager.engineState

    var autoUpdate by mutableStateOf(manager.autoUpdateEnabled)
        private set
    var lastUpdateMillis by mutableStateOf(manager.lastUpdateMillis)
        private set

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun onAutoUpdateChange(enabled: Boolean) {
        manager.autoUpdateEnabled = enabled
        autoUpdate = enabled
    }

    fun updateYtDlp() {
        viewModelScope.launch {
            _messages.send(manager.updateYtDlp())
            lastUpdateMillis = manager.lastUpdateMillis
        }
    }
}
