package com.local.mp3box.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.local.mp3box.R
import com.local.mp3box.ui.components.MiniPlayer
import com.local.mp3box.ui.navigation.Mp3BoxNavHost
import com.local.mp3box.ui.navigation.Routes

private data class Tab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)

private val tabs = listOf(
    Tab(Routes.LIBRARY, R.string.nav_library, Icons.Filled.LibraryMusic),
    Tab(Routes.PLAYLISTS, R.string.nav_playlists, Icons.AutoMirrored.Filled.QueueMusic)
)

@Composable
fun AppRoot() {
    val player = appContainer().playerRepository
    val playback by player.state.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route
    val showBar = tabs.any { it.route == currentRoute }
    val showMini = playback.currentTrackId != null && currentRoute != Routes.NOW_PLAYING

    // Spec §6.6: the snapshot is written when the app leaves the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.flushSnapshot()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        bottomBar = {
            Column {
                if (showMini) {
                    // Polled only while the bar is on screen and the app is in the foreground.
                    val positionMs by player.positionMs.collectAsStateWithLifecycle(
                        initialValue = player.currentPositionMs
                    )
                    MiniPlayer(
                        title = playback.currentTitle,
                        isPlaying = playback.isPlaying,
                        progress = if (playback.durationMs > 0) {
                            positionMs.toFloat() / playback.durationMs
                        } else {
                            0f
                        },
                        onOpen = {
                            navController.navigate(Routes.NOW_PLAYING) { launchSingleTop = true }
                        },
                        onTogglePlayPause = player::togglePlayPause,
                        onNext = player::next,
                        // Outside the tabs (playlist detail, add songs) there is no
                        // NavigationBar to absorb the system navigation inset, so the bar
                        // would sit on top of the 3-button navigation. Pad it here so it
                        // keeps the same height above the system bars as on the tabs.
                        modifier = if (showBar) Modifier else Modifier.navigationBarsPadding()
                    )
                }
                if (showBar) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.labelRes)) }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // Now Playing is a full-screen destination; every other route clears the bottom bars.
        Mp3BoxNavHost(
            navController = navController,
            modifier = if (currentRoute == Routes.NOW_PLAYING) Modifier else Modifier.padding(innerPadding)
        )
    }
}
