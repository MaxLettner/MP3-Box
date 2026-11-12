package com.local.mp3box.data.db

import androidx.media3.common.Player
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single row, always written with [PLAYBACK_STATE_ID]. */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val id: Int = PLAYBACK_STATE_ID,
    val playlistId: Long?,
    val trackId: Long?,
    val positionMs: Long,            // written with the snapshot; not restored in v1
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    /** Track ids in play order, comma-separated; null/blank when shuffle was off. */
    val shuffleOrder: String? = null,
    val updatedAt: Long
)

const val PLAYBACK_STATE_ID = 1      // read with WHERE id = :PLAYBACK_STATE_ID
