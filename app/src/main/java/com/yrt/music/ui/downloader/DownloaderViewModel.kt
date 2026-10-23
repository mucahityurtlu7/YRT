package com.yrt.music.ui.downloader

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yrt.music.YrtApp
import com.yrt.music.data.model.AudioFormat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class DownloaderViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = (app as YrtApp).container.downloadManager

    val tasks = manager.tasks
    val engineState = manager.engineState

    var url by mutableStateOf("")
    var format by mutableStateOf(AudioFormat.M4A)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun startDownload() {
        val link = extractUrl(url)
        if (link == null) {
            _messages.trySend("Geçerli bir bağlantı girin")
            return
        }
        manager.download(link, format)
        url = ""
    }

    /** Text coming from the Android share sheet (e.g. "Check this out https://youtu.be/…"). */
    fun onSharedText(text: String) {
        extractUrl(text)?.let { url = it }
    }

    fun cancel(id: String) = manager.cancel(id)
    fun clearFinished() = manager.clearFinished()

    fun updateYtDlp() {
        viewModelScope.launch { _messages.send(manager.updateYtDlp()) }
    }

    companion object {
        private val URL_REGEX = Regex("""https?://\S+""")
        fun extractUrl(text: String): String? = URL_REGEX.find(text.trim())?.value
    }
}
