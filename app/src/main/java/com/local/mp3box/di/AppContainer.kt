package com.local.mp3box.di

import android.content.Context
import androidx.room.Room
import com.local.mp3box.data.CoverArtStore
import com.local.mp3box.data.MediaScanner
import com.local.mp3box.data.MusicFolderRepository
import com.local.mp3box.data.PlaybackStateRepository
import com.local.mp3box.data.PlaylistRepository
import com.local.mp3box.data.SettingsStore
import com.local.mp3box.data.db.AppDatabase
import com.local.mp3box.data.db.MIGRATION_1_2
import com.local.mp3box.data.db.PlaylistDao
import com.local.mp3box.data.db.TrackDao
import com.local.mp3box.playback.PlayerRepository

/** Manual dependency container owned by the Application. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "mp3box.db"
    ).addMigrations(MIGRATION_1_2).build()

    val trackDao: TrackDao get() = database.trackDao()
    val playlistDao: PlaylistDao get() = database.playlistDao()

    val settings: SettingsStore = SettingsStore(appContext)

    val mediaScanner: MediaScanner = MediaScanner(appContext)

    val musicFolderRepository: MusicFolderRepository =
        MusicFolderRepository(appContext, settings, trackDao, mediaScanner)

    val playbackStateRepository: PlaybackStateRepository =
        PlaybackStateRepository(database.playbackStateDao())

    val coverArtStore: CoverArtStore = CoverArtStore(appContext)

    val playlistRepository: PlaylistRepository = PlaylistRepository(playlistDao, coverArtStore)

    /** Connects to [com.local.mp3box.playback.PlaybackService] once for the app's lifetime. */
    val playerRepository: PlayerRepository = PlayerRepository(
        appContext,
        trackDao,
        playlistDao,
        playbackStateRepository
    )
}
