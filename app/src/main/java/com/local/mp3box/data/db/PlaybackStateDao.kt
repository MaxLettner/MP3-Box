package com.local.mp3box.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaybackStateDao {

    @Query("SELECT * FROM playback_state WHERE id = :id")
    suspend fun get(id: Int): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: PlaybackStateEntity)

    @Query("DELETE FROM playback_state")
    suspend fun clear()
}
