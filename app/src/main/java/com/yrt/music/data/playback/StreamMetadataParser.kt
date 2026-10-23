package com.yrt.music.data.playback

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal object StreamMetadataParser {
    fun parse(json: String): ResolvedAudioStream {
        val metadata = JSONObject(json)
        val selected = sequenceOf(
            metadata,
            *objects(metadata.optJSONArray("requested_downloads")).toTypedArray(),
            *objects(metadata.optJSONArray("requested_formats")).toTypedArray(),
        ).firstOrNull { playableUrl(it) != null && it.optString("acodec") != "none" }
            ?: objects(metadata.optJSONArray("formats"))
                .filter { playableUrl(it) != null && it.optString("acodec") != "none" }
                .maxWithOrNull(compareBy<JSONObject>(
                    { it.optString("vcodec") == "none" },
                    { it.optDouble("abr", it.optDouble("tbr", 0.0)) },
                ))
            ?: throw IllegalStateException("Bu şarkı için oynatılabilir ses bulunamadı")

        val headers = linkedMapOf<String, String>()
        mergeHeaders(headers, metadata.optJSONObject("http_headers"))
        mergeHeaders(headers, selected.optJSONObject("http_headers"))
        return ResolvedAudioStream(
            url = requireNotNull(playableUrl(selected)),
            httpHeaders = headers.toMap(),
            mimeType = when (selected.optString("ext")) {
                "m4a", "mp4" -> "audio/mp4"
                "webm" -> "audio/webm"
                "opus", "ogg", "oga" -> "audio/ogg"
                "mp3" -> "audio/mpeg"
                "aac" -> "audio/aac"
                else -> null
            },
        )
    }

    private fun playableUrl(format: JSONObject): String? = format.optString("url")
        .takeIf { url ->
            runCatching {
                val uri = URI(url)
                uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)

    private fun mergeHeaders(target: MutableMap<String, String>, source: JSONObject?) {
        source ?: return
        source.keys().forEach { key ->
            val value = source.opt(key) as? String ?: return@forEach
            if (key.isBlank() || key.any { it == '\r' || it == '\n' } ||
                value.any { it == '\r' || it == '\n' }
            ) return@forEach
            target.keys.firstOrNull { it.equals(key, ignoreCase = true) }?.let(target::remove)
            target[key] = value
        }
    }
}
