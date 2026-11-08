package com.local.mp3box.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tracks", indices = [Index(value = ["uri"], unique = true)])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,                 // document URI, stable key for re-scans
    val fileName: String,            // e.g. "01_Intro_V2.mp3"
    @ColumnInfo(collate = ColumnInfo.NOCASE) val title: String,  // derived, per spec §4.2
    val durationMs: Long,
    val sizeBytes: Long,
    val lastModified: Long,
    val isMissing: Boolean = false,
    val lastSeenAt: Long
)
