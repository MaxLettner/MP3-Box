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

/** Manual dependency container owned by the Application; screens only see the repositories. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "mp3box.db"
    ).addMigrations(MIGRATION_1_2).build()

    private val trackDao: TrackDao get() = database.trackDao()
    private val playlistDao: PlaylistDao get() = database.playlistDao()

    private val settings: SettingsStore = SettingsStore(appContext)

    private val mediaScanner: MediaScanner = MediaScanner(appContext)

    val musicFolderRepository: MusicFolderRepository =
        MusicFolderRepository(appContext, settings, trackDao, mediaScanner)

    private val playbackStateRepository: PlaybackStateRepository =
        PlaybackStateRepository(database.playbackStateDao())

    private val coverArtStore: CoverArtStore = CoverArtStore(appContext)

    val playlistRepository: PlaylistRepository = PlaylistRepository(playlistDao, coverArtStore)

    /** Connects to [com.local.mp3box.playback.PlaybackService] once for the app's lifetime. */
    val playerRepository: PlayerRepository = PlayerRepository(
        appContext,
        trackDao,
        playlistDao,
        playbackStateRepository
    )
}
