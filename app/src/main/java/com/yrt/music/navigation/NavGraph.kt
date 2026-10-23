package com.yrt.music.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.yrt.music.YrtApp
import com.yrt.music.data.repository.YouTubeMusicRepository
import com.yrt.music.ui.album.AlbumScreen
import com.yrt.music.ui.album.AlbumViewModel
import com.yrt.music.ui.artist.ArtistScreen
import com.yrt.music.ui.artist.ArtistViewModel
import com.yrt.music.ui.downloader.DownloaderScreen
import com.yrt.music.ui.downloader.DownloaderViewModel
import com.yrt.music.ui.discover.DiscoveryViewModel
import com.yrt.music.ui.library.LibraryScreen
import com.yrt.music.ui.library.LibraryViewModel
import com.yrt.music.ui.player.PlayerViewModel
import com.yrt.music.ui.search.SearchScreen
import com.yrt.music.ui.search.SearchViewModel

/**
 * Sealed destination hierarchy matching PROJECT.md § 4. Navigation Routes Contract.
 */
sealed class Screen(val route: String) {
    data object Search : Screen("search")
    data object Library : Screen("library")
    data object Downloads : Screen("downloads")

    data object Artist : Screen("artist/{artistId}") {
        const val ARG_ARTIST_ID = "artistId"
        fun createRoute(artistId: String): String = "artist/$artistId"
    }

    data object Album : Screen("album/{albumId}") {
        const val ARG_ALBUM_ID = "albumId"
        fun createRoute(albumId: String): String = "album/$albumId"
    }
}

/**
 * Model representing bottom navigation tabs.
 */
data class NavTabItem(
    val screen: Screen,
    val title: String,
    val icon: ImageVector,
)

val MainNavTabs = listOf(
    NavTabItem(Screen.Search, "Keşfet", Icons.Default.Explore),
    NavTabItem(Screen.Library, "Kitaplık", Icons.Default.LibraryMusic),
    NavTabItem(Screen.Downloads, "İndirilenler", Icons.Default.Download),
)

/**
 * Top-level application NavHost connecting all top-level tabs and stack routes.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    libraryVm: LibraryViewModel,
    downloaderVm: DownloaderViewModel,
    playerVm: PlayerViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Search.route,
    searchVm: SearchViewModel? = null,
    onNavigateToArtist: (String) -> Unit = { artistId ->
        navController.navigate(Screen.Artist.createRoute(artistId))
    },
    onNavigateToAlbum: (String) -> Unit = { albumId ->
        navController.navigate(Screen.Album.createRoute(albumId))
    },
) {
    val context = LocalContext.current
    val app = context.applicationContext as? YrtApp

    val effectiveSearchVm: SearchViewModel = searchVm ?: viewModel(
        factory = SearchViewModel.provideFactory(
            repository = app?.container?.youTubeMusicRepository ?: YouTubeMusicRepository(),
            localSongLookup = app?.container?.songRepository,
        )
    )

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Screen.Search.route) {
            val discoveryVm: DiscoveryViewModel? = if (app != null) viewModel(
                factory = DiscoveryViewModel.provideFactory(
                    app.container.discoveryRepository,
                    app.container.downloadManager,
                    app.container.songRepository,
                ),
            ) else null
            SearchScreen(
                vm = effectiveSearchVm,
                discoveryVm = discoveryVm,
                contentPadding = contentPadding,
                onArtistClick = onNavigateToArtist,
                onAlbumClick = onNavigateToAlbum,
                onPlayTrack = { song -> playerVm.playSongs(listOf(song), 0) },
                onPlayRemoteTrack = { track -> playerVm.playTracks(listOf(track), 0) },
            )
        }

        composable(Screen.Library.route) {
            val playerState by playerVm.state.collectAsState()
            LibraryScreen(
                vm = libraryVm,
                currentMediaId = playerState.currentMediaId,
                contentPadding = contentPadding,
                onPlay = { songs, index -> playerVm.playSongs(songs, index) },
                onDeleted = { playerVm.removeFromQueue(it.id) },
            )
        }

        composable(Screen.Downloads.route) {
            DownloaderScreen(
                vm = downloaderVm,
                contentPadding = contentPadding,
            )
        }

        composable(
            route = Screen.Artist.route,
            arguments = listOf(
                navArgument(Screen.Artist.ARG_ARTIST_ID) { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val artistId = backStackEntry.arguments?.getString(Screen.Artist.ARG_ARTIST_ID).orEmpty()
            val playerState by playerVm.state.collectAsState()

            val artistVm: ArtistViewModel = if (app != null) {
                viewModel(
                    key = "artist_$artistId",
                    factory = ArtistViewModel.provideFactory(
                        repository = app.container.youTubeMusicRepository,
                        downloadManager = app.container.downloadManager,
                        songRepository = app.container.songRepository,
                    ),
                )
            } else {
                viewModel(key = "artist_$artistId")
            }

            ArtistScreen(
                artistId = artistId,
                vm = artistVm,
                contentPadding = contentPadding,
                currentMediaId = playerState.currentMediaId,
                isPlaying = playerState.isPlaying,
                onBackClick = { navController.popBackStack() },
                onAlbumClick = onNavigateToAlbum,
                onPlaySongs = { songs, index -> playerVm.playSongs(songs, index) },
                onPlayTracks = playerVm::playTracks,
            )
        }

        composable(
            route = Screen.Album.route,
            arguments = listOf(
                navArgument(Screen.Album.ARG_ALBUM_ID) { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getString(Screen.Album.ARG_ALBUM_ID).orEmpty()
            val playerState by playerVm.state.collectAsState()

            val albumVm: AlbumViewModel = if (app != null) {
                viewModel(
                    key = "album_$albumId",
                    factory = AlbumViewModel.provideFactory(
                        repository = app.container.youTubeMusicRepository,
                        downloadManager = app.container.downloadManager,
                        songRepository = app.container.songRepository,
                    ),
                )
            } else {
                viewModel(key = "album_$albumId")
            }

            AlbumScreen(
                albumId = albumId,
                vm = albumVm,
                contentPadding = contentPadding,
                currentMediaId = playerState.currentMediaId,
                isPlaying = playerState.isPlaying,
                onBackClick = { navController.popBackStack() },
                onArtistClick = onNavigateToArtist,
                onPlaySongs = { songs, index -> playerVm.playSongs(songs, index) },
                onPlayTracks = playerVm::playTracks,
            )
        }
    }
}
