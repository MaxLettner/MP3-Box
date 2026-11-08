package com.local.mp3box.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun observeTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY title ASC")
    suspend fun getTracks(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observeTrack(id: Long): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun findById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): TrackEntity?

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(track: TrackEntity): Long

    @Query(
        "UPDATE tracks SET title = :title, durationMs = :durationMs, sizeBytes = :sizeBytes, " +
            "lastModified = :lastModified, isMissing = 0, lastSeenAt = :lastSeenAt WHERE id = :id"
    )
    suspend fun updateFromScan(
        id: Long,
        title: String,
        durationMs: Long,
        sizeBytes: Long,
        lastModified: Long,
        lastSeenAt: Long
    )

    @Query("UPDATE tracks SET isMissing = 0, lastSeenAt = :lastSeenAt WHERE id = :id")
    suspend fun markSeen(id: Long, lastSeenAt: Long)

    @Query("UPDATE tracks SET isMissing = 1 WHERE uri NOT IN (:seenUris)")
    suspend fun markMissingExcept(seenUris: List<String>)

    @Query("UPDATE tracks SET isMissing = 1")
    suspend fun markAllMissing()

    @Query("DELETE FROM tracks WHERE isMissing = 1")
    suspend fun deleteMissing()

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun countTracks(): Int

    @Query("SELECT COUNT(*) FROM tracks WHERE isMissing = 1")
    suspend fun countMissing(): Int
}
