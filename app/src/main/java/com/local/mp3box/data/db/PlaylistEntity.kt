package com.local.mp3box.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverPath: String? = null,   // absolute path under filesDir/covers/
    val createdAt: Long,
    val updatedAt: Long              // bumped by add/remove/reorder/rename/cover; grid sort key
)
