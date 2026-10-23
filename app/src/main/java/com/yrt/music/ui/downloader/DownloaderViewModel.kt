package com.yrt.music.ui.downloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.yrt.music.YrtApp
import com.yrt.music.data.model.AudioFormat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class DownloaderViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = (app as YrtApp).container.downloadManager

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Text coming from the Android share sheet (e.g. "Check this out https://youtu.be/…"). */
    fun onSharedText(text: String) {
        val link = extractUrl(text)
        if (link == null) {
            _messages.trySend("Geçerli bir bağlantı bulunamadı")
            return
        }
        manager.download(link, AudioFormat.M4A)
        _messages.trySend("İndirme kuyruğa eklendi")
    }

    companion object {
        private val URL_REGEX = Regex("""https?://\S+""")
        fun extractUrl(text: String): String? = URL_REGEX.find(text.trim())?.value
    }
}