package com.local.mp3box.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.local.mp3box.ui.library.LibraryScreen
import com.local.mp3box.ui.nowplaying.NowPlayingScreen
import com.local.mp3box.ui.playlist.AddSongsScreen
import com.local.mp3box.ui.playlist.PlaylistDetailScreen
import com.local.mp3box.ui.playlists.PlaylistsScreen

@Composable
fun Mp3BoxNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.LIBRARY,
        modifier = modifier
    ) {
        composable(Routes.LIBRARY) {
            LibraryScreen()
        }
        composable(Routes.PLAYLISTS) {
            PlaylistsScreen(
                onCreatePlaylist = { id -> navController.navigate(Routes.addSongs(id)) },
                onPlaylistClick = { id -> navController.navigate(Routes.playlistDetail(id)) }
            )
        }
        composable(
            route = Routes.PLAYLIST_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_PLAYLIST_ID) { type = NavType.LongType })
        ) { entry ->
            val playlistId = entry.arguments?.getLong(Routes.ARG_PLAYLIST_ID) ?: 0L
            PlaylistDetailScreen(
                playlistId = playlistId,
                onBack = { navController.popBackStack() },
                onAddSongs = { navController.navigate(Routes.addSongs(playlistId)) }
            )
        }
        composable(
            route = Routes.ADD_SONGS,
            arguments = listOf(navArgument(Routes.ARG_PLAYLIST_ID) { type = NavType.LongType })
        ) { entry ->
            val playlistId = entry.arguments?.getLong(Routes.ARG_PLAYLIST_ID) ?: 0L
            AddSongsScreen(
                playlistId = playlistId,
                onBack = { navController.popBackStack() },
                onAdded = { navController.popBackStack() }
            )
        }
        composable(Routes.NOW_PLAYING) {
            NowPlayingScreen(onClose = { navController.popBackStack() })
        }
    }
}
