package com.yrt.music

import com.yrt.music.data.model.Song
import com.yrt.music.ui.library.ArtistNames
import com.yrt.music.ui.library.groupSongsByArtist
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ArtistGroupingTest {

    private fun song(id: String, title: String, artist: String) = Song(
        id = id, title = title, artist = artist, durationMs = 180_000,
        file = File("$id.m4a"), artworkFile = null, lastModified = 0L,
    )

    private fun names(raw: String) = ArtistNames.parse(raw).artists

    @Test
    fun parsesCommonCollaborationFormats() {
        val expected = listOf("Semicenk", "Büken")
        assertEquals(expected, names("Semicenk, Büken"))
        assertEquals(expected, names("Semicenk & Büken"))
        assertEquals(expected, names("Semicenk feat. Büken"))
        assertEquals(expected, names("Semicenk ft Büken"))
        assertEquals(expected, names("Semicenk (feat. Büken)"))
        assertEquals(expected, names("Semicenk x Büken"))
        assertEquals(expected, names("Semicenk ve Büken"))
        assertEquals(listOf("Semicenk"), names("Semicenk - Topic"))
    }

    @Test
    fun keepsKnownNamesWithSeparators() {
        assertEquals(listOf("Tyler, The Creator"), names("Tyler, The Creator"))
        assertEquals(listOf("Simon & Garfunkel", "Foo"), names("Simon & Garfunkel feat. Foo"))
    }

    @Test
    fun collaborationsMergeIntoSoloArtist() {
        val library = listOf(
            song("1", "Solo A", "Semicenk"),
            song("2", "Solo B", "semicenk"),
            song("3", "Duet", "Semicenk, Büken"),
            song("4", "Duet 2", "Semicenk & Büken"),
            song("5", "Reverse", "Büken, Semicenk"),
        )
        val groups = groupSongsByArtist(library)
        assertEquals(listOf("Semicenk"), groups.map { it.name })
        assertEquals(5, groups.single().songs.size)
        assertEquals(listOf("Büken"), groups.single().featuring["3"])
    }

    @Test
    fun searchKeepsGroupingStable() {
        val library = listOf(
            song("1", "Solo", "Semicenk"),
            song("2", "Duet", "Büken, Semicenk"),
        )
        val filtered = library.filter { it.title == "Duet" }
        assertEquals(listOf("Semicenk"), groupSongsByArtist(filtered, library).map { it.name })
    }
}
