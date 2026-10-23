package com.yrt.music.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import com.yrt.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Deduplication contract for querying indexed local songs by YouTube videoId.
 */
interface LocalSongLookup {
    fun hasVideoId(videoId: String): Boolean
    fun getSongByVideoId(videoId: String): Song?
    val songs: StateFlow<List<Song>>
    suspend fun refresh()
}

/**
 * Scans the app music directory, extracts ID3/media metadata & album tags,
 * and maintains an instant O(1) videoId index for duplicate prevention.
 */
class SongRepository(
    val musicDir: File,
    private val artDir: File,
) : LocalSongLookup {

    /**
     * Android Context convenience constructor.
     */
    constructor(context: Context) : this(
        musicDir = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music"))
            .apply { mkdirs() },
        artDir = File(context.cacheDir, "artwork").apply { mkdirs() },
    )

    init {
        musicDir.mkdirs()
        artDir.mkdirs()
    }

    private val cache = HashMap<String, Song>()

    @Volatile
    private var videoIdIndex: Map<String, Song> = emptyMap()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    override val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    override fun hasVideoId(videoId: String): Boolean = videoIdIndex.containsKey(videoId)

    override fun getSongByVideoId(videoId: String): Song? = videoIdIndex[videoId]

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val files = musicDir.listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXTS }
            ?.toList().orEmpty()
        val list = files.map { file ->
            val key = file.absolutePath
            val cached = cache[key]
            if (cached != null && cached.lastModified == file.lastModified()) cached
            else readSong(file).also { cache[key] = it }
        }.sortedByDescending { it.lastModified }

        val newIndex = HashMap<String, Song>()
        for (song in list) {
            song.videoId?.let { vId ->
                newIndex.putIfAbsent(vId, song)
            }
        }
        videoIdIndex = newIndex
        _songs.value = list
    }

    suspend fun delete(song: Song) = withContext(Dispatchers.IO) {
        song.file.delete()
        song.artworkFile?.delete()
        cache.remove(song.id)
        refresh()
    }

    /** Deletes several songs at once and re-indexes the library a single time. */
    suspend fun deleteAll(songs: Collection<Song>) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        songs.forEach { song ->
            song.file.delete()
            song.artworkFile?.delete()
            cache.remove(song.id)
        }
        refresh()
    }

    internal fun readSong(file: File): Song {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var duration = 0L
        var art: File? = null

        // MediaMetadataRetriever runs on Android runtime; on standard JVM unit tests,
        // it may throw a stub/mocking exception which is caught safely here.
        try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                mmr.embeddedPicture?.let { bytes ->
                    val out = File(artDir, "${file.absolutePath.hashCode()}_${file.lastModified()}.jpg")
                    if (!out.exists()) out.writeBytes(bytes)
                    art = out
                }
            } finally {
                runCatching { mmr.release() }
            }
        } catch (_: Throwable) {
            // Unreadable audio file or running on JVM without Android media framework
        }

        val cleanBase = file.nameWithoutExtension
        return Song(
            id = file.absolutePath,
            title = title?.takeIf { it.isNotBlank() } ?: cleanName(cleanBase),
            artist = artist?.takeIf { it.isNotBlank() } ?: "Bilinmeyen sanatçı",
            album = album?.takeIf { it.isNotBlank() },
            videoId = extractVideoId(cleanBase),
            durationMs = duration,
            file = file,
            artworkFile = art,
            lastModified = file.lastModified(),
        )
    }

    companion object {
        val AUDIO_EXTS = setOf("m4a", "mp3", "opus", "ogg", "webm", "flac", "wav", "aac")

        private val VIDEO_ID_REGEX = Regex("""\[([\w-]{11})]$""")

        /**
         * Extracts the 11-character YouTube video ID from a filename (without extension).
         * e.g. "Daft Punk - Get Lucky [5NV6Rdv1a3I]" -> "5NV6Rdv1a3I"
         */
        fun extractVideoId(filenameWithoutExtension: String): String? {
            return VIDEO_ID_REGEX.find(filenameWithoutExtension.trim())?.groupValues?.get(1)
        }

        /**
         * Removes the trailing " [videoId]" added by yt-dlp output template.
         */
        fun cleanName(name: String): String = name.replace(Regex("""\s*\[[\w-]{6,}]$"""), "").trim()
    }
}
