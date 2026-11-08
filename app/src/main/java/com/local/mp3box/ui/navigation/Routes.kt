package com.local.mp3box.ui.navigation

object Routes {
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val NOW_PLAYING = "nowplaying"

    const val ARG_PLAYLIST_ID = "playlistId"
    const val PLAYLIST_DETAIL = "playlist/{$ARG_PLAYLIST_ID}"
    const val ADD_SONGS = "playlist/{$ARG_PLAYLIST_ID}/add"

    fun playlistDetail(playlistId: Long) = "playlist/$playlistId"
    fun addSongs(playlistId: Long) = "playlist/$playlistId/add"
}
