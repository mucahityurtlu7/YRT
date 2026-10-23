package com.yrt.music

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Explore
import com.yrt.music.navigation.MainNavTabs
import com.yrt.music.navigation.Screen
import com.yrt.music.ui.components.formatDuration
import com.yrt.music.ui.player.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAndPlayerIntegrationTest {

    @Test
    fun testScreenRoutesContract() {
        assertEquals("search", Screen.Search.route)
        assertEquals("library", Screen.Library.route)
        assertEquals("downloads", Screen.Downloads.route)

        assertEquals("artist/{artistId}", Screen.Artist.route)
        assertEquals("artist/UC_test_artist_123", Screen.Artist.createRoute("UC_test_artist_123"))
        assertEquals("artistId", Screen.Artist.ARG_ARTIST_ID)

        assertEquals("album/{albumId}", Screen.Album.route)
        assertEquals("album/MPREb_test_album_456", Screen.Album.createRoute("MPREb_test_album_456"))
        assertEquals("albumId", Screen.Album.ARG_ALBUM_ID)
    }

    @Test
    fun testMainNavTabsHierarchy() {
        assertEquals(3, MainNavTabs.size)

        val searchTab = MainNavTabs[0]
        assertEquals(Screen.Search, searchTab.screen)
        assertEquals("Keşfet", searchTab.title)
        assertEquals(Icons.Default.Explore, searchTab.icon)

        val libraryTab = MainNavTabs[1]
        assertEquals(Screen.Library, libraryTab.screen)
        assertEquals("Kitaplık", libraryTab.title)
        assertEquals(Icons.Default.LibraryMusic, libraryTab.icon)

        val downloadsTab = MainNavTabs[2]
        assertEquals(Screen.Downloads, downloadsTab.screen)
        assertEquals("İndirilenler", downloadsTab.title)
        assertEquals(Icons.Default.Download, downloadsTab.icon)
    }

    @Test
    fun testPlayerUiStateMediaPresence() {
        val emptyState = PlayerUiState()
        assertFalse(emptyState.hasMedia)

        val activeState = PlayerUiState(
            currentMediaId = "song_42",
            title = "Get Lucky",
            artist = "Daft Punk",
            isPlaying = true,
            positionMs = 30_000L,
            durationMs = 240_000L,
        )
        assertTrue(activeState.hasMedia)
        assertEquals("song_42", activeState.currentMediaId)
        assertEquals("Get Lucky", activeState.title)
    }

    @Test
    fun testPlayerProgressCalculation() {
        fun calculateProgress(positionMs: Long, durationMs: Long): Float {
            return if (durationMs > 0) {
                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
        }

        assertEquals(0f, calculateProgress(0L, 0L), 0.0001f)
        assertEquals(0.5f, calculateProgress(30_000L, 60_000L), 0.0001f)
        assertEquals(1.0f, calculateProgress(70_000L, 60_000L), 0.0001f)
        assertEquals(0.25f, calculateProgress(15_000L, 60_000L), 0.0001f)
    }

    @Test
    fun testDurationFormatting() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("0:45", formatDuration(45_000L))
        assertEquals("3:42", formatDuration(222_000L))
        assertEquals("1:02:03", formatDuration(3_723_000L))
    }
}
