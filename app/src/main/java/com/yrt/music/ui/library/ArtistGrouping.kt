package com.yrt.music.ui.library

import androidx.compose.runtime.Immutable
import com.yrt.music.data.model.Song
import java.text.Normalizer
import java.util.Locale

internal const val UNKNOWN_ARTIST = "Bilinmeyen sanatçı"

/** All artists credited on one song, in the order they were written. */
internal data class ArtistCredit(val artists: List<String>)

/** One library artist card: its own songs plus the collaborations it leads. */
@Immutable
data class ArtistGroup(
    val name: String,
    val songs: List<Song>,
    /** songId -> other credited artists (e.g. "Semicenk, Büken" shown under Semicenk -> ["Büken"]). */
    val featuring: Map<String, List<String>> = emptyMap(),
) {
    val cover: Song? get() = songs.firstOrNull { it.artworkFile != null } ?: songs.firstOrNull()
    val totalMs: Long get() = songs.sumOf { it.durationMs }
}

internal object ArtistNames {

    /** Real artist names that contain separator characters and must never be split. */
    private val protectedNames = listOf(
        "Tyler, The Creator",
        "Earth, Wind & Fire",
        "Crosby, Stills, Nash & Young",
        "Crosby, Stills & Nash",
        "Simon & Garfunkel",
        "Hall & Oates",
        "Mumford & Sons",
        "Brooks & Dunn",
        "Sonny & Cher",
        "Kool & The Gang",
        "Ike & Tina Turner",
        "Hootie & The Blowfish",
        "Panic! At The Disco",
    ).map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }

    private const val COMMA = '\uE000'
    private const val AMP = '\uE001'
    private const val SEMI = '\uE002'

    private val topicSuffix = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)
    private val bracketFeat = Regex(
        """[(\[]\s*(?:feat|ft|featuring)\.?\s+([^)\]]*)[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val separators = Regex(
        """\s*[,;]\s*|\s+[&×]\s+|\s+(?:feat\.?|ft\.?|featuring|x|ve)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val marks = Regex("""\p{Mn}+""")
    private val spaces = Regex("""\s+""")

    /** Splits "Semicenk, Büken", "A & B", "A feat. B", "A (ft. B)", "A x B" ... into individual artists. */
    fun parse(raw: String?): ArtistCredit {
        var s = raw.orEmpty().trim().replace(topicSuffix, "")
        s = bracketFeat.replace(s) { " feat. ${it.groupValues[1]}" }
        protectedNames.forEach { re ->
            s = re.replace(s) { m ->
                m.value.replace(',', COMMA).replace('&', AMP).replace(';', SEMI)
            }
        }
        val seen = HashSet<String>()
        val artists = separators.split(s)
            .map { it.replace(COMMA, ',').replace(AMP, '&').replace(SEMI, ';').trim() }
            .filter { it.isNotEmpty() && !isUnknown(it) && seen.add(key(it)) }
        return ArtistCredit(artists.ifEmpty { listOf(UNKNOWN_ARTIST) })
    }

    private fun isUnknown(name: String): Boolean {
        val k = name.lowercase(Locale.ROOT)
        return k == "unknown" || k == "<unknown>" || k == "unknown artist" || name == UNKNOWN_ARTIST
    }

    /** Case-, accent- and Turkish-dotless-i-insensitive identity: "Büken" == "buken" == "BÜKEN". */
    fun key(name: String): String =
        Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
            .replace(marks, "")
            .lowercase(Locale.ROOT)
            .replace('ı', 'i')
            .replace(spaces, " ")
}

/**
 * Groups songs under one card per artist; collaborations are merged into the lead artist.
 *
 * The lead artist of a collaboration is the first credited artist. If that artist has no solo
 * songs in the [library] but another credited artist does, the song goes to that artist instead,
 * so "Büken, Semicenk" still lands under Semicenk when only Semicenk has songs of his own.
 * [library] is the full collection (used for the solo counts) so grouping stays stable while searching.
 */
internal fun groupSongsByArtist(songs: List<Song>, library: List<Song> = songs): List<ArtistGroup> {
    val credits = HashMap<String, ArtistCredit>()
    fun credit(raw: String) = credits.getOrPut(raw) { ArtistNames.parse(raw) }

    val soloCount = HashMap<String, Int>()
    library.forEach { song ->
        val c = credit(song.artist)
        if (c.artists.size == 1) soloCount.merge(ArtistNames.key(c.artists[0]), 1, Int::plus)
    }
    fun solo(name: String) = soloCount[ArtistNames.key(name)] ?: 0

    fun lead(c: ArtistCredit): String {
        val first = c.artists.first()
        if (c.artists.size == 1 || solo(first) > 0) return first
        val best = c.artists.maxByOrNull { solo(it) } ?: first
        return if (solo(best) > 0) best else first
    }

    return songs
        .map { song ->
            val c = credit(song.artist)
            Triple(song, lead(c), c)
        }
        .groupBy { ArtistNames.key(it.second) }
        .values
        .map { entries ->
            val name = entries.groupingBy { it.second }.eachCount().maxByOrNull { it.value }!!.key
            val leadKey = ArtistNames.key(name)
            val sorted = entries.sortedBy { it.first.title.lowercase(Locale.ROOT) }
            ArtistGroup(
                name = name,
                songs = sorted.map { it.first },
                featuring = sorted
                    .mapNotNull { (song, _, c) ->
                        val others = c.artists.filter { ArtistNames.key(it) != leadKey }
                        if (others.isEmpty()) null else song.id to others
                    }
                    .toMap(),
            )
        }
        .sortedWith(compareBy({ it.name == UNKNOWN_ARTIST }, { it.name.lowercase(Locale.ROOT) }))
}
