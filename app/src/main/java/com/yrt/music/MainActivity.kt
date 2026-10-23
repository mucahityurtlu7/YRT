package com.yrt.music

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yrt.music.navigation.AppNavHost
import com.yrt.music.navigation.MainNavTabs
import com.yrt.music.navigation.Screen
import com.yrt.music.ui.components.MiniPlayer
import com.yrt.music.ui.components.StreamingNavigationBar
import com.yrt.music.ui.downloader.DownloaderViewModel
import com.yrt.music.ui.library.LibraryViewModel
import com.yrt.music.ui.player.PlayerScreen
import com.yrt.music.ui.player.PlayerViewModel
import com.yrt.music.ui.search.SearchViewModel
import com.yrt.music.ui.settings.SettingsViewModel
import com.yrt.music.ui.theme.YrtTheme

class MainActivity : ComponentActivity() {

    private val downloaderVm: DownloaderViewModel by viewModels()
    private val settingsVm: SettingsViewModel by viewModels()
    private val libraryVm: LibraryViewModel by viewModels()
    private val playerVm: PlayerViewModel by viewModels()
    private val searchVm: SearchViewModel by viewModels {
        val app = application as YrtApp
        SearchViewModel.provideFactory(
            repository = app.container.youTubeMusicRepository,
            localSongLookup = app.container.songRepository,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            YrtTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppRoot(downloaderVm, settingsVm, libraryVm, playerVm, searchVm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                downloaderVm.onSharedText(it)
            }
        }
    }
}

@Composable
private fun AppRoot(
    downloaderVm: DownloaderViewModel,
    settingsVm: SettingsViewModel,
    libraryVm: LibraryViewModel,
    playerVm: PlayerViewModel,
    searchVm: SearchViewModel,
) {
    val navController = rememberNavController()
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    val playerState by playerVm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        downloaderVm.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        settingsVm.messages.collect { snackbar.showSnackbar(it) }
    }

    // Android 13+: media / download notifications need runtime permission
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = showPlayer) { showPlayer = false }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedVisibility(
                        visible = playerState.hasMedia,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        MiniPlayer(
                            state = playerState,
                            onClick = { showPlayer = true },
                            onPlayPause = playerVm::togglePlayPause,
                            onNext = playerVm::next,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }

                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    StreamingNavigationBar(
                        selectedScreen = MainNavTabs.firstOrNull { tab ->
                            currentDestination?.hierarchy?.any { it.route == tab.screen.route } == true
                        }?.screen,
                        onSelectScreen = { screen ->
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            },
        ) { padding ->
            AppNavHost(
                navController = navController,
                libraryVm = libraryVm,
                settingsVm = settingsVm,
                playerVm = playerVm,
                searchVm = searchVm,
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = showPlayer && playerState.hasMedia,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                PlayerScreen(
                    state = playerState,
                    vm = playerVm,
                    onClose = { showPlayer = false },
                )
            }
        }
    }
}
