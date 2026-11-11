package com.local.mp3box.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    // Spec §5.5: the library lists playable files only. A missing track's row survives a scan
    // just long enough to back the playlist stub that still points at it.
    @Query("SELECT * FROM tracks WHERE isMissing = 0 ORDER BY title ASC")
    fun observeTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isMissing = 0 ORDER BY title ASC")
    suspend fun getTracks(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observeTrack(id: Long): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun findById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): TrackEntity?

    @Query("SELECT COUNT(*) FROM tracks WHERE isMissing = 0")
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

    /** Present rows that this listing no longer contains: the files lost by this scan. */
    @Query("SELECT COUNT(*) FROM tracks WHERE isMissing = 0 AND uri NOT IN (:seenUris)")
    suspend fun countNewlyMissing(seenUris: List<String>): Int

    /**
     * Spec §5.5: a missing file no playlist points at has nothing left to show, so the row is
     * dropped for good. Rows still referenced stay to render the playlist's "File missing" stub.
     */
    @Query("DELETE FROM tracks WHERE isMissing = 1 AND id NOT IN (SELECT trackId FROM playlist_items)")
    suspend fun deleteOrphanedMissing()

    @Query("SELECT COUNT(*) FROM tracks WHERE isMissing = 0")
    suspend fun countAvailable(): Int
}
